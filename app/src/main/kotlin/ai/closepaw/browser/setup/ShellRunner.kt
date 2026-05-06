package ai.closepaw.browser.setup

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * Shared `Process`-style shell runner backed by `Shizuku.newProcess`. Centralizes the reflection
 * that is duplicated across [ai.closepaw.browser.cdp.shizuku.ShizukuChromeRunningProbe] and
 * [ai.closepaw.platform.virtualdisplay.ShizukuShellExecutor]: `newProcess` is private on some
 * Shizuku builds and public on others; we resolve via reflection so both shapes work.
 *
 * Concrete consumers in this package: [CommandLineWriter] (write `chrome-command-line`),
 * [ChromeFlagDeepLink] (`am start` fallback), and [ChromeCdpProbe]'s OEM fallback that reads
 * `/proc/net/unix` through shell uid when the app uid is denied.
 *
 * Returns stdout AND exit code so callers can both read content and decide success.
 */
interface ShellRunner {
    suspend fun run(command: Array<String>): ShellResult

    data class ShellResult(val exitCode: Int, val stdout: String)
}

/**
 * Production [ShellRunner] using Shizuku's shell-uid `newProcess`. Blocking; callers MUST
 * dispatch to `Dispatchers.IO` themselves — keeping the dispatch decision at the call site is
 * cheaper than nesting another `withContext(IO)` here.
 */
internal class ShizukuShellRunner(
    private val timeoutSec: Long = DEFAULT_TIMEOUT_SEC,
) : ShellRunner {

    override suspend fun run(command: Array<String>): ShellRunner.ShellResult {
        val process = runCatching { newProcessViaShizuku(command) }.getOrElse {
            Log.w(TAG, "Shizuku newProcess failed: ${command.joinToString(" ")}", it)
            return ShellRunner.ShellResult(exitCode = -1, stdout = "")
        }
        return try {
            runDrained(process, timeoutSec)
        } catch (e: Throwable) {
            Log.w(TAG, "shell failed: ${command.joinToString(" ")}", e)
            ShellRunner.ShellResult(exitCode = -1, stdout = "")
        }
    }

    private fun newProcessViaShizuku(command: Array<String>): Process {
        val cls = Shizuku::class.java
        val method = runCatching {
            cls.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
        }.getOrNull() ?: cls.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null) as Process
    }

    companion object {
        private const val TAG = "ShizukuShellRunner"
        private const val DEFAULT_TIMEOUT_SEC = 5L
        private const val DRAIN_JOIN_TIMEOUT_MS = 1_000L

        /**
         * Wait for [process] to exit (or [timeoutSec] elapse) while concurrently draining its
         * stdout and stderr. Without this, a child that emits more than the OS pipe buffer
         * (~64 KiB on Linux) blocks inside its `write()` until the reader catches up — a serial
         * "waitFor then read" trips our timeout instead. ChromeCdpProbe used to hit this with
         * `cat /proc/net/unix` (~50 KiB on nubia P0110) before being narrowed to a `grep -F`.
         *
         * Stderr is read but discarded — not exposed on [ShellRunner.ShellResult] (no current
         * caller needs it). Reader threads are daemons so they never keep the JVM alive on
         * shutdown.
         *
         * Visible to JVM tests so they can drive a `ProcessBuilder`-spawned process through the
         * exact same code path that production uses with Shizuku.
         */
        internal fun runDrained(process: Process, timeoutSec: Long): ShellRunner.ShellResult {
            val stdoutBuf = ByteArrayOutputStream()
            val stderrBuf = ByteArrayOutputStream()
            val stdoutThread = drainerThread("ShellRunner-stdout", process.inputStream, stdoutBuf)
            val stderrThread = drainerThread("ShellRunner-stderr", process.errorStream, stderrBuf)
            stdoutThread.start()
            stderrThread.start()
            val exited = waitForProcess(process, timeoutSec, TimeUnit.SECONDS)
            if (!exited) {
                runCatching { process.destroy() }
                Log.w(TAG, "shell timed out")
                runCatching { stdoutThread.join(DRAIN_JOIN_TIMEOUT_MS) }
                runCatching { stderrThread.join(DRAIN_JOIN_TIMEOUT_MS) }
                return ShellRunner.ShellResult(exitCode = -1, stdout = "")
            }
            // Process has exited; drainers will hit EOF and return naturally. Join before
            // reading the buffer to ensure the last bytes have landed.
            runCatching { stdoutThread.join(DRAIN_JOIN_TIMEOUT_MS) }
            runCatching { stderrThread.join(DRAIN_JOIN_TIMEOUT_MS) }
            return ShellRunner.ShellResult(
                exitCode = process.exitValue(),
                stdout = stdoutBuf.toString(Charsets.UTF_8.name()),
            )
        }

        private fun drainerThread(
            name: String,
            source: InputStream,
            sink: ByteArrayOutputStream,
        ): Thread {
            return Thread({
                try {
                    source.use { it.copyTo(sink) }
                } catch (_: Throwable) {
                    // Stream closed early (e.g. process.destroy() on timeout). Whatever has
                    // landed in `sink` is fine; nothing to recover.
                }
            }, name).apply { isDaemon = true }
        }

        /**
         * ShizukuRemoteProcess throws IllegalArgumentException instead of
         * IllegalThreadStateException when the process hasn't exited; mirror the workaround used
         * by the existing virtualdisplay executor.
         */
        private fun waitForProcess(process: Process, timeout: Long, unit: TimeUnit): Boolean {
            val startTime = System.nanoTime()
            val remNanos = unit.toNanos(timeout)
            var rem = remNanos
            var sleepMs = 10L
            do {
                try {
                    process.exitValue()
                    return true
                } catch (_: IllegalThreadStateException) {
                } catch (e: IllegalArgumentException) {
                    if (e.message?.contains("process hasn't exited") != true) throw e
                }
                if (rem > 0) {
                    try {
                        Thread.sleep(minOf(TimeUnit.NANOSECONDS.toMillis(rem) + 1, sleepMs))
                        sleepMs = minOf(sleepMs * 2, 100L)
                    } catch (_: InterruptedException) {
                        return false
                    }
                }
                rem = remNanos - (System.nanoTime() - startTime)
            } while (rem > 0)
            return false
        }
    }
}

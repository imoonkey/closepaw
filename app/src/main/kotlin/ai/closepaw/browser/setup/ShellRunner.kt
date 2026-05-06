package ai.closepaw.browser.setup

import android.util.Log
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
            if (!waitForProcess(process, timeoutSec, TimeUnit.SECONDS)) {
                runCatching { process.destroy() }
                Log.w(TAG, "shell timed out: ${command.joinToString(" ")}")
                return ShellRunner.ShellResult(exitCode = -1, stdout = "")
            }
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            ShellRunner.ShellResult(exitCode = process.exitValue(), stdout = stdout)
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

    companion object {
        private const val TAG = "ShizukuShellRunner"
        private const val DEFAULT_TIMEOUT_SEC = 5L
    }
}

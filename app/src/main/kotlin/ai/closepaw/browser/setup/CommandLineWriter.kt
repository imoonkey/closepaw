package ai.closepaw.browser.setup

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Idempotently writes Chrome's command-line file at `/data/local/tmp/chrome-command-line` so
 * Chrome binds the `chrome_devtools_remote` abstract socket once the user has flipped the
 * `enable-command-line-on-non-rooted-devices` flag and restarted Chrome.
 *
 * App uid cannot write to `/data/local/tmp/`; Shizuku gives us shell uid which can. We never
 * force-stop Chrome — the file is harmless until the user toggles the chrome flag.
 *
 * Idempotent: reads the existing file first and skips the write when content already matches,
 * so flipping the toggle off + on doesn't cause needless writes.
 */
class CommandLineWriter(
    private val shell: ShellRunner = ShizukuShellRunner(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * @return [Outcome.AlreadyCorrect] when the file already had the expected content (no-op),
     * [Outcome.Written] when we wrote it successfully, [Outcome.Failed] when the shell call
     * failed (Shizuku unavailable, exit non-zero, etc.). The Settings UI can ignore the result
     * — failure here is not user-actionable; the visible status comes from [ChromeCdpProbe].
     */
    suspend fun ensureWritten(): Outcome = withContext(ioDispatcher) {
        val current = runCatching { shell.run(arrayOf("sh", "-c", "cat $TARGET_PATH 2>/dev/null")) }
            .getOrNull()
        if (current?.exitCode == 0 && current.stdout.trim() == DESIRED_CONTENT.trim()) {
            return@withContext Outcome.AlreadyCorrect
        }
        val write = runCatching {
            shell.run(arrayOf("sh", "-c", "echo $QUOTED_CONTENT > $TARGET_PATH"))
        }.getOrElse {
            Log.w(TAG, "shell write threw", it)
            return@withContext Outcome.Failed
        }
        if (write.exitCode == 0) Outcome.Written else Outcome.Failed
    }

    enum class Outcome { AlreadyCorrect, Written, Failed }

    /** Indirection so tests don't need a real Shizuku binder. */
    interface ShellRunner {
        suspend fun run(command: Array<String>): ShellResult
    }

    data class ShellResult(val exitCode: Int, val stdout: String)

    /**
     * Production runner that spawns a shell-uid process via Shizuku and captures stdout. Uses
     * reflection like the existing virtualdisplay [ai.closepaw.platform.virtualdisplay
     * .ShizukuShellExecutor] does — `Shizuku.newProcess` is private on some Shizuku builds.
     */
    private class ShizukuShellRunner : ShellRunner {
        override suspend fun run(command: Array<String>): ShellResult {
            val process = newProcessViaShizuku(command)
            return try {
                if (!waitForProcess(process, EXEC_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    runCatching { process.destroy() }
                    Log.w(TAG, "shell command timed out: ${command.joinToString(" ")}")
                    return ShellResult(exitCode = -1, stdout = "")
                }
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                ShellResult(exitCode = process.exitValue(), stdout = stdout)
            } catch (e: Throwable) {
                Log.w(TAG, "shell command failed: ${command.joinToString(" ")}", e)
                ShellResult(exitCode = -1, stdout = "")
            }
        }

        private fun newProcessViaShizuku(command: Array<String>): Process {
            val shizukuClass = Shizuku::class.java
            val method = runCatching {
                shizukuClass.getMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                )
            }.getOrNull() ?: shizukuClass.getDeclaredMethod(
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
         * IllegalThreadStateException when the process hasn't exited; mirror the workaround
         * used by [ai.closepaw.platform.virtualdisplay.ShizukuShellExecutor].
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

    companion object {
        const val TARGET_PATH = "/data/local/tmp/chrome-command-line"

        /**
         * Chrome reads the first token (process name) and ignores it; everything after is
         * appended to the command line. The two flags below are what Chromium's
         * DevToolsServer::IsAllowed checks: a remote-debugging-socket-name flag tells Chrome
         * to bind the named abstract socket. NetworkService is already default in modern
         * Chrome but specifying it keeps behavior consistent across older Chrome variants.
         */
        const val DESIRED_CONTENT =
            "_ --remote-debugging-socket-name=chrome_devtools_remote --enable-features=NetworkService"

        /** Single-quoted form for `sh -c "echo '...' > path"`. */
        private const val QUOTED_CONTENT = "'$DESIRED_CONTENT'"
        private const val EXEC_TIMEOUT_SEC = 5L
        private const val TAG = "ChromeCmdLineWriter"
    }
}

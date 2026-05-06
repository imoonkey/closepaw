package ai.closepaw.browser.setup

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     * failed (Shizuku unavailable, exit non-zero, etc.). Callers should surface failure to the
     * user — when the write fails the toggle should not stay on.
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
        private const val TAG = "ChromeCmdLineWriter"
    }
}

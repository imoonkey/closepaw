package ai.closepaw.browser.setup

import ai.closepaw.browser.cdp.shizuku.DefaultDevtoolsDiagnostics
import ai.closepaw.browser.cdp.shizuku.ShizukuChromeDevtoolsBridge
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Detects whether Chrome's `chrome_devtools_remote` abstract socket is currently bound.
 *
 * The socket only becomes visible when Chrome has unlocked DevTools — for stable on a
 * non-rooted device that requires the `chrome://flags#enable-command-line-on-non-rooted-devices`
 * flag. This probe lets the Settings UI tell the user whether the unlock has actually taken
 * effect.
 *
 * Two-stage read with token-aware matching:
 * 1. **App-uid read** of `/proc/net/unix` (the file is world-readable on AOSP-spec builds —
 *    same way [ai.closepaw.browser.cdp.wireless.ProcNetTcpListeners] reads `/proc/net/tcp`).
 * 2. **Shell-uid fallback** via the optional [shellRunner] for OEMs where SELinux denies app-uid
 *    access. We invoke `grep -F` directly (no `cat` of the full file) for two reasons:
 *      a) Shizuku's `Process` plumbing on some OEM builds doesn't drain stdout in parallel with
 *         the exit poll, so a 50 KB+ `cat /proc/net/unix` deadlocks the pipe and the runner
 *         times out — observed on nubia P0110 / Android 16. `grep -F` returns ~100 bytes,
 *         well under the pipe buffer, so the runner can read after exit without deadlock.
 *      b) Lower latency on every device.
 *    Without this fallback, locked-down builds would always report Unknown and never confirm
 *    success even when Chrome IS bound.
 *
 * Token matching is delegated to [DefaultDevtoolsDiagnostics.containsAbstractSocket] so this
 * probe and the runtime preflight diagnostic stay in lockstep — no risk of substring matches
 * accepting `@chrome_devtools_remote_unrelated_thing` or rejecting valid `_<pid>` suffixes.
 */
class ChromeCdpProbe(
    private val procNetUnix: File = DEFAULT_PROC_NET_UNIX,
    private val shellRunner: ShellRunner? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * @return [Result.Bound] when Chrome has bound the devtools socket, [Result.NotBound] when
     * `/proc/net/unix` was readable but the socket name is absent, [Result.Unknown] when the
     * file could not be read by either app uid or shell uid so the UI surfaces "Re-check"
     * rather than guessing.
     */
    suspend fun probe(): Result = withContext(ioDispatcher) {
        // Try app-uid first — cheaper, no Shizuku binder roundtrip.
        runCatching { procNetUnix.readText() }
            .getOrNull()
            ?.let { return@withContext parse(it) }

        // App uid was denied (SELinux on locked OEM builds, or some MDM policies). Fall
        // through to shell uid via Shizuku so the same kernel data is reachable.
        val runner = shellRunner ?: return@withContext Result.Unknown
        val shellResult = runCatching {
            runner.run(arrayOf("grep", "-F", GREP_NEEDLE, PROC_NET_UNIX_PATH))
        }.getOrNull() ?: return@withContext Result.Unknown
        // grep exit codes: 0 = matched, 1 = no match (file readable), 2 = error reading file.
        // Both 0 and 1 prove the file was readable via shell uid; only 2 (or our runner's -1
        // sentinel for binder/timeout failure) means we genuinely cannot probe.
        when (shellResult.exitCode) {
            0 -> parse(shellResult.stdout)
            1 -> Result.NotBound
            else -> Result.Unknown
        }
    }

    enum class Result { Bound, NotBound, Unknown }

    companion object {
        private const val PROC_NET_UNIX_PATH = "/proc/net/unix"
        private val DEFAULT_PROC_NET_UNIX = File(PROC_NET_UNIX_PATH)
        // Substring needle for grep — the strict token match still happens in
        // DefaultDevtoolsDiagnostics.containsAbstractSocket so `@chrome_devtools_remote_unrelated`
        // would be filtered out even though grep would surface the line.
        internal const val GREP_NEEDLE =
            "@" + ShizukuChromeDevtoolsBridge.CHROME_DEVTOOLS_SOCKET

        /**
         * Token-aware match — delegates to the runtime preflight parser so this probe and the
         * bridge cannot disagree about what counts as the chrome devtools socket.
         */
        internal fun parse(procNetUnix: String): Result {
            val bound = DefaultDevtoolsDiagnostics.containsAbstractSocket(
                procContent = procNetUnix,
                socketName = ShizukuChromeDevtoolsBridge.CHROME_DEVTOOLS_SOCKET,
            )
            return if (bound) Result.Bound else Result.NotBound
        }
    }
}

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
 *    access. Without this, locked-down builds would always report Unknown and never confirm
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
            runner.run(arrayOf("sh", "-c", "cat $PROC_NET_UNIX_PATH"))
        }.getOrNull() ?: return@withContext Result.Unknown
        if (shellResult.exitCode != 0) return@withContext Result.Unknown
        parse(shellResult.stdout)
    }

    enum class Result { Bound, NotBound, Unknown }

    companion object {
        private const val PROC_NET_UNIX_PATH = "/proc/net/unix"
        private val DEFAULT_PROC_NET_UNIX = File(PROC_NET_UNIX_PATH)

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

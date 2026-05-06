package ai.closepaw.browser.setup

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Detects whether Chrome's `chrome_devtools_remote` abstract socket is currently bound.
 *
 * The abstract socket only becomes visible when Chrome has unlocked DevTools — for stable on a
 * non-rooted device that requires the `chrome://flags#enable-command-line-on-non-rooted-devices`
 * flag. This probe lets the Settings UI tell the user whether the unlock has actually taken
 * effect.
 *
 * `/proc/net/unix` is global and readable from the app uid (same way [ai.closepaw.browser.cdp
 * .wireless.ProcNetTcpListeners] reads `/proc/net/tcp`), so we don't need Shizuku just to look
 * up a socket binding. The `@` prefix marks an abstract-namespace socket; Chrome appends the
 * renderer pid in newer Chromium builds, so we match the prefix.
 */
class ChromeCdpProbe(
    private val procNetUnix: File = DEFAULT_PROC_NET_UNIX,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * @return [Result.Bound] when Chrome has bound the devtools socket, [Result.NotBound] when
     * `/proc/net/unix` was readable but the socket name is absent, [Result.Unknown] when the
     * file could not be read so the UI can surface "Re-check" instead of pretending it knows.
     */
    suspend fun probe(): Result = withContext(ioDispatcher) {
        runCatching { procNetUnix.readText() }
            .map { parse(it) }
            .getOrElse { Result.Unknown }
    }

    enum class Result { Bound, NotBound, Unknown }

    companion object {
        const val SOCKET_NAME_PREFIX = "@chrome_devtools_remote"
        private val DEFAULT_PROC_NET_UNIX = File("/proc/net/unix")

        /**
         * Parses `/proc/net/unix` output looking for Chrome's abstract socket. The kernel
         * formats abstract-namespace sockets with a leading `@` byte (e.g.
         * `@chrome_devtools_remote_22310`).
         */
        internal fun parse(procNetUnix: String): Result =
            procNetUnix.lineSequence()
                .any { it.contains(SOCKET_NAME_PREFIX) }
                .let { if (it) Result.Bound else Result.NotBound }
    }
}

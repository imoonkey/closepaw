package ai.closepaw.browser.cdp.shizuku

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Shizuku UserService implementation.
 *
 * Runs inside a Shizuku-spawned host process under the shell UID and exposes a single
 * round-trip [exchange] over Chrome's `chrome_devtools_remote` abstract socket. The shell UID
 * is typically allowed to connect to that socket even when ClosePaw's app UID is blocked by
 * SELinux / package isolation.
 *
 * Security: the abstract socket name is hard-wired to [CHROME_DEVTOOLS_SOCKET]; if it were a
 * parameter the app UID could use this user service as a proxy to reach arbitrary privileged
 * sockets, which is a privilege-escalation hole.
 *
 * This class is intentionally thin: it owns no state beyond the binder lifecycle. Higher-level
 * orchestration (preflight checks, transport selection, parsing) lives in
 * [ShizukuChromeDevtoolsBridge]. Unit-testable parsing lives in [DevtoolsHttpProtocol].
 */
class ChromeDevtoolsUserService() : IChromeDevtoolsUserService.Stub() {

    /**
     * Shizuku spawns the user service process and instantiates this class via reflection. Some
     * binder hosts pass a Context-like argument; we accept and ignore it because this service
     * holds no state outside the binder lifecycle.
     */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Any?) : this()

    override fun exchange(request: ByteArray, timeoutMs: Int): ByteArray {
        require(request.isNotEmpty()) { "request must not be empty" }
        val timeout = timeoutMs.coerceAtLeast(1)
        val deadline = SystemClock.uptimeMillis() + timeout

        val socket = LocalSocket()
        return try {
            socket.connect(
                LocalSocketAddress(CHROME_DEVTOOLS_SOCKET, LocalSocketAddress.Namespace.ABSTRACT)
            )
            socket.soTimeout = timeout
            socket.outputStream.write(request)
            socket.outputStream.flush()
            socket.shutdownOutput()
            readUntilEofOrDeadline(socket, deadline)
        } finally {
            runCatching { socket.close() }
        }
    }

    override fun destroy() {
        // No persistent state. Process is torn down by Shizuku binder lifecycle.
    }

    private fun readUntilEofOrDeadline(socket: LocalSocket, deadline: Long): ByteArray {
        val sink = ByteArrayOutputStream(BUFFER)
        val buf = ByteArray(BUFFER)
        val input = socket.inputStream
        while (true) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw SocketTimeoutException("read deadline exceeded for $socket")
            }
            val n = try {
                input.read(buf)
            } catch (e: SocketTimeoutException) {
                throw IOException("read timed out after ${socket.soTimeout} ms", e)
            }
            if (n < 0) break
            if (n > 0) sink.write(buf, 0, n)
        }
        return sink.toByteArray()
    }

    companion object {
        const val CHROME_DEVTOOLS_SOCKET = ShizukuChromeDevtoolsBridge.CHROME_DEVTOOLS_SOCKET
        private const val BUFFER = 4096
    }
}

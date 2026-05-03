package ai.closepaw.browser.cdp.shizuku

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Default app-process transport: connects to Chrome's `chrome_devtools_remote` abstract socket
 * directly from the ClosePaw app UID. Will typically fail under SELinux / package isolation;
 * the bridge falls back to the Shizuku UserService transport in that case.
 *
 * Cancellation/timeout: [LocalSocket.soTimeout] gives a hard read deadline (otherwise
 * `inputStream.read` can hang forever even if Chrome dies mid-response). Coroutine
 * cancellation closes the socket via [suspendCancellableCoroutine.invokeOnCancellation],
 * which unblocks any in-flight `read` from a different thread.
 *
 * The hard-coded socket name keeps this transport in lock-step with
 * [ChromeDevtoolsUserService] — both target the same endpoint.
 */
class AppProcessLocalSocketTransport : DevtoolsSocketTransport {

    override val label: TransportLabel = TransportLabel.APP_PROCESS

    override suspend fun exchange(request: ByteArray, timeoutMs: Int): ByteArray =
        suspendCancellableCoroutine { cont ->
            require(request.isNotEmpty()) { "request must not be empty" }
            val timeout = timeoutMs.coerceAtLeast(1)
            val deadline = SystemClock.uptimeMillis() + timeout
            val socket = LocalSocket()
            cont.invokeOnCancellation { runCatching { socket.close() } }
            try {
                socket.connect(
                    LocalSocketAddress(
                        ShizukuChromeDevtoolsBridge.CHROME_DEVTOOLS_SOCKET,
                        LocalSocketAddress.Namespace.ABSTRACT,
                    )
                )
                socket.soTimeout = timeout
                socket.outputStream.write(request)
                socket.outputStream.flush()
                // Read with HTTP Content-Length awareness: Chrome's net::HttpServer ignores
                // `Connection: close` on the abstract socket and never EOFs after a single
                // response. shutdownOutput() also can't be used — it triggers a connection
                // RST on this build. See ChromeDevtoolsUserService.readHttpResponse for the
                // same parsing logic.
                val result = readHttpResponse(socket, deadline)
                if (cont.isActive) cont.resume(result)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            } finally {
                runCatching { socket.close() }
            }
        }

    private fun readHttpResponse(socket: LocalSocket, deadline: Long): ByteArray {
        val sink = ByteArrayOutputStream(BUFFER)
        val buf = ByteArray(BUFFER)
        val input = socket.inputStream
        var headerEnd = -1
        while (headerEnd < 0) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw SocketTimeoutException("read deadline exceeded for $socket")
            }
            val n = try {
                input.read(buf)
            } catch (e: SocketTimeoutException) {
                throw IOException("read timed out after ${socket.soTimeout} ms", e)
            }
            if (n < 0) return sink.toByteArray()
            if (n > 0) sink.write(buf, 0, n)
            headerEnd = ChromeDevtoolsUserService.indexOfDoubleCrlf(sink.toByteArray())
        }
        val collected = sink.toByteArray()
        val contentLength = ChromeDevtoolsUserService.parseContentLength(collected, headerEnd)
            ?: return collected
        val bodyStart = headerEnd + 4
        val needed = bodyStart + contentLength - collected.size
        if (needed <= 0) return collected
        readExactly(input, sink, needed.toInt(), deadline, socket.soTimeout)
        return sink.toByteArray()
    }

    private fun readExactly(
        input: java.io.InputStream,
        sink: ByteArrayOutputStream,
        bytes: Int,
        deadline: Long,
        soTimeout: Int,
    ) {
        var remaining = bytes
        val buf = ByteArray(BUFFER)
        while (remaining > 0) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw SocketTimeoutException("body read deadline exceeded after ${bytes - remaining}/$bytes bytes")
            }
            val n = try {
                input.read(buf, 0, minOf(remaining, buf.size))
            } catch (e: SocketTimeoutException) {
                throw IOException("body read timed out after $soTimeout ms", e)
            }
            if (n < 0) throw IOException("EOF after ${bytes - remaining}/$bytes body bytes")
            sink.write(buf, 0, n)
            remaining -= n
        }
    }

    companion object {
        private const val BUFFER = 4096
    }
}

/**
 * Adapter wrapping an [IChromeDevtoolsUserService] binder as a [DevtoolsSocketTransport].
 *
 * Binder calls are inherently synchronous and not coroutine-cancellable from the client side.
 * [runInterruptible] gives a best-effort: when the surrounding coroutine is cancelled it
 * interrupts the IO thread carrying the binder transaction, which Binder honours by aborting
 * with `InterruptedException`. The remote socket also has a `soTimeout` (set inside
 * [ChromeDevtoolsUserService.exchange]) so a wedged remote eventually unwinds even without
 * cancellation.
 */
class UserServiceTransport(
    val binder: IChromeDevtoolsUserService,
) : DevtoolsSocketTransport {

    override val label: TransportLabel = TransportLabel.USER_SERVICE

    /**
     * Lazily start the device-side TCP relay (one process == one relay) and return its
     * 127.0.0.1 port. The app then opens an OkHttp WebSocket to that port to tunnel CDP
     * traffic through the shell-UID UserService — required because Chrome's
     * `webSocketDebuggerUrl` has no port (defaults to 80, unreachable from the app UID).
     */
    suspend fun ensureRelayPortSuspend(): Int = runInterruptible(Dispatchers.IO) {
        val port = binder.startTcpRelay()
        if (port <= 0) throw IOException("UserService.startTcpRelay returned invalid port=$port")
        port
    }

    /**
     * Ask the shell-context UserService to write `/data/local/tmp/chrome-command-line` and
     * force-restart Chrome so it binds the requested TCP debug port. Used by the Phase 2
     * [ChromeRemoteDebugPortTransport] when shell cannot connectto the abstract socket.
     * Idempotent on the UserService side.
     */
    fun ensureChromeRemoteDebugPort(port: Int): Boolean = binder.ensureChromeRemoteDebugPort(port)

    override suspend fun exchange(request: ByteArray, timeoutMs: Int): ByteArray =
        runInterruptible(Dispatchers.IO) {
            val response: ByteArray? = binder.exchange(request, timeoutMs)
            response ?: throw IOException(
                "UserService exchange returned null; check logcat for the remote exception"
            )
        }
}

package ai.closepaw.browser.cdp.shizuku

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
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
                socket.shutdownOutput()
                val result = readUntilEof(socket, deadline)
                if (cont.isActive) cont.resume(result)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            } finally {
                runCatching { socket.close() }
            }
        }

    private fun readUntilEof(socket: LocalSocket, deadline: Long): ByteArray {
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
    private val binder: IChromeDevtoolsUserService,
) : DevtoolsSocketTransport {

    override val label: TransportLabel = TransportLabel.USER_SERVICE

    override suspend fun exchange(request: ByteArray, timeoutMs: Int): ByteArray =
        runInterruptible(Dispatchers.IO) {
            val response: ByteArray? = binder.exchange(request, timeoutMs)
            response ?: throw IOException(
                "UserService exchange returned null; check logcat for the remote exception"
            )
        }
}

/**
 * Debug-only transport for real-device QA when the device policy blocks even the shell
 * UserService from connecting to Chrome's abstract DevTools socket.
 *
 * Expected setup:
 *
 * - `adb forward tcp:<host-port> localabstract:chrome_devtools_remote`
 * - Host relay from `127.0.0.1:9222` to `<host-port>` when the same-port forward/reverse path is
 *   not transparent on the device.
 * - `adb reverse tcp:9222 tcp:9222`
 *
 * The app then connects to device-local 127.0.0.1:9222, which ADB reverses into Chrome's real CDP
 * endpoint through the host path.
 */
class DebugTcpDevtoolsSocketTransport(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
) : DevtoolsSocketTransport {

    override val label: TransportLabel = TransportLabel.DEBUG_TCP

    override suspend fun exchange(request: ByteArray, timeoutMs: Int): ByteArray =
        runInterruptible(Dispatchers.IO) {
            require(request.isNotEmpty()) { "request must not be empty" }
            val timeout = timeoutMs.coerceAtLeast(1)
            val connection = URL("http", host, port, requestPath(request))
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = timeout
                connection.readTimeout = timeout
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "ClosePaw-DevTools-Bridge/1.0")
                connection.setRequestProperty("Connection", "close")
                val code = connection.responseCode
                val body = responseBody(connection, code)
                httpResponse(code, connection.responseMessage, connection.contentType, body)
            } finally {
                connection.disconnect()
            }
        }

    private fun requestPath(request: ByteArray): String {
        val firstLine = request.toString(Charsets.UTF_8).substringBefore("\r\n")
        val parts = firstLine.split(' ', limit = 3)
        require(parts.size == 3 && parts[0] == "GET" && parts[1].startsWith("/")) {
            "Debug TCP fallback expected an HTTP GET request but got: $firstLine"
        }
        return parts[1]
    }

    private fun responseBody(connection: HttpURLConnection, code: Int): ByteArray {
        val stream = if (code in 200..399) connection.inputStream else connection.errorStream
        return stream?.use { it.readBytes() } ?: ByteArray(0)
    }

    private fun httpResponse(
        code: Int,
        message: String?,
        contentType: String?,
        body: ByteArray,
    ): ByteArray {
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(message ?: "").append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            if (!contentType.isNullOrBlank()) {
                append("Content-Type: ").append(contentType).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        return header.toByteArray(Charsets.UTF_8) + body
    }

    companion object {
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 9222
    }
}

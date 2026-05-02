package ai.closepaw.browser.cdp.shizuku

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers

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
            binder.exchange(request, timeoutMs)
        }
}

package ai.closepaw.browser.cdp.shizuku

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible

/**
 * Phase 2 transport for locked-down OEM devices (e.g. nubia P0110) where SELinux blocks
 * BOTH the app UID and the Shizuku-shell UserService from connectto'ing
 * `chrome_devtools_remote`, but TCP loopback between apps is not MLS-restricted.
 *
 * Strategy:
 *
 * 1. Try a direct TCP connect to `127.0.0.1:<port>`.
 * 2. On connect failure, ask the Shizuku UserService (running in shell context) to write
 *    `--remote-debugging-port=<port>` into `/data/local/tmp/chrome-command-line` and force-stop +
 *    relaunch Chrome so it binds the TCP debug server.
 * 3. Poll `127.0.0.1:<port>` until Chrome accepts a connection or the deadline elapses.
 * 4. If Chrome never binds — almost always because the user has not toggled
 *    `chrome://flags#enable-command-line-on-non-rooted-devices` — surface
 *    [DevtoolsSetupError.ChromeRemoteDebuggingFlagNotEnabled] with one-line user instructions.
 *
 * This transport intentionally does NOT serialize setup across multiple `exchange` calls; the
 * UserService side keeps its `ensureChromeRemoteDebugPort` idempotent (no rewrite, no restart
 * if the file already requests this port), so a brief flurry of concurrent calls converges
 * cleanly.
 */
class ChromeRemoteDebugPortTransport(
    private val setup: ChromeRemoteDebugSetup,
    private val port: Int = DEFAULT_PORT,
    private val readyPollIntervalMs: Long = 200,
    private val readyTimeoutMs: Long = 12_000,
) : DevtoolsSocketTransport {

    init {
        require(port in 1024..65535) { "port out of range: $port" }
    }

    override val label: TransportLabel = TransportLabel.CHROME_TCP_LOOPBACK

    @Volatile private var setupCompleted = false

    override suspend fun exchange(request: ByteArray, timeoutMs: Int): ByteArray {
        val first = tryDoExchange(request, timeoutMs)
        first.getOrNull()?.let { return it }

        val firstError = first.exceptionOrNull()
        if (firstError !is ConnectException && firstError !is SocketTimeoutException) {
            throw firstError ?: IOException("unknown failure")
        }
        if (setupCompleted) {
            // Already set up once and Chrome dropped — surface the actionable error rather
            // than retry forever.
            throw DevtoolsSetupError.ChromeRemoteDebuggingFlagNotEnabled(firstError)
        }

        val configured = runCatching { setup.ensureChromeRemoteDebugPort(port) }
            .getOrElse { throw DevtoolsSetupError.ChromeRemoteDebuggingFlagNotEnabled(it) }
        if (!configured) {
            throw DevtoolsSetupError.ChromeRemoteDebuggingFlagNotEnabled(firstError)
        }
        if (!waitForPortBound()) {
            throw DevtoolsSetupError.ChromeRemoteDebuggingFlagNotEnabled(firstError)
        }
        setupCompleted = true
        Log.i(TAG, "Chrome bound 127.0.0.1:$port; resuming exchange")
        return doExchange(request, timeoutMs)
    }

    private suspend fun waitForPortBound(): Boolean {
        val deadline = (System.nanoTime() / 1_000_000L) + readyTimeoutMs
        while ((System.nanoTime() / 1_000_000L) < deadline) {
            if (probeConnect()) return true
            delay(readyPollIntervalMs)
        }
        return false
    }

    private suspend fun probeConnect(): Boolean = runInterruptible(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), 500)
            true
        } catch (_: IOException) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun tryDoExchange(request: ByteArray, timeoutMs: Int): Result<ByteArray> =
        runCatching { doExchange(request, timeoutMs) }

    private suspend fun doExchange(request: ByteArray, timeoutMs: Int): ByteArray =
        runInterruptible(Dispatchers.IO) {
            require(request.isNotEmpty()) { "request must not be empty" }
            val timeout = timeoutMs.coerceAtLeast(1)
            val deadline = (System.nanoTime() / 1_000_000L) + timeout
            val socket = Socket()
            socket.tcpNoDelay = true
            try {
                socket.connect(InetSocketAddress("127.0.0.1", port), timeout)
                socket.soTimeout = timeout
                socket.outputStream.write(request)
                socket.outputStream.flush()
                // Same Content-Length-aware reader as Phase 1: Chrome ignores `Connection: close`
                // and never EOFs after a single response.
                readHttpResponse(socket, deadline)
            } finally {
                runCatching { socket.close() }
            }
        }

    private fun readHttpResponse(socket: Socket, deadline: Long): ByteArray {
        val sink = ByteArrayOutputStream(BUFFER)
        val buf = ByteArray(BUFFER)
        val input = socket.getInputStream()
        var headerEnd = -1
        while (headerEnd < 0) {
            if ((System.nanoTime() / 1_000_000L) > deadline) {
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
            if ((System.nanoTime() / 1_000_000L) > deadline) {
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
        const val DEFAULT_PORT = 9222
        private const val BUFFER = 4096
        private const val TAG = "ChromeTcpTransport"
    }
}

/**
 * Indirection so we can test [ChromeRemoteDebugPortTransport] without a real Shizuku binder.
 * Production wiring backs this with the UserService AIDL [IChromeDevtoolsUserService].
 */
fun interface ChromeRemoteDebugSetup {
    /** Returns true when the chrome-command-line is now configured for the requested port. */
    suspend fun ensureChromeRemoteDebugPort(port: Int): Boolean
}

class ShizukuChromeRemoteDebugSetup(
    private val userServiceProvider: UserServiceProvider,
) : ChromeRemoteDebugSetup {
    override suspend fun ensureChromeRemoteDebugPort(port: Int): Boolean =
        runInterruptible(Dispatchers.IO) {
            val transport = userServiceProvider.runBlockingObtain() as? UserServiceTransport
                ?: throw IllegalStateException(
                    "ChromeRemoteDebugPortTransport requires UserServiceTransport to write " +
                        "/data/local/tmp/chrome-command-line; got ${userServiceProvider.javaClass.simpleName}"
                )
            transport.ensureChromeRemoteDebugPort(port)
        }

    /** Bridge from the suspend `obtain` to the IO-thread call inside `runInterruptible`. */
    private fun UserServiceProvider.runBlockingObtain(): DevtoolsSocketTransport =
        kotlinx.coroutines.runBlocking { obtain() }
}

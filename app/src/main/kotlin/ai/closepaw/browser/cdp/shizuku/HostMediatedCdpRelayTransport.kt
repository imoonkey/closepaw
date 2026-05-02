package ai.closepaw.browser.cdp.shizuku

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Host-mediated CDP relay: device-side TCP loopback that ADB chains back to Chrome's abstract
 * socket through the user's PC.
 *
 * The ClosePaw process connects to `127.0.0.1:<port>` on the device. ADB's reverse-tunnel
 * listener (running inside `adbd`, the only context that can both connectto appdomain unix
 * sockets AND be reached over TCP from an untrusted_app — see
 * `projects/active/browser/cn/diag_20260502_wireless_adb_spike/`) carries the bytes back to
 * the host's `adbd`, which then forwards them into Chrome's `chrome_devtools_remote` abstract
 * socket on the same device. The chain is established by the host once via
 * `scripts/setup-cdp-relay.sh`:
 *
 * ```
 * adb forward tcp:<port> localabstract:chrome_devtools_remote   # host:<port> → device's Chrome socket
 * adb reverse tcp:<port> tcp:<port>                              # device:<port> → host:<port>
 * ```
 *
 * This is the production fallback for OEM-locked devices (e.g. nubia P0110 Android 16) where
 * Shizuku's shell-context UserService is denied by SELinux from connectto'ing the abstract
 * socket. It does require the user's PC to keep ADB attached, so it is not a fully-autonomous
 * in-device transport — call sites should advertise that requirement when surfacing setup
 * errors.
 *
 * Port discovery: probe a small range (default 9222..9230) on first use. The setup script
 * picks the lowest free host port from the same range, so the two sides converge without
 * needing an out-of-band rendezvous.
 *
 * Reachability: cheap TCP connect probe (no HTTP); the bridge only uses this transport when
 * the probe succeeds, so we never burn the LLM-visible latency budget waiting on a listener
 * that isn't there.
 */
class HostMediatedCdpRelayTransport(
    private val host: String = DEFAULT_HOST,
    private val portRange: IntRange = DEFAULT_PORT_RANGE,
    private val probeConnectTimeoutMs: Int = DEFAULT_PROBE_CONNECT_MS,
) : DevtoolsSocketTransport {

    init {
        require(!portRange.isEmpty()) { "portRange must not be empty" }
        require(portRange.first in 1..65535 && portRange.last in 1..65535) {
            "portRange out of TCP range: $portRange"
        }
    }

    override val label: TransportLabel = TransportLabel.HOST_MEDIATED_RELAY

    @Volatile private var resolvedPort: Int? = null

    /**
     * Lowest port in [portRange] that currently accepts a TCP connection, or null if none does.
     * Cached on first hit; the setup script is idempotent and pins the port for the session.
     */
    suspend fun resolvePort(): Int? {
        resolvedPort?.let { return it }
        val candidate = runInterruptible(Dispatchers.IO) { probePortRange() }
        if (candidate != null) resolvedPort = candidate
        return candidate
    }

    /** Cheap reachability probe used by the bridge to decide whether to even try this transport. */
    override suspend fun isReachable(): Boolean = resolvePort() != null

    override suspend fun exchange(request: ByteArray, timeoutMs: Int): ByteArray =
        runInterruptible(Dispatchers.IO) {
            require(request.isNotEmpty()) { "request must not be empty" }
            val port = resolvedPort ?: probePortRange()
                ?: throw HostMediatedRelayUnreachableException(portRange, host)
            resolvedPort = port
            val timeout = timeoutMs.coerceAtLeast(1)
            val deadline = (System.nanoTime() / 1_000_000L) + timeout
            val socket = Socket()
            socket.tcpNoDelay = true
            try {
                try {
                    socket.connect(InetSocketAddress(host, port), timeout)
                } catch (e: IOException) {
                    // The setup script may have been torn down between probes; clear cache so
                    // the next call re-probes the range instead of pinning a now-dead port.
                    resolvedPort = null
                    throw HostMediatedRelayUnreachableException(portRange, host, e)
                }
                socket.soTimeout = timeout
                socket.outputStream.write(request)
                socket.outputStream.flush()
                readHttpResponse(socket, deadline)
            } finally {
                runCatching { socket.close() }
            }
        }

    private fun probePortRange(): Int? {
        for (port in portRange) {
            if (probeConnect(port)) return port
        }
        return null
    }

    private fun probeConnect(port: Int): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), probeConnectTimeoutMs)
            true
        } catch (_: IOException) {
            false
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
                throw SocketTimeoutException(
                    "body read deadline exceeded after ${bytes - remaining}/$bytes bytes"
                )
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
        const val DEFAULT_HOST: String = "127.0.0.1"
        val DEFAULT_PORT_RANGE: IntRange = 9222..9230
        const val DEFAULT_PROBE_CONNECT_MS: Int = 250
        private const val BUFFER = 4096
    }
}

/**
 * Internal exception thrown by [HostMediatedCdpRelayTransport.exchange] when no port in the
 * configured range responds. The bridge translates this into
 * [DevtoolsSetupError.HostMediatedRelayUnreachable] so the user sees actionable guidance about
 * running `scripts/setup-cdp-relay.sh`.
 */
class HostMediatedRelayUnreachableException(
    val portRange: IntRange,
    val host: String,
    cause: Throwable? = null,
) : IOException(
    "No host-mediated CDP relay listener on $host:${portRange.first}-${portRange.last}",
    cause,
)

package ai.closepaw.browser.cdp.shizuku

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/**
 * Unit coverage for [HostMediatedCdpRelayTransport]. The transport is a thin TCP loopback
 * client, so we back it with a real local [ServerSocket] that mimics what `adb forward` +
 * `adb reverse` would produce on a device. This proves:
 *
 * - port-range probe finds the lowest reachable port and caches it
 * - reachability returns false when nothing is listening across the entire range
 * - exchange tunnels HTTP correctly through the relay
 * - throws [HostMediatedRelayUnreachableException] when no listener is up at exchange time
 * - constructor rejects empty / out-of-range port ranges
 */
class HostMediatedCdpRelayTransportTest {

    private val openServers = mutableListOf<FakeRelayServer>()

    @After
    fun tearDown() {
        openServers.forEach { runCatching { it.close() } }
        openServers.clear()
    }

    @Test
    fun `is_reachable returns false when no listener exists in range`() = runBlocking {
        // Bind+close a port to make sure it's free, then point the transport at that one
        // port — no listener means probe fails.
        val deadPort = oneFreePort()
        val transport = HostMediatedCdpRelayTransport(
            host = "127.0.0.1",
            portRange = deadPort..deadPort,
            probeConnectTimeoutMs = 100,
        )
        assertThat(transport.isReachable()).isFalse()
    }

    @Test
    fun `is_reachable finds and caches a reachable port in the range`() = runBlocking {
        // Stand a single fake CDP listener up; the transport's probe should find it within
        // its configured port range and cache the result so subsequent calls don't re-scan.
        val openPort = oneFreePort()
        val server = startServer(openPort)
        val transport = HostMediatedCdpRelayTransport(
            host = "127.0.0.1",
            portRange = openPort..openPort,
            probeConnectTimeoutMs = 200,
        )
        assertThat(transport.isReachable()).isTrue()
        assertThat(transport.resolvePort()).isEqualTo(openPort)

        // Stop the server — the cached port keeps `resolvePort` returning the same value
        // (we cache aggressively to avoid re-scanning the range on every CDP call).
        server.close()
        assertThat(transport.resolvePort()).isEqualTo(openPort)
    }

    @Test
    fun `exchange round-trips HTTP through the relay`() = runBlocking {
        val openPort = oneFreePort()
        val server = startServer(
            port = openPort,
            response = okResponse("""{"Browser":"Chrome/130.0.0.0"}"""),
        )
        val transport = HostMediatedCdpRelayTransport(
            host = "127.0.0.1",
            portRange = openPort..openPort,
            probeConnectTimeoutMs = 200,
        )

        val request = DevtoolsHttpProtocol.buildGet("/json/version")
        val response = withTimeout(2_000) { transport.exchange(request, timeoutMs = 1500) }

        val body = DevtoolsHttpProtocol.parseHttpBody(response)
        assertThat(body).contains("Chrome/130.0.0.0")
        assertThat(server.lastRequest).contains("GET /json/version")
    }

    @Test
    fun `exchange throws HostMediatedRelayUnreachable when no port responds`() = runBlocking {
        val deadPort = oneFreePort()
        val transport = HostMediatedCdpRelayTransport(
            host = "127.0.0.1",
            portRange = deadPort..deadPort,
            probeConnectTimeoutMs = 100,
        )

        val request = DevtoolsHttpProtocol.buildGet("/json/version")
        try {
            transport.exchange(request, timeoutMs = 500)
            error("expected HostMediatedRelayUnreachableException")
        } catch (e: HostMediatedRelayUnreachableException) {
            assertThat(e.host).isEqualTo("127.0.0.1")
            assertThat(e.portRange.first).isAtLeast(1024)
        }
    }

    @Test
    fun `exchange clears cached port and re-probes when listener disappears mid-flight`() = runBlocking {
        val openPort = oneFreePort()
        val server = startServer(
            port = openPort,
            response = okResponse("""{"Browser":"first"}"""),
        )
        val transport = HostMediatedCdpRelayTransport(
            host = "127.0.0.1",
            portRange = openPort..openPort,
            probeConnectTimeoutMs = 200,
        )

        // Warm the cache
        val first = transport.exchange(
            DevtoolsHttpProtocol.buildGet("/json/version"),
            timeoutMs = 1500,
        )
        assertThat(DevtoolsHttpProtocol.parseHttpBody(first)).contains("first")

        // Tear down the listener and ensure subsequent exchange surfaces the unreachable error.
        server.close()
        try {
            withTimeout(2_000) {
                transport.exchange(
                    DevtoolsHttpProtocol.buildGet("/json/version"),
                    timeoutMs = 500,
                )
            }
            error("expected exchange to fail after listener was closed")
        } catch (_: HostMediatedRelayUnreachableException) {
            // OK
        }
        assertThat(transport.resolvePort()).isNull()
    }

    @Test
    fun `constructor rejects empty range`() {
        try {
            HostMediatedCdpRelayTransport(portRange = IntRange(9230, 9222))
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("empty")
        }
    }

    @Test
    fun `constructor rejects out-of-tcp-range ports`() {
        try {
            HostMediatedCdpRelayTransport(portRange = 0..10)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("out of TCP range")
        }
        try {
            HostMediatedCdpRelayTransport(portRange = 65530..70000)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("out of TCP range")
        }
    }

    @Test
    fun `transport advertises HOST_MEDIATED_RELAY label`() {
        val transport = HostMediatedCdpRelayTransport(portRange = oneFreePort().let { it..it })
        assertThat(transport.label).isEqualTo(TransportLabel.HOST_MEDIATED_RELAY)
    }

    // ── Test helpers ─────────────────────────────────────────────────────────

    /**
     * Get one currently-free TCP port above 1024. There is a small race between us closing
     * the bound socket and the test consuming the port, but for the assertions below — which
     * either bind a server immediately afterwards or expect "no listener" — the window is
     * small enough that a contending process is rare.
     */
    private fun oneFreePort(): Int {
        val s = ServerSocket(0)
        val p = s.localPort
        s.close()
        return p
    }

    private fun startServer(port: Int, response: String = okResponse("{}")): FakeRelayServer {
        val server = FakeRelayServer(port, response)
        openServers.add(server)
        return server
    }

    private fun okResponse(body: String): String {
        val bytes = body.toByteArray(Charsets.UTF_8).size
        return "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: $bytes\r\n" +
            "Connection: close\r\n\r\n" +
            body
    }
}

/**
 * Minimal single-shot HTTP server bound on 127.0.0.1:[port]. Each accepted connection reads
 * the request, captures it for assertion, writes [response], and closes. Mimics the bytes
 * `adb forward` would deliver from Chrome's `chrome_devtools_remote` socket.
 */
private class FakeRelayServer(port: Int, private val response: String) : AutoCloseable {
    @Volatile var lastRequest: String = ""
        private set

    private val server = ServerSocket(port)
    private val accepter: Thread

    init {
        accepter = Thread {
            try {
                while (!server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (_: IOException) {
                        return@Thread
                    }
                    handleClient(client)
                }
            } catch (_: IOException) {
                // server closed; exit loop
            }
        }.apply {
            isDaemon = true
            name = "FakeRelayServer-$port"
            start()
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.getOutputStream().use { out ->
                client.getInputStream().use { input ->
                    // Read just enough to capture the request line (until \r\n\r\n or 4 KiB).
                    val buf = ByteArray(4096)
                    val n = try {
                        input.read(buf)
                    } catch (_: IOException) {
                        -1
                    }
                    if (n > 0) {
                        lastRequest = String(buf, 0, n, Charsets.UTF_8)
                    }
                    out.write(response.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
        } catch (_: IOException) {
            // client gone
        } finally {
            runCatching { client.close() }
        }
    }

    override fun close() {
        runCatching { server.close() }
        accepter.interrupt()
    }
}

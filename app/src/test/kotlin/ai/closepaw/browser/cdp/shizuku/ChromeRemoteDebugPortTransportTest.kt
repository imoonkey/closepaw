package ai.closepaw.browser.cdp.shizuku

import com.google.common.truth.Truth.assertThat
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * Unit coverage for [ChromeRemoteDebugPortTransport]. We back the transport with a real
 * loopback `ServerSocket` that speaks the same minimal HTTP/1.1 the bridge sends so we
 * exercise the actual Content-Length-aware reader, then drive setup/wait/timeout paths
 * through a fake [ChromeRemoteDebugSetup].
 */
class ChromeRemoteDebugPortTransportTest {

    private val fakeChromes = mutableListOf<FakeChrome>()

    @After
    fun tearDown() {
        fakeChromes.forEach { it.close() }
        fakeChromes.clear()
    }

    @Test
    fun connects_on_first_try_when_chrome_already_bound() = runBlocking {
        val chrome = startFakeChrome(VERSION_BODY).also { fakeChromes += it }
        val setup = FakeSetup(returns = false)  // would fail if invoked
        val transport = ChromeRemoteDebugPortTransport(
            setup = setup,
            port = chrome.port,
            readyTimeoutMs = 500,
        )

        val response = transport.exchange(jsonVersionRequest(), 1500).toString(Charsets.UTF_8)

        assertThat(response).contains("HTTP/1.1 200")
        assertThat(response).contains("\"Browser\"")
        assertThat(response).contains("Chrome/130")
        assertThat(setup.calls).isEqualTo(0)
    }

    @Test
    fun triggers_setup_then_succeeds_when_chrome_appears() = runBlocking {
        // Reserve a port the transport will probe — but bind it lazily so the first attempt
        // fails, then setup() "starts" the fake chrome and the transport retries.
        val deferredPort = pickFreePort()
        val setup = FakeSetup(returns = true) {
            fakeChromes += startFakeChrome(VERSION_BODY, port = deferredPort)
            true
        }
        val transport = ChromeRemoteDebugPortTransport(
            setup = setup,
            port = deferredPort,
            readyPollIntervalMs = 50,
            readyTimeoutMs = 5000,
        )

        val response = transport.exchange(jsonVersionRequest(), 2000).toString(Charsets.UTF_8)

        assertThat(response).contains("HTTP/1.1 200")
        assertThat(response).contains("\"Browser\"")
        assertThat(setup.calls).isEqualTo(1)
    }

    @Test
    fun surfaces_flag_not_enabled_when_chrome_never_binds() = runBlocking {
        val unboundPort = pickFreePort()
        val setup = FakeSetup(returns = true)  // pretends to write file, but Chrome flag is off
        val transport = ChromeRemoteDebugPortTransport(
            setup = setup,
            port = unboundPort,
            readyPollIntervalMs = 50,
            readyTimeoutMs = 250,
        )

        val err = assertFailsWith<DevtoolsSetupError.ChromeRemoteDebuggingFlagNotEnabled> {
            transport.exchange(jsonVersionRequest(), 1000)
        }
        assertThat(err.code).isEqualTo("chrome_flag_not_enabled")
        assertThat(err.message).contains("chrome://flags")
        assertThat(setup.calls).isEqualTo(1)
    }

    @Test
    fun surfaces_flag_not_enabled_when_userservice_setup_returns_false() = runBlocking {
        val unboundPort = pickFreePort()
        val setup = FakeSetup(returns = false)
        val transport = ChromeRemoteDebugPortTransport(
            setup = setup,
            port = unboundPort,
            readyPollIntervalMs = 50,
            readyTimeoutMs = 250,
        )

        val err = assertFailsWith<DevtoolsSetupError.ChromeRemoteDebuggingFlagNotEnabled> {
            transport.exchange(jsonVersionRequest(), 1000)
        }
        assertThat(err.code).isEqualTo("chrome_flag_not_enabled")
    }

    @Test
    fun second_failure_after_setup_does_not_retry_setup() = runBlocking {
        val deferredPort = pickFreePort()
        var callsBefore = 0
        val setup = FakeSetup(returns = true) {
            callsBefore += 1
            // First call: start chrome so first exchange succeeds.
            fakeChromes += startFakeChrome(VERSION_BODY, port = deferredPort)
            true
        }
        val transport = ChromeRemoteDebugPortTransport(
            setup = setup,
            port = deferredPort,
            readyPollIntervalMs = 50,
            readyTimeoutMs = 2000,
        )

        // First exchange triggers setup and succeeds.
        transport.exchange(jsonVersionRequest(), 2000)
        assertThat(callsBefore).isEqualTo(1)

        // Now kill chrome and try again — should NOT re-trigger setup; should surface flag error.
        fakeChromes.forEach { it.close() }
        fakeChromes.clear()

        val err = assertFailsWith<DevtoolsSetupError.ChromeRemoteDebuggingFlagNotEnabled> {
            transport.exchange(jsonVersionRequest(), 1000)
        }
        assertThat(err.code).isEqualTo("chrome_flag_not_enabled")
        assertThat(callsBefore).isEqualTo(1)  // setup not re-called
    }

    @Test
    fun rejects_invalid_port_at_construction() {
        try {
            ChromeRemoteDebugPortTransport(setup = FakeSetup(returns = true), port = 0)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("port out of range")
        }
        try {
            ChromeRemoteDebugPortTransport(setup = FakeSetup(returns = true), port = 80)
            error("expected IllegalArgumentException for privileged port")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("port out of range")
        }
    }

    @Test
    fun declares_correct_label() {
        val transport = ChromeRemoteDebugPortTransport(setup = FakeSetup(returns = true), port = 9222)
        assertThat(transport.label).isEqualTo(TransportLabel.CHROME_TCP_LOOPBACK)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun jsonVersionRequest(): ByteArray = (
        "GET /json/version HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n\r\n"
        ).toByteArray()

    private fun pickFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private fun startFakeChrome(body: String, port: Int = 0): FakeChrome {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", port))
        val actualPort = server.localPort
        val chrome = FakeChrome(server, actualPort)
        thread(isDaemon = true, name = "fake-chrome-$actualPort") {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (e: Exception) { return@thread }
                thread(isDaemon = true) {
                    sock.use { s ->
                        // Read until \r\n\r\n then write our canned response. We don't bother
                        // honouring Connection: close; Chrome doesn't either.
                        val input = s.getInputStream().bufferedReader()
                        val sb = StringBuilder()
                        var line = input.readLine()
                        while (line != null && line.isNotEmpty()) {
                            sb.appendLine(line)
                            line = input.readLine()
                        }
                        val out = s.getOutputStream()
                        val bytes = body.toByteArray()
                        val resp = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\n\r\n"
                        out.write(resp.toByteArray())
                        out.write(bytes)
                        out.flush()
                    }
                }
            }
        }
        return chrome
    }

    private inline fun <reified E : Throwable> assertFailsWith(block: () -> Unit): E {
        try {
            block()
        } catch (t: Throwable) {
            if (t is E) return t
            throw AssertionError(
                "expected ${E::class.java.name} but got ${t::class.java.name}: $t", t,
            )
        }
        throw AssertionError("expected ${E::class.java.name} but no exception was thrown")
    }

    companion object {
        private const val VERSION_BODY = """{
  "Browser": "Chrome/130.0.0.0",
  "Protocol-Version": "1.3",
  "User-Agent": "Mozilla/5.0",
  "V8-Version": "13.0",
  "WebKit-Version": "537.36",
  "webSocketDebuggerUrl": "ws://localhost:9222/devtools/browser/abc"
}"""
    }
}

private class FakeChrome(val server: ServerSocket, val port: Int) {
    fun close() { runCatching { server.close() } }
}

private class FakeSetup(
    private val returns: Boolean,
    private val sideEffect: (() -> Boolean)? = null,
) : ChromeRemoteDebugSetup {
    var calls = 0
        private set

    override suspend fun ensureChromeRemoteDebugPort(port: Int): Boolean {
        calls += 1
        return sideEffect?.invoke() ?: returns
    }
}

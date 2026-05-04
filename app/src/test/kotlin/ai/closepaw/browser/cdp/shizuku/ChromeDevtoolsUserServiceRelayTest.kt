package ai.closepaw.browser.cdp.shizuku

import com.google.common.truth.Truth.assertThat
import java.net.Socket
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Coverage for the token-gated TCP relay in [ChromeDevtoolsUserService]. We can't drive the
 * happy-path proxy here because the upstream is `android.net.LocalSocket(ABSTRACT)`, which is
 * unreachable from a JVM unit test — the wireless transport's stress test
 * (`WirelessAdbSelfPairTransportRelayStressTest`) covers the success path with a fake
 * upstream, and both relays share the same [RelayAuthToken] gate, so the security invariants
 * tested here apply to both.
 *
 * Coverage: matching-token-idempotent, different-token-rejected, no-token → 403, wrong-token
 * → 403, slowloris → 408 timeout. Together these prove the gate fires on every code path
 * before the upstream connect call.
 */
class ChromeDevtoolsUserServiceRelayTest {

    private lateinit var service: ChromeDevtoolsUserService

    @Before fun setUp() {
        service = ChromeDevtoolsUserService()
    }

    @After fun tearDown() {
        service.destroy()
    }

    @Test
    fun `startTcpRelay is idempotent for matching token`() {
        val first = service.startTcpRelay(TEST_TOKEN)
        assertThat(first).isGreaterThan(0)
        repeat(5) {
            assertThat(service.startTcpRelay(TEST_TOKEN)).isEqualTo(first)
        }
    }

    @Test
    fun `startTcpRelay rejects token rotation`() {
        val first = service.startTcpRelay(TEST_TOKEN)
        assertThat(first).isGreaterThan(0)
        try {
            service.startTcpRelay("different-token-from-original")
            throw AssertionError("expected IllegalStateException for token rotation")
        } catch (e: IllegalStateException) {
            assertThat(e.message).contains("different token")
        }
    }

    @Test
    fun `startTcpRelay requires non-empty token`() {
        try {
            service.startTcpRelay("")
            throw AssertionError("expected IllegalArgumentException for empty token")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("authToken")
        }
    }

    @Test
    fun `relay rejects connection without token with 403`() {
        val port = service.startTcpRelay(TEST_TOKEN)
        Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 5_000
            sock.getOutputStream().write(
                "GET /devtools/page/A HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray()
            )
            sock.getOutputStream().flush()
            val response = sock.getInputStream().readBytes().toString(Charsets.US_ASCII)
            assertThat(response).startsWith("HTTP/1.1 403")
        }
    }

    @Test
    fun `relay rejects connection with wrong token with 403`() {
        val port = service.startTcpRelay(TEST_TOKEN)
        Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 5_000
            sock.getOutputStream().write(
                ("GET /devtools/page/A HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                    "X-ClosePaw-Token: not-the-real-token\r\n\r\n").toByteArray()
            )
            sock.getOutputStream().flush()
            val response = sock.getInputStream().readBytes().toString(Charsets.US_ASCII)
            assertThat(response).startsWith("HTTP/1.1 403")
        }
    }

    @Test
    fun `relay times out slowloris client (no bytes sent) with 408`() {
        val port = service.startTcpRelay(TEST_TOKEN)
        // Connect and never send a request body. The pre-auth soTimeout (5s) must fire and
        // the relay must respond with 408 + close, not park the thread/fd indefinitely.
        Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 10_000  // generous so we get the relay's 408, not our own timeout
            val start = System.currentTimeMillis()
            val response = sock.getInputStream().readBytes().toString(Charsets.US_ASCII)
            val elapsed = System.currentTimeMillis() - start
            assertThat(response).startsWith("HTTP/1.1 408")
            // Must fire within ~PRE_AUTH_SO_TIMEOUT_MS (5s) plus a small jitter; never the full
            // 10s client-side cap.
            assertThat(elapsed).isLessThan(8_000L)
        }
    }

    private companion object {
        const val TEST_TOKEN = "user-service-test-token-deadbeefcafe1234567890"
    }
}

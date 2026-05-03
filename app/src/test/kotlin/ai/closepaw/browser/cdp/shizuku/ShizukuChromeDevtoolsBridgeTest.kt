package ai.closepaw.browser.cdp.shizuku

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Coverage for [ShizukuChromeDevtoolsBridge].
 *
 * Each `setup_error_*` test exercises one of the failure modes the bridge must surface
 * distinctly. The happy-path tests prove that endpoint selection (`/json/version` vs
 * `/json/list`) actually drives the request — the FakeTransport routes by the request bytes
 * and asserts the path each call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShizukuChromeDevtoolsBridgeTest {

    private fun bridge(
        status: ShizukuStatusProvider = FakeStatus(available = true, permitted = true),
        diagnostics: DevtoolsDiagnostics = FakeDiagnostics(
            socket = SocketProbeResult.Bound,
            chrome = ChromeRunningResult.Running,
        ),
        userServiceProvider: UserServiceProvider = FakeUserServiceProvider(
            transport = FakeTransport(TransportLabel.USER_SERVICE, responses = defaultResponses()),
        ),
        wirelessAdbSelfPairTransport: DevtoolsSocketTransport? = null,
    ): ShizukuChromeDevtoolsBridge = ShizukuChromeDevtoolsBridge(
        status = status,
        diagnostics = diagnostics,
        userServiceProvider = userServiceProvider,
        wirelessAdbSelfPairTransport = wirelessAdbSelfPairTransport,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    // ── Setup errors ─────────────────────────────────────────────────────────

    @Test
    fun setup_error_shizuku_unavailable() = runTest {
        val b = bridge(status = FakeStatus(available = false, permitted = true))
        val err = assertFailsWith<DevtoolsSetupError> { b.fetchVersion() }
        assertThat(err).isSameInstanceAs(DevtoolsSetupError.ShizukuUnavailable)
        assertThat(err.code).isEqualTo("shizuku_unavailable")
    }

    @Test
    fun setup_error_shizuku_permission_missing() = runTest {
        val b = bridge(status = FakeStatus(available = true, permitted = false))
        val err = assertFailsWith<DevtoolsSetupError> { b.fetchVersion() }
        assertThat(err).isSameInstanceAs(DevtoolsSetupError.ShizukuPermissionMissing)
        assertThat(err.code).isEqualTo("shizuku_permission_missing")
    }

    @Test
    fun setup_error_chrome_not_running() = runTest {
        val b = bridge(
            diagnostics = FakeDiagnostics(
                socket = SocketProbeResult.NotBound,
                chrome = ChromeRunningResult.NotRunning,
            ),
        )
        val err = assertFailsWith<DevtoolsSetupError> { b.fetchVersion() }
        assertThat(err).isSameInstanceAs(DevtoolsSetupError.ChromeNotRunning)
        assertThat(err.code).isEqualTo("chrome_not_running")
    }

    @Test
    fun setup_error_devtools_socket_missing() = runTest {
        val b = bridge(
            diagnostics = FakeDiagnostics(
                socket = SocketProbeResult.NotBound,
                chrome = ChromeRunningResult.Running,
            ),
        )
        val err = assertFailsWith<DevtoolsSetupError> { b.fetchVersion() }
        assertThat(err).isSameInstanceAs(DevtoolsSetupError.DevtoolsSocketMissing)
        assertThat(err.code).isEqualTo("devtools_socket_missing")
    }

    @Test
    fun setup_error_user_service_socket_inaccessible_when_user_transport_fails_and_no_fallback() = runTest {
        val userCause = IOException("user service binder cannot reach socket")
        val provider = FakeUserServiceProvider(
            transport = FakeTransport(TransportLabel.USER_SERVICE, error = userCause),
        )
        val b = bridge(userServiceProvider = provider)
        val err = assertFailsWith<DevtoolsSetupError.UserServiceSocketInaccessible> {
            b.fetchVersion()
        }
        assertThat(err.code).isEqualTo("user_service_socket_inaccessible")
        assertThat(err.cause).isSameInstanceAs(userCause)
        assertThat(provider.obtainCalls).isEqualTo(1)
    }

    @Test
    fun setup_error_user_service_socket_inaccessible_when_provider_obtain_throws() = runTest {
        val bindError = RuntimeException("Shizuku.bindUserService rejected")
        val b = bridge(userServiceProvider = FakeUserServiceProvider(error = bindError))
        val err = assertFailsWith<DevtoolsSetupError.UserServiceSocketInaccessible> {
            b.fetchVersion()
        }
        assertThat(err.cause).isSameInstanceAs(bindError)
    }

    @Test
    fun setup_error_malformed_response_when_http_garbage() = runTest {
        val b = bridge(
            userServiceProvider = FakeUserServiceProvider(
                transport = FakeTransport(
                    TransportLabel.USER_SERVICE,
                    responses = mapOf("/json/version" to "not http\n".toByteArray()),
                ),
            ),
        )
        val err = assertFailsWith<DevtoolsSetupError.MalformedResponse> { b.fetchVersion() }
        assertThat(err.code).isEqualTo("malformed_response")
        assertThat(err.message).contains("CRLF")
    }

    @Test
    fun setup_error_malformed_response_when_websocket_handshake_lacks_upgrade() = runTest {
        val handshakeReject = (
            "HTTP/1.1 400 Bad Request\r\n" +
                "Content-Length: 0\r\n\r\n"
            ).toByteArray()
        val b = bridge(
            userServiceProvider = FakeUserServiceProvider(
                transport = FakeTransport(
                    TransportLabel.USER_SERVICE,
                    responses = mapOf("/json/version" to handshakeReject),
                ),
            ),
        )
        val err = assertFailsWith<DevtoolsSetupError.MalformedResponse> { b.fetchVersion() }
        assertThat(err.message).contains("HTTP 400")
    }

    // ── Happy paths and fallback ────────────────────────────────────────────

    @Test
    fun fetch_version_returns_parsed_payload_via_user_service_transport() = runTest {
        val transport = FakeTransport(TransportLabel.USER_SERVICE, responses = defaultResponses())
        val b = bridge(userServiceProvider = FakeUserServiceProvider(transport = transport))

        val v = b.fetchVersion()

        assertThat(v.browser).isEqualTo("Chrome/130.0.0.0")
        assertThat(v.protocolVersion).isEqualTo("1.3")
        assertThat(v.webSocketDebuggerUrl).isEqualTo("ws://localhost/devtools/browser/abc")
        assertThat(transport.lastPath).isEqualTo("/json/version")
    }

    @Test
    fun list_page_targets_returns_parsed_array() = runTest {
        val transport = FakeTransport(TransportLabel.USER_SERVICE, responses = defaultResponses())
        val b = bridge(userServiceProvider = FakeUserServiceProvider(transport = transport))

        val targets = b.listPageTargets()

        assertThat(targets).hasSize(2)
        assertThat(targets[0].id).isEqualTo("AAA")
        assertThat(targets[0].type).isEqualTo("page")
        assertThat(targets[1].url).isEqualTo("https://example.com/")
        assertThat(transport.lastPath).isEqualTo("/json/list")
    }

    @Test
    fun bridge_routes_each_method_to_its_distinct_endpoint() = runTest {
        val transport = FakeTransport(TransportLabel.USER_SERVICE, responses = defaultResponses())
        val b = bridge(userServiceProvider = FakeUserServiceProvider(transport = transport))

        b.fetchVersion()
        assertThat(transport.lastPath).isEqualTo("/json/version")

        b.listPageTargets()
        assertThat(transport.lastPath).isEqualTo("/json/list")
        assertThat(transport.calls).isEqualTo(2)
    }

    @Test
    fun bridge_falls_back_to_wireless_when_user_service_cannot_reach_socket() = runTest {
        val user = FakeTransport(
            TransportLabel.USER_SERVICE,
            error = IOException("Permission denied"),
        )
        val wireless = FakeTransport(
            TransportLabel.WIRELESS_ADB_SELF_PAIR,
            responses = defaultResponses(),
        )
        val provider = FakeUserServiceProvider(transport = user)
        val b = bridge(
            userServiceProvider = provider,
            wirelessAdbSelfPairTransport = wireless,
        )

        val v = b.fetchVersion()

        assertThat(v.browser).isEqualTo("Chrome/130.0.0.0")
        assertThat(user.calls).isEqualTo(1)
        assertThat(wireless.calls).isEqualTo(1)
        assertThat(provider.obtainCalls).isEqualTo(1)
    }

    @Test
    fun bridge_falls_back_to_wireless_when_user_service_returns_empty_response() = runTest {
        val user = FakeTransport(
            TransportLabel.USER_SERVICE,
            responses = mapOf("/json/version" to ByteArray(0)),
        )
        val wireless = FakeTransport(
            TransportLabel.WIRELESS_ADB_SELF_PAIR,
            responses = defaultResponses(),
        )
        val provider = FakeUserServiceProvider(transport = user)
        val b = bridge(
            userServiceProvider = provider,
            wirelessAdbSelfPairTransport = wireless,
        )

        val v = b.fetchVersion()

        assertThat(v.browser).isEqualTo("Chrome/130.0.0.0")
        assertThat(user.calls).isEqualTo(1)
        assertThat(wireless.calls).isEqualTo(1)
    }

    @Test
    fun bridge_skips_wireless_when_user_service_succeeds() = runTest {
        val user = FakeTransport(TransportLabel.USER_SERVICE, responses = defaultResponses())
        val wireless = FakeTransport(
            TransportLabel.WIRELESS_ADB_SELF_PAIR,
            responses = defaultResponses(),
        )
        val provider = FakeUserServiceProvider(transport = user)
        val b = bridge(
            userServiceProvider = provider,
            wirelessAdbSelfPairTransport = wireless,
        )

        b.fetchVersion()

        assertThat(user.calls).isEqualTo(1)
        assertThat(wireless.calls).isEqualTo(0)
    }

    @Test
    fun bridge_surfaces_wireless_unavailable_when_both_paths_fail() = runTest {
        val user = FakeTransport(
            TransportLabel.USER_SERVICE,
            error = IOException("Permission denied"),
        )
        val wireless = FakeTransport(
            TransportLabel.WIRELESS_ADB_SELF_PAIR,
            error = IOException("pair handshake failed"),
        )
        val b = bridge(
            userServiceProvider = FakeUserServiceProvider(transport = user),
            wirelessAdbSelfPairTransport = wireless,
        )

        val err = assertFailsWith<DevtoolsSetupError.WirelessAdbSelfPairUnavailable> {
            b.fetchVersion()
        }
        assertThat(err.code).isEqualTo("wireless_adb_self_pair_unavailable")
    }

    @Test
    fun bridge_unknown_socket_probe_proceeds_to_transport() = runTest {
        val b = bridge(
            diagnostics = FakeDiagnostics(SocketProbeResult.Unknown, ChromeRunningResult.Unknown),
            userServiceProvider = FakeUserServiceProvider(
                transport = FakeTransport(
                    TransportLabel.USER_SERVICE,
                    responses = defaultResponses(),
                ),
            ),
        )
        // Should NOT throw — Unknown probes mean defer to transport.
        b.fetchVersion()
    }

    @Test
    fun bridge_notbound_unknown_chrome_probe_defers_to_transport() = runTest {
        // When socket is NotBound but Chrome state is Unknown, we must NOT lie that Chrome is
        // not running. Defer to the transport so the actual failure mode surfaces with real
        // diagnostics.
        val user = FakeTransport(TransportLabel.USER_SERVICE, error = IOException("ECONNREFUSED"))
        val b = bridge(
            diagnostics = FakeDiagnostics(SocketProbeResult.NotBound, ChromeRunningResult.Unknown),
            userServiceProvider = FakeUserServiceProvider(transport = user),
        )
        val err = assertFailsWith<DevtoolsSetupError.UserServiceSocketInaccessible> {
            b.fetchVersion()
        }
        assertThat(err.cause).isInstanceOf(IOException::class.java)
        assertThat(user.calls).isEqualTo(1)
    }

    @Test
    fun close_releases_user_service_provider() = runTest {
        val provider = FakeUserServiceProvider(transport = FakeTransport(TransportLabel.USER_SERVICE))
        val b = bridge(userServiceProvider = provider)
        b.close()
        assertThat(provider.closed).isTrue()
    }

    @Test
    fun constructor_rejects_misclassified_wireless_transport() {
        try {
            ShizukuChromeDevtoolsBridge(
                status = FakeStatus(true, true),
                diagnostics = FakeDiagnostics(SocketProbeResult.Bound, ChromeRunningResult.Running),
                userServiceProvider = FakeUserServiceProvider(
                    transport = FakeTransport(TransportLabel.USER_SERVICE),
                ),
                wirelessAdbSelfPairTransport = FakeTransport(TransportLabel.USER_SERVICE),
            )
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("WIRELESS_ADB_SELF_PAIR")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun defaultResponses(): Map<String, ByteArray> = mapOf(
        ShizukuChromeDevtoolsBridge.JSON_VERSION_PATH to okResponse(versionJson()),
        ShizukuChromeDevtoolsBridge.JSON_LIST_PATH to okResponse(targetsJson()),
    )

    private fun okResponse(body: String): ByteArray {
        val bytes = body.toByteArray()
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        return header.toByteArray() + bytes
    }

    private fun versionJson(): String = """
        {
          "Browser": "Chrome/130.0.0.0",
          "Protocol-Version": "1.3",
          "User-Agent": "Mozilla/5.0",
          "V8-Version": "13.0",
          "WebKit-Version": "537.36",
          "webSocketDebuggerUrl": "ws://localhost/devtools/browser/abc"
        }
    """.trimIndent()

    private fun targetsJson(): String = """
        [
          {"id":"AAA","type":"page","title":"NewTab","url":"chrome://newtab/",
           "webSocketDebuggerUrl":"ws://localhost/devtools/page/AAA"},
          {"id":"BBB","type":"page","title":"Example","url":"https://example.com/",
           "webSocketDebuggerUrl":"ws://localhost/devtools/page/BBB"}
        ]
    """.trimIndent()

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
}

private class FakeStatus(val available: Boolean, val permitted: Boolean) : ShizukuStatusProvider {
    override fun isAvailable(): Boolean = available
    override fun hasPermission(): Boolean = permitted
}

private class FakeDiagnostics(
    val socket: SocketProbeResult,
    val chrome: ChromeRunningResult,
) : DevtoolsDiagnostics {
    override fun isDevtoolsSocketBound(): SocketProbeResult = socket
    override fun isChromeRunning(): ChromeRunningResult = chrome
}

/**
 * Fake transport that routes responses by the GET path embedded in the request bytes. This
 * proves the bridge actually issues the right HTTP path for each method, instead of accepting
 * a single canned blob that could mask an endpoint-selection bug.
 */
private class FakeTransport(
    override val label: TransportLabel,
    private val responses: Map<String, ByteArray> = emptyMap(),
    private val error: Throwable? = null,
) : DevtoolsSocketTransport {

    var calls = 0
        private set
    var lastPath: String? = null
        private set

    override suspend fun exchange(request: ByteArray, timeoutMs: Int): ByteArray {
        calls++
        error?.let { throw it }
        val firstLine = request.toString(Charsets.UTF_8).substringBefore("\r\n")
        val parts = firstLine.split(' ')
        require(parts.size >= 2 && parts[0] == "GET") {
            "FakeTransport($label) expected GET request but got: $firstLine"
        }
        val path = parts[1]
        lastPath = path
        return responses[path]
            ?: error("FakeTransport($label) has no canned response for path: $path")
    }
}

private class FakeUserServiceProvider(
    private val transport: DevtoolsSocketTransport? = null,
    private val error: Throwable? = null,
) : UserServiceProvider {

    var obtainCalls = 0
        private set
    var closed = false
        private set

    override suspend fun obtain(): DevtoolsSocketTransport {
        obtainCalls++
        error?.let { throw it }
        return transport ?: error("FakeUserServiceProvider misconfigured: no transport, no error")
    }

    override fun close() {
        closed = true
    }
}

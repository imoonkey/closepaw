package ai.closepaw.browser.cdp.shizuku

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure-parsing tests for [DevtoolsHttpProtocol] and [DefaultDevtoolsDiagnostics] helpers. */
class DevtoolsHttpProtocolTest {

    @Test
    fun build_get_emits_minimal_http_1_1_request() {
        val req = DevtoolsHttpProtocol.buildGet("/json/version").toString(Charsets.UTF_8)
        assertThat(req).startsWith("GET /json/version HTTP/1.1\r\n")
        assertThat(req).contains("Host: localhost\r\n")
        assertThat(req).contains("Connection: close\r\n")
        assertThat(req).endsWith("\r\n\r\n")
    }

    @Test
    fun build_get_rejects_paths_without_leading_slash() {
        try {
            DevtoolsHttpProtocol.buildGet("json/version")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("path must begin with /")
        }
    }

    @Test
    fun parse_http_body_extracts_body_after_double_crlf() {
        val raw = (
            "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: 4\r\n\r\n" +
            "{\"x\":1}"
            ).toByteArray()
        val body = DevtoolsHttpProtocol.parseHttpBody(raw)
        assertThat(body).isEqualTo("{\"x\":1}")
    }

    @Test
    fun parse_http_body_rejects_non_200_status() {
        val raw = "HTTP/1.1 404 Not Found\r\n\r\n".toByteArray()
        assertMalformed(raw, contains = "HTTP 404")
    }

    @Test
    fun parse_http_body_rejects_when_no_header_terminator() {
        val raw = "HTTP/1.1 200 OK\r\nNo terminator here".toByteArray()
        assertMalformed(raw, contains = "CRLF CRLF")
    }

    @Test
    fun parse_http_body_rejects_invalid_status_line() {
        val raw = "GARBAGE LINE\r\n\r\n".toByteArray()
        assertMalformed(raw, contains = "invalid status line")
    }

    @Test
    fun parse_version_extracts_canonical_fields() {
        val v = DevtoolsHttpProtocol.parseVersion(
            """{"Browser":"Chrome/130","Protocol-Version":"1.3","webSocketDebuggerUrl":"ws://x/y"}"""
        )
        assertThat(v.browser).isEqualTo("Chrome/130")
        assertThat(v.protocolVersion).isEqualTo("1.3")
        assertThat(v.webSocketDebuggerUrl).isEqualTo("ws://x/y")
    }

    @Test
    fun parse_version_throws_malformed_when_browser_field_missing() {
        try {
            DevtoolsHttpProtocol.parseVersion("""{"Protocol-Version":"1.3"}""")
            throw AssertionError("expected MalformedResponse")
        } catch (e: DevtoolsSetupError.MalformedResponse) {
            assertThat(e.message).contains("missing Browser")
        }
    }

    @Test
    fun parse_version_throws_malformed_when_payload_is_array() {
        try {
            DevtoolsHttpProtocol.parseVersion("[]")
            throw AssertionError("expected MalformedResponse")
        } catch (e: DevtoolsSetupError.MalformedResponse) {
            assertThat(e.message).contains("expected JSON object")
        }
    }

    @Test
    fun parse_page_targets_returns_empty_for_empty_array() {
        assertThat(DevtoolsHttpProtocol.parsePageTargets("[]")).isEmpty()
    }

    @Test
    fun parse_page_targets_throws_malformed_when_payload_is_object() {
        try {
            DevtoolsHttpProtocol.parsePageTargets("""{"x":1}""")
            throw AssertionError("expected MalformedResponse")
        } catch (e: DevtoolsSetupError.MalformedResponse) {
            assertThat(e.message).contains("expected JSON array")
        }
    }

    @Test
    fun parse_page_targets_drops_page_targets_missing_websocket_debugger_url() {
        // Review MEDIUM #6: pages without a webSocketDebuggerUrl are unattachable; surfacing
        // them would let callers try to attach to them and fail at the WS layer instead.
        val json = """[
            {"id":"X","type":"page","title":"NewTab","url":"chrome://newtab/"},
            {"id":"Y","type":"page","title":"Real","url":"https://example.com/",
             "webSocketDebuggerUrl":"ws://x/Y"}
        ]"""
        val targets = DevtoolsHttpProtocol.parsePageTargets(json)
        assertThat(targets).hasSize(1)
        assertThat(targets[0].id).isEqualTo("Y")
    }

    @Test
    fun parse_page_targets_drops_page_targets_with_blank_websocket_debugger_url() {
        val json = """[
            {"id":"X","type":"page","title":"Foo","url":"https://x/","webSocketDebuggerUrl":""},
            {"id":"Y","type":"page","title":"Real","url":"https://example.com/",
             "webSocketDebuggerUrl":"ws://x/Y"}
        ]"""
        val targets = DevtoolsHttpProtocol.parsePageTargets(json)
        assertThat(targets.map { it.id }).containsExactly("Y")
    }

    @Test
    fun parse_page_targets_keeps_non_page_targets_without_websocket_debugger_url() {
        val json = """[
            {"id":"S","type":"service_worker","title":"sw","url":"chrome-extension://x/sw.js"}
        ]"""
        val targets = DevtoolsHttpProtocol.parsePageTargets(json)
        assertThat(targets).hasSize(1)
        assertThat(targets[0].type).isEqualTo("service_worker")
        assertThat(targets[0].webSocketDebuggerUrl).isNull()
    }

    @Test
    fun parse_page_targets_throws_when_target_missing_required_fields() {
        val cases = listOf(
            """[{"type":"page","url":"x","webSocketDebuggerUrl":"ws://x/Y"}]""" to "missing id",
            """[{"id":"X","url":"x","webSocketDebuggerUrl":"ws://x/Y"}]""" to "missing type",
            """[{"id":"X","type":"page","webSocketDebuggerUrl":"ws://x/Y"}]""" to "missing url",
        )
        for ((body, contains) in cases) {
            try {
                DevtoolsHttpProtocol.parsePageTargets(body)
                throw AssertionError("expected MalformedResponse for: $body")
            } catch (e: DevtoolsSetupError.MalformedResponse) {
                assertThat(e.message).contains(contains)
            }
        }
    }

    @Test
    fun proc_net_unix_detects_chrome_devtools_remote_with_pid_suffix() {
        val sample = """
            Num       RefCount Protocol Flags    Type St Inode Path
            0000: 00000003 00000000 00000000 0001 03  1234 @some_other
            0000: 00000003 00000000 00000000 0001 03  5678 @chrome_devtools_remote_4321
        """.trimIndent()
        val found = DefaultDevtoolsDiagnostics.containsAbstractSocket(
            sample,
            "chrome_devtools_remote",
        )
        assertThat(found).isTrue()
    }

    @Test
    fun proc_net_unix_detects_exact_match() {
        val sample = "0000: 00 00 00 0001 03 5678 @chrome_devtools_remote"
        val found = DefaultDevtoolsDiagnostics.containsAbstractSocket(
            sample,
            "chrome_devtools_remote",
        )
        assertThat(found).isTrue()
    }

    @Test
    fun proc_net_unix_misses_unrelated_sockets() {
        val sample = """
            0000: 00 00 00 0001 03 1 @android.app.cts.somesocket
            0000: 00 00 00 0001 03 2 @com.example.other
        """.trimIndent()
        val found = DefaultDevtoolsDiagnostics.containsAbstractSocket(
            sample,
            "chrome_devtools_remote",
        )
        assertThat(found).isFalse()
    }

    private fun assertMalformed(raw: ByteArray, contains: String) {
        try {
            DevtoolsHttpProtocol.parseHttpBody(raw)
            throw AssertionError("expected MalformedResponse for raw=${raw.toString(Charsets.UTF_8)}")
        } catch (e: DevtoolsSetupError.MalformedResponse) {
            assertThat(e.message).contains(contains)
        }
    }
}

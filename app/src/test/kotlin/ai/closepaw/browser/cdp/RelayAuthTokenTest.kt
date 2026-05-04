package ai.closepaw.browser.cdp

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Test

class RelayAuthTokenTest {

    @Test
    fun `generate returns 64-char hex (32 bytes)`() {
        val t = RelayAuthToken.generate()
        assertThat(t).hasLength(64)
        assertThat(t.matches(Regex("[0-9a-f]+"))).isTrue()
    }

    @Test
    fun `generate returns distinct tokens`() {
        val tokens = (1..50).map { RelayAuthToken.generate() }.toSet()
        assertThat(tokens).hasSize(50)
    }

    @Test
    fun `verify accepts exact match`() {
        val t = "deadbeef"
        assertThat(RelayAuthToken.verify(t, t)).isTrue()
    }

    @Test
    fun `verify rejects mismatch even of same length`() {
        assertThat(RelayAuthToken.verify("deadbeef", "cafebabe")).isFalse()
    }

    @Test
    fun `verify rejects null and empty actual`() {
        assertThat(RelayAuthToken.verify("deadbeef", null)).isFalse()
        assertThat(RelayAuthToken.verify("deadbeef", "")).isFalse()
    }

    @Test
    fun `verify rejects empty expected`() {
        assertThat(RelayAuthToken.verify("", "anything")).isFalse()
    }

    @Test
    fun `verify rejects different lengths`() {
        assertThat(RelayAuthToken.verify("short", "longerstring")).isFalse()
    }

    @Test
    fun `readHttpRequestHead parses header value case-insensitively`() {
        val req = "GET /devtools/page/AAA HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "x-closepaw-token: tok-123\r\n\r\n"
        val result = RelayAuthToken.readHttpRequestHead(ByteArrayInputStream(req.toByteArray()))
        assertThat(result).isInstanceOf(RelayAuthToken.ParseResult.Success::class.java)
        val s = result as RelayAuthToken.ParseResult.Success
        assertThat(s.token).isEqualTo("tok-123")
        assertThat(s.bytes.toString(Charsets.US_ASCII)).isEqualTo(req)
    }

    @Test
    fun `readHttpRequestHead returns null token when header absent`() {
        val req = "GET / HTTP/1.1\r\nHost: x\r\n\r\n"
        val result = RelayAuthToken.readHttpRequestHead(ByteArrayInputStream(req.toByteArray()))
        assertThat(result).isInstanceOf(RelayAuthToken.ParseResult.Success::class.java)
        assertThat((result as RelayAuthToken.ParseResult.Success).token).isNull()
    }

    @Test
    fun `readHttpRequestHead fails on EOF before end of headers`() {
        val req = "GET / HTTP/1.1\r\nHost: x"
        val result = RelayAuthToken.readHttpRequestHead(ByteArrayInputStream(req.toByteArray()))
        assertThat(result).isInstanceOf(RelayAuthToken.ParseResult.Failure::class.java)
    }

    @Test
    fun `readHttpRequestHead fails when limit exceeded without end`() {
        // 4096 bytes of header without the terminator.
        val sb = StringBuilder("GET / HTTP/1.1\r\n")
        while (sb.length < 5000) sb.append("X-Pad: ").append("a".repeat(40)).append("\r\n")
        val result = RelayAuthToken.readHttpRequestHead(ByteArrayInputStream(sb.toString().toByteArray()))
        assertThat(result).isInstanceOf(RelayAuthToken.ParseResult.Failure::class.java)
    }

    @Test
    fun `write403 emits a Forbidden response`() {
        val sink = ByteArrayOutputStream()
        RelayAuthToken.write403(sink)
        val out = sink.toString(Charsets.US_ASCII.name())
        assertThat(out).startsWith("HTTP/1.1 403 Forbidden")
        assertThat(out).contains("Content-Length: 0")
        assertThat(out).contains("Connection: close")
    }

    @Test
    fun `write408 emits a Request Timeout response`() {
        val sink = ByteArrayOutputStream()
        RelayAuthToken.write408(sink)
        val out = sink.toString(Charsets.US_ASCII.name())
        assertThat(out).startsWith("HTTP/1.1 408 Request Timeout")
        assertThat(out).contains("Content-Length: 0")
        assertThat(out).contains("Connection: close")
    }

    @Test
    fun `readHttpRequestHead propagates SocketTimeoutException`() {
        val timing = object : java.io.InputStream() {
            override fun read(): Int = throw java.net.SocketTimeoutException("simulated")
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                throw java.net.SocketTimeoutException("simulated")
        }
        try {
            RelayAuthToken.readHttpRequestHead(timing)
            throw AssertionError("expected SocketTimeoutException to propagate")
        } catch (e: java.net.SocketTimeoutException) {
            assertThat(e.message).contains("simulated")
        }
    }
}

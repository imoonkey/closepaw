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
        val result = RelayAuthToken.readHttpRequestHead(
            ByteArrayInputStream(req.toByteArray()),
            totalDeadlineMs = 1_000,
        )
        assertThat(result).isInstanceOf(RelayAuthToken.ParseResult.Success::class.java)
        val s = result as RelayAuthToken.ParseResult.Success
        assertThat(s.token).isEqualTo("tok-123")
        assertThat(s.bytes.toString(Charsets.US_ASCII)).isEqualTo(req)
    }

    @Test
    fun `readHttpRequestHead returns null token when header absent`() {
        val req = "GET / HTTP/1.1\r\nHost: x\r\n\r\n"
        val result = RelayAuthToken.readHttpRequestHead(
            ByteArrayInputStream(req.toByteArray()),
            totalDeadlineMs = 1_000,
        )
        assertThat(result).isInstanceOf(RelayAuthToken.ParseResult.Success::class.java)
        assertThat((result as RelayAuthToken.ParseResult.Success).token).isNull()
    }

    @Test
    fun `readHttpRequestHead fails on EOF before end of headers`() {
        val req = "GET / HTTP/1.1\r\nHost: x"
        val result = RelayAuthToken.readHttpRequestHead(
            ByteArrayInputStream(req.toByteArray()),
            totalDeadlineMs = 1_000,
        )
        assertThat(result).isInstanceOf(RelayAuthToken.ParseResult.Failure::class.java)
    }

    @Test
    fun `readHttpRequestHead fails when limit exceeded without end`() {
        // 4096 bytes of header without the terminator.
        val sb = StringBuilder("GET / HTTP/1.1\r\n")
        while (sb.length < 5000) sb.append("X-Pad: ").append("a".repeat(40)).append("\r\n")
        val result = RelayAuthToken.readHttpRequestHead(
            ByteArrayInputStream(sb.toString().toByteArray()),
            totalDeadlineMs = 1_000,
        )
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
    fun `readHttpRequestHead propagates SocketTimeoutException from underlying read`() {
        val timing = object : java.io.InputStream() {
            override fun read(): Int = throw java.net.SocketTimeoutException("simulated")
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                throw java.net.SocketTimeoutException("simulated")
        }
        try {
            RelayAuthToken.readHttpRequestHead(timing, totalDeadlineMs = 1_000)
            throw AssertionError("expected SocketTimeoutException to propagate")
        } catch (e: java.net.SocketTimeoutException) {
            assertThat(e.message).contains("simulated")
        }
    }

    @Test
    fun `readHttpRequestHead enforces total deadline against byte-dribble`() {
        // Adversary: drips one byte at a time, each well under the per-read soTimeout. Without a
        // TOTAL deadline the helper would run for HEADER_BUFFER_LIMIT iterations × per-byte
        // delay (~minutes for a small dribble). With the deadline, total wall time bounded by
        // totalDeadlineMs + last-read budget regardless of payload size.
        val totalMs = 600
        val perByteDelayMs = 80L
        val dribble = object : java.io.InputStream() {
            override fun read(): Int {
                Thread.sleep(perByteDelayMs)
                return 'a'.code
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Thread.sleep(perByteDelayMs)
                b[off] = 'a'.code.toByte()
                return 1
            }
        }
        val timeoutsRequested = mutableListOf<Int>()
        val start = System.currentTimeMillis()
        try {
            RelayAuthToken.readHttpRequestHead(
                input = dribble,
                totalDeadlineMs = totalMs,
                setReadTimeout = { timeoutsRequested += it },
            )
            throw AssertionError("expected SocketTimeoutException for dribble exceeding deadline")
        } catch (e: java.net.SocketTimeoutException) {
            val elapsed = System.currentTimeMillis() - start
            assertThat(e.message).contains("deadline")
            // Must terminate within the budget plus a small slop — never the full
            // HEADER_BUFFER_LIMIT × perByteDelay product (~327 s).
            assertThat(elapsed).isLessThan((totalMs + 300).toLong())
            // Per-read timeouts must walk DOWN as the budget elapses (proves the deadline is
            // enforced via the setter, not just by the underlying socket's per-read cap).
            assertThat(timeoutsRequested.size).isAtLeast(2)
            assertThat(timeoutsRequested.first()).isAtMost(totalMs)
            assertThat(timeoutsRequested.last()).isLessThan(timeoutsRequested.first())
        }
    }

    @Test
    fun `readHttpRequestHead rejects non-positive deadline`() {
        try {
            RelayAuthToken.readHttpRequestHead(
                ByteArrayInputStream(ByteArray(0)),
                totalDeadlineMs = 0,
            )
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("totalDeadlineMs")
        }
    }
}

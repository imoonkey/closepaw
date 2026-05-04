package ai.closepaw.browser.cdp

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * Per-session unguessable token for the localhost CDP relays. Both
 * [ai.closepaw.browser.cdp.shizuku.ChromeDevtoolsUserService] and
 * [ai.closepaw.browser.cdp.wireless.WirelessAdbSelfPairTransport] bind 127.0.0.1:0 — every
 * other app on the device can dial the same port. Without auth, any local app can drive
 * Chrome DevTools the moment a script is running.
 *
 * Defense: [ChromeCdpClient]'s OkHttp WebSocket client sends [HEADER_NAME] with the token in
 * the WS Upgrade request; the relay's accept loop reads the HTTP request line + headers and
 * 403s anything that doesn't match. The relay forwards the buffered request bytes verbatim
 * — Chrome silently ignores [HEADER_NAME].
 */
object RelayAuthToken {

    /** Header that carries the per-session token in the WS Upgrade request. */
    const val HEADER_NAME = "X-ClosePaw-Token"

    /** 32 random bytes hex-encoded — 256 bits, plenty of entropy against guess attacks. */
    fun generate(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Read up to [HEADER_BUFFER_LIMIT] bytes from [input] looking for the end of HTTP headers
     * (`\r\n\r\n`). On success, return the buffered bytes (so callers can replay them upstream)
     * paired with the parsed value of [HEADER_NAME] — or null if absent. On failure (EOF before
     * end-of-headers, or limit exceeded), return [Failure].
     *
     * Constant-time comparison happens in [verify] — this method only parses.
     */
    fun readHttpRequestHead(input: InputStream): ParseResult {
        val buf = ByteArray(HEADER_BUFFER_LIMIT)
        var size = 0
        var headerEnd = -1
        while (size < HEADER_BUFFER_LIMIT) {
            val n = try {
                input.read(buf, size, HEADER_BUFFER_LIMIT - size)
            } catch (e: Exception) {
                return ParseResult.Failure("read failed: ${e.message}")
            }
            if (n < 0) return ParseResult.Failure("EOF before end of HTTP headers (read $size bytes)")
            size += n
            headerEnd = indexOfDoubleCrlf(buf, size)
            if (headerEnd >= 0) break
        }
        if (headerEnd < 0) {
            return ParseResult.Failure("end-of-headers not found within $HEADER_BUFFER_LIMIT bytes")
        }
        val header = extractHeaderValue(buf, headerEnd, HEADER_NAME)
        val bytes = buf.copyOf(size)
        return ParseResult.Success(bytes = bytes, token = header)
    }

    /**
     * Compare [actual] against [expected] in constant time. Both must be non-blank and equal in
     * length+bytes. Length comparison can short-circuit safely — different lengths can never
     * match — but byte comparison must run to completion to deny timing oracles.
     */
    fun verify(expected: String, actual: String?): Boolean {
        if (actual.isNullOrEmpty() || expected.isEmpty()) return false
        val a = expected.toByteArray(Charsets.US_ASCII)
        val b = actual.toByteArray(Charsets.US_ASCII)
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /** Write a minimal HTTP/1.1 403 Forbidden + close. Best-effort; ignores write errors. */
    fun write403(output: OutputStream) {
        runCatching {
            output.write(HTTP_403_BYTES)
            output.flush()
        }
    }

    private fun indexOfDoubleCrlf(buf: ByteArray, size: Int): Int {
        if (size < 4) return -1
        outer@ for (i in 0..size - 4) {
            for (j in 0 until 4) if (buf[i + j] != CRLFCRLF[j]) continue@outer
            return i
        }
        return -1
    }

    private fun extractHeaderValue(buf: ByteArray, headerEnd: Int, name: String): String? {
        // ISO_8859_1 is the HTTP/1.1 default for header bytes — every byte is a valid char so
        // we never lose data. Header line tokens are ASCII anyway.
        val text = String(buf, 0, headerEnd, Charsets.ISO_8859_1)
        for (line in text.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            if (!key.equals(name, ignoreCase = true)) continue
            return line.substring(idx + 1).trim()
        }
        return null
    }

    sealed interface ParseResult {
        data class Success(val bytes: ByteArray, val token: String?) : ParseResult
        data class Failure(val reason: String) : ParseResult
    }

    /**
     * 4 KiB caps the request-line + headers we'll buffer before deciding to allow or 403.
     * Real WS Upgrade headers from OkHttp are well under 1 KiB; an attacker sending a giant
     * blob to exhaust memory hits this cap and gets 403'd.
     */
    const val HEADER_BUFFER_LIMIT = 4096

    private val CRLFCRLF = byteArrayOf(0x0d, 0x0a, 0x0d, 0x0a)

    private val HTTP_403_BYTES = (
        "HTTP/1.1 403 Forbidden\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        ).toByteArray(Charsets.US_ASCII)
}

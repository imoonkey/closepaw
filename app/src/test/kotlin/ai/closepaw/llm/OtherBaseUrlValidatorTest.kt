package ai.closepaw.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OtherBaseUrlValidatorTest {

    // ── allowDebugHttp = false (release policy) ──────────────────────────

    @Test
    fun `release rejects http everywhere`() {
        val cases = listOf(
            "http://localhost",
            "http://127.0.0.1",
            "http://10.0.2.2",
            "http://api.example.com/v1",
        )
        for (input in cases) {
            val result = OtherBaseUrlValidator.validate(input, allowDebugHttp = false)
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).hasMessageThat().contains("https")
        }
    }

    @Test
    fun `release accepts https`() {
        val result = OtherBaseUrlValidator.validate("https://api.example.com/v1", allowDebugHttp = false)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()).isEqualTo("https://api.example.com/v1")
    }

    // ── allowDebugHttp = true (debug policy) ─────────────────────────────

    @Test
    fun `debug accepts http only for loopback hosts`() {
        val accepted = listOf(
            "http://localhost" to "http://localhost",
            "http://127.0.0.1" to "http://127.0.0.1",
            "http://10.0.2.2" to "http://10.0.2.2",
            "http://LOCALHOST:8080/v1" to "http://LOCALHOST:8080/v1",
        )
        for ((input, normalized) in accepted) {
            val result = OtherBaseUrlValidator.validate(input, allowDebugHttp = true)
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow()).isEqualTo(normalized)
        }
    }

    @Test
    fun `debug rejects http for non-loopback hosts`() {
        val result = OtherBaseUrlValidator.validate("http://api.example.com/v1", allowDebugHttp = true)
        assertThat(result.isFailure).isTrue()
        val msg = result.exceptionOrNull()?.message.orEmpty()
        assertThat(msg).contains("localhost")
    }

    @Test
    fun `debug accepts https everywhere`() {
        val result = OtherBaseUrlValidator.validate("https://api.example.com/v1", allowDebugHttp = true)
        assertThat(result.isSuccess).isTrue()
    }

    // ── scheme / host validation ─────────────────────────────────────────

    @Test
    fun `rejects ftp and other non-http schemes`() {
        val result = OtherBaseUrlValidator.validate("ftp://example.com/", allowDebugHttp = true)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("http")
    }

    @Test
    fun `rejects empty input`() {
        val result = OtherBaseUrlValidator.validate("", allowDebugHttp = true)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `rejects whitespace-only input`() {
        val result = OtherBaseUrlValidator.validate("   ", allowDebugHttp = true)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `rejects empty host`() {
        val result = OtherBaseUrlValidator.validate("https:///v1", allowDebugHttp = true)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("host")
    }

    // ── normalization ────────────────────────────────────────────────────

    @Test
    fun `trims trailing slash`() {
        val result = OtherBaseUrlValidator.validate("https://api.example.com/v1/", allowDebugHttp = false)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()).isEqualTo("https://api.example.com/v1")
    }

    @Test
    fun `trims surrounding whitespace`() {
        val result = OtherBaseUrlValidator.validate("  https://api.example.com/v1  ", allowDebugHttp = false)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()).isEqualTo("https://api.example.com/v1")
    }

    // ── reject sensitive URL segments (Codex review HIGH #2) ─────────────

    @Test
    fun `rejects user-info in URL and message does not echo the secret`() {
        val result = OtherBaseUrlValidator.validate(
            "https://eve:supersecret@api.example.com/v1",
            allowDebugHttp = false,
        )
        assertThat(result.isFailure).isTrue()
        val msg = result.exceptionOrNull()?.message.orEmpty()
        // The actual user-info from the input must not appear in the error,
        // but the error may name the rejected element ("credentials").
        assertThat(msg).doesNotContain("supersecret")
        assertThat(msg).doesNotContain("eve")
        assertThat(msg).contains("credentials")
    }

    @Test
    fun `rejects query string and message does not echo the secret`() {
        val result = OtherBaseUrlValidator.validate(
            "https://api.example.com/v1?api_key=supersecret",
            allowDebugHttp = false,
        )
        assertThat(result.isFailure).isTrue()
        val msg = result.exceptionOrNull()?.message.orEmpty()
        assertThat(msg).doesNotContain("supersecret")
        assertThat(msg).doesNotContain("api_key")
        assertThat(msg).contains("query")
    }

    @Test
    fun `rejects fragment and message does not echo the secret`() {
        val result = OtherBaseUrlValidator.validate(
            "https://api.example.com/v1#supersecret",
            allowDebugHttp = false,
        )
        assertThat(result.isFailure).isTrue()
        val msg = result.exceptionOrNull()?.message.orEmpty()
        assertThat(msg).doesNotContain("supersecret")
        assertThat(msg).contains("fragment")
    }
}

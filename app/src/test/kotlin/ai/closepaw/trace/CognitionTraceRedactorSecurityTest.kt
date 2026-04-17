package ai.closepaw.trace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Security regression: password fields and other sensitive keys must be redacted
 * from traces/prompts/history so they never leak into debug artifacts.
 */
class CognitionTraceRedactorSecurityTest {

    // ── Password field redaction in JSON ─────────────────────────────

    @Test
    fun `password key value is redacted in JSON`() {
        val json = JsonObject(mapOf(
            "username" to JsonPrimitive("alice"),
            "password" to JsonPrimitive("s3cret!")
        ))

        val redacted = CognitionTraceRedactor.redactJson(json).jsonObject

        assertThat(redacted["password"]?.jsonPrimitive?.content).isEqualTo("[REDACTED]")
        assertThat(redacted["username"]?.jsonPrimitive?.content).isEqualTo("alice")
    }

    @Test
    fun `key containing password substring is redacted`() {
        val json = JsonObject(mapOf(
            "user_password_hash" to JsonPrimitive("abc123")
        ))

        val redacted = CognitionTraceRedactor.redactJson(json).jsonObject

        assertThat(redacted["user_password_hash"]?.jsonPrimitive?.content).isEqualTo("[REDACTED]")
    }

    @Test
    fun `token key is redacted`() {
        val json = JsonObject(mapOf(
            "access_token" to JsonPrimitive("eyJhbG...")
        ))

        val redacted = CognitionTraceRedactor.redactJson(json).jsonObject

        assertThat(redacted["access_token"]?.jsonPrimitive?.content).isEqualTo("[REDACTED]")
    }

    @Test
    fun `api_key key is redacted`() {
        val json = JsonObject(mapOf(
            "api_key" to JsonPrimitive("sk-live-1234"),
            "name" to JsonPrimitive("test")
        ))

        val redacted = CognitionTraceRedactor.redactJson(json).jsonObject

        assertThat(redacted["api_key"]?.jsonPrimitive?.content).isEqualTo("[REDACTED]")
        assertThat(redacted["name"]?.jsonPrimitive?.content).isEqualTo("test")
    }

    // ── Password text redaction in plain strings ─────────────────────

    @Test
    fun `text containing email is redacted`() {
        val text = "Login: user@example.com with password"
        val redacted = CognitionTraceRedactor.redactText(text)

        assertThat(redacted).doesNotContain("user@example.com")
        assertThat(redacted).contains("[REDACTED_EMAIL]")
    }

    @Test
    fun `text containing bearer token is redacted`() {
        val text = "Authorization: Bearer abcdefghijklmnop"
        val redacted = CognitionTraceRedactor.redactText(text)

        assertThat(redacted).doesNotContain("abcdefghijklmnop")
        assertThat(redacted).contains("[REDACTED_TOKEN]")
    }

    // ── Nested JSON redaction ────────────────────────────────────────

    @Test
    fun `nested password fields are redacted`() {
        val json = JsonObject(mapOf(
            "credentials" to JsonObject(mapOf(
                "password" to JsonPrimitive("deeply-hidden"),
                "username" to JsonPrimitive("bob")
            ))
        ))

        val redacted = CognitionTraceRedactor.redactJson(json).jsonObject
        val creds = redacted["credentials"]?.jsonObject

        assertThat(creds?.get("password")?.jsonPrimitive?.content).isEqualTo("[REDACTED]")
        assertThat(creds?.get("username")?.jsonPrimitive?.content).isEqualTo("bob")
    }

    // ── Non-sensitive data is preserved ──────────────────────────────

    @Test
    fun `non-sensitive text is not modified`() {
        val text = "Open Settings and tap Bluetooth"
        val redacted = CognitionTraceRedactor.redactText(text)

        assertThat(redacted).isEqualTo(text)
    }
}

package ai.closepaw.auth

import java.net.URLDecoder
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIOAuthTest {

    private val base64UrlPattern = Regex("^[A-Za-z0-9_-]+$")

    @Test
    fun `PKCE verifier and challenge have valid base64url format and length`() {
        val pkce = generatePkce()

        assertTrue("verifier length in [43,128]", pkce.verifier.length in 43..128)
        assertTrue("challenge length in [43,128]", pkce.challenge.length in 43..128)
        assertTrue("verifier is base64url", base64UrlPattern.matches(pkce.verifier))
        assertTrue("challenge is base64url", base64UrlPattern.matches(pkce.challenge))

        // Sanity: two calls should differ.
        val other = generatePkce()
        assertTrue(pkce.verifier != other.verifier)
    }

    @Test
    fun `generateOAuthState returns 32-char hex string`() {
        val state = generateOAuthState()
        assertEquals(32, state.length)
        assertTrue(Regex("^[0-9a-f]{32}$").matches(state))
    }

    @Test
    fun `buildAuthorizeUrl contains required OAuth parameters`() {
        val challenge = "test-challenge-abc123"
        val state = "csrf-state-xyz"
        val url = buildAuthorizeUrl(challenge, state)

        assertTrue(url.startsWith(OAuthConfig.AUTHORIZE_URL + "?"))
        val query = url.substringAfter("?")
        val params = query.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

        assertEquals("code", params["response_type"])
        assertEquals(OAuthConfig.CLIENT_ID, params["client_id"])
        assertEquals(OAuthConfig.REDIRECT_URI, params["redirect_uri"])
        assertEquals(challenge, params["code_challenge"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals(state, params["state"])
        assertEquals(OAuthConfig.SCOPE, params["scope"])
    }

    @Test
    fun `parseEmailFromJwt extracts standard email claim`() {
        val jwt = craftJwt(JSONObject().apply { put("email", "user@example.com") })
        assertEquals("user@example.com", parseEmailFromJwt(jwt))
    }

    @Test
    fun `parseEmailFromJwt prefers OpenAI profile email claim`() {
        val payload = JSONObject().apply {
            put("https://api.openai.com/profile.email", "openai@example.com")
            put("email", "fallback@example.com")
        }
        val jwt = craftJwt(payload)
        assertEquals("openai@example.com", parseEmailFromJwt(jwt))
    }

    @Test
    fun `parseEmailFromJwt returns null for malformed token`() {
        assertNull(parseEmailFromJwt("not-a-jwt"))
        assertNull(parseEmailFromJwt("only.two"))
    }

    private fun craftJwt(payload: JSONObject): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val body = enc.encodeToString(payload.toString().toByteArray())
        val sig = enc.encodeToString("sig".toByteArray())
        return "$header.$body.$sig"
    }
}

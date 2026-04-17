package ai.closepaw.app

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

class AppSettingsStateTest {

    private lateinit var store: AppSettingsStore
    private lateinit var state: AppSettingsState

    @Before
    fun setUp() {
        store = mockk(relaxed = true)
        state = AppSettingsState(store)
    }

    // ── buildApiKeys: OAuth credential selection ────────────────────────

    @Test
    fun `buildApiKeys returns OAuth token when authMethod is oauth`() {
        state.updateAuthMethod("oauth")
        state.updateOpenAiOAuthAccessToken("oauth-token-123")
        state.updateOpenAiManualApiKey("sk-manual-key")

        val keys = state.buildApiKeys()

        assertThat(keys["OPENAI_API_KEY"]).isEqualTo("oauth-token-123")
    }

    @Test
    fun `buildApiKeys returns manual key when authMethod is null`() {
        state.updateAuthMethod(null)
        state.updateOpenAiManualApiKey("sk-manual-key")

        val keys = state.buildApiKeys()

        assertThat(keys["OPENAI_API_KEY"]).isEqualTo("sk-manual-key")
    }

    @Test
    fun `buildApiKeys falls back to legacy apiKey when OAuth token blank`() {
        state.updateAuthMethod("oauth")
        state.updateOpenAiOAuthAccessToken("")
        state.updateApiKey("sk-legacy-key")

        val keys = state.buildApiKeys()

        assertThat(keys["OPENAI_API_KEY"]).isEqualTo("sk-legacy-key")
    }

    @Test
    fun `buildApiKeys falls back to legacy apiKey when manual key blank`() {
        state.updateAuthMethod(null)
        state.updateOpenAiManualApiKey("")
        state.updateApiKey("sk-legacy-key")

        val keys = state.buildApiKeys()

        assertThat(keys["OPENAI_API_KEY"]).isEqualTo("sk-legacy-key")
    }

    @Test
    fun `buildApiKeys includes AUTH_METHOD_OPENAI only when OAuth`() {
        state.updateAuthMethod("oauth")
        state.updateOpenAiOAuthAccessToken("token")

        val oauthKeys = state.buildApiKeys()
        assertThat(oauthKeys).containsEntry("__AUTH_METHOD_OPENAI", "oauth")

        state.updateAuthMethod(null)
        val manualKeys = state.buildApiKeys()
        assertThat(manualKeys).doesNotContainKey("__AUTH_METHOD_OPENAI")
    }

    @Test
    fun `buildApiKeys excludes blank keys`() {
        val keys = state.buildApiKeys()

        assertThat(keys).doesNotContainKey("OPENAI_API_KEY")
        assertThat(keys).doesNotContainKey("OPENROUTER_API_KEY")
        assertThat(keys).doesNotContainKey("NOVITA_API_KEY")
    }

    // ── Sign-out preserves manual key ───────────────────────────────────

    @Test
    fun `sign-out preserves manual key after clearing OAuth`() {
        state.updateOpenAiManualApiKey("sk-manual-key")
        state.updateAuthMethod("oauth")
        state.updateOpenAiOAuthAccessToken("oauth-token")

        // OAuth active
        assertThat(state.buildApiKeys()["OPENAI_API_KEY"]).isEqualTo("oauth-token")

        // Sign out
        state.updateOpenAiOAuthAccessToken("")
        state.updateAuthMethod(null)

        assertThat(state.openAiManualApiKey).isEqualTo("sk-manual-key")
        assertThat(state.buildApiKeys()["OPENAI_API_KEY"]).isEqualTo("sk-manual-key")
    }

    @Test
    fun `buildApiKeys switches to manual key after OAuth cleared`() {
        state.updateOpenAiManualApiKey("sk-manual")
        state.updateAuthMethod("oauth")
        state.updateOpenAiOAuthAccessToken("oauth-tok")

        assertThat(state.buildApiKeys()["OPENAI_API_KEY"]).isEqualTo("oauth-tok")

        state.updateOpenAiOAuthAccessToken("")
        state.updateAuthMethod(null)

        assertThat(state.buildApiKeys()["OPENAI_API_KEY"]).isEqualTo("sk-manual")
    }

    // ── Other providers ─────────────────────────────────────────────────

    @Test
    fun `buildApiKeys includes OpenRouter and Novita keys`() {
        state.updateOpenRouterApiKey("or-key")
        state.updateNovitaApiKey("nv-key")

        val keys = state.buildApiKeys()

        assertThat(keys["OPENROUTER_API_KEY"]).isEqualTo("or-key")
        assertThat(keys["NOVITA_API_KEY"]).isEqualTo("nv-key")
    }

    @Test
    fun `buildApiKeys includes base URL override when set`() {
        state.updateOpenaiBaseUrl("http://localhost:8000/v1")

        val keys = state.buildApiKeys()

        assertThat(keys["__BASE_URL_OPENAI"]).isEqualTo("http://localhost:8000/v1")
    }
}

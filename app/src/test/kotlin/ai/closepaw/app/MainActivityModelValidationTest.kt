package ai.closepaw.app

import ai.closepaw.auth.AuthStore
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.protocol.LLMBackendType
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class MainActivityModelValidationTest {

    private val catalog = ModelCatalog.fromJson(
        """
        {
          "gpt-5.2": {"display_name":"GPT-5.2","provider":"OPENAI_API","api":"response","model_id":"gpt-5.2"},
          "gpt-5.2-codex": {"display_name":"GPT-5.2 Codex","provider":"OPENAI_CODEX","api":"response","model_id":"gpt-5.2"},
          "glm-4.7": {"display_name":"GLM-4.7","provider":"OPENROUTER","api":"chat","model_id":"z-ai/glm-4.7"},
          "autoglm-9b": {"display_name":"AutoGLM 9B","provider":"OTHER","api":"chat","model_id":"zai-org/autoglm-phone-9b","base_url":"https://example.invalid/v1"}
        }
        """.trimIndent()
    )

    private fun settings(
        backend: LLMBackendType = LLMBackendType.OPENAI,
        mainModel: String = "gpt-5.2",
        otherBaseUrl: String = "",
        otherModelId: String = "",
    ): AppSettingsState {
        val s = mockk<AppSettingsState>(relaxed = true)
        every { s.llmBackend } returns backend
        every { s.selectedModel } returns mainModel
        every { s.otherBaseUrl } returns otherBaseUrl
        every { s.otherModelId } returns otherModelId
        return s
    }

    private fun emptyAuthStore(): AuthStore {
        val store = mockk<AuthStore>(relaxed = true)
        every { store.has(any()) } returns false
        return store
    }

    @Test
    fun `returns empty when local backend selected`() {
        val missing = findMissingCloudKeys(
            settings(backend = LLMBackendType.LOCAL),
            catalog,
            emptyAuthStore(),
        )
        assertThat(missing).isEmpty()
    }

    @Test
    fun `flags main model with missing OpenAI key`() {
        val missing = findMissingCloudKeys(settings(), catalog, emptyAuthStore())
        assertThat(missing).hasSize(1)
        assertThat(missing[0].provider).isEqualTo(LLMProvider.OPENAI_API)
        assertThat(missing[0].message).contains("OpenAI API key required")
    }

    @Test
    fun `flags Codex sign-in for OPENAI_CODEX provider`() {
        val missing = findMissingCloudKeys(
            settings(mainModel = "gpt-5.2-codex"),
            catalog,
            emptyAuthStore(),
        )
        assertThat(missing).hasSize(1)
        assertThat(missing[0].provider).isEqualTo(LLMProvider.OPENAI_CODEX)
        assertThat(missing[0].message).contains("ChatGPT sign-in required")
    }

    @Test
    fun `unknown model surfaces as OPENAI_API target`() {
        val missing = findMissingCloudKeys(
            settings(mainModel = "ghost-model"),
            catalog,
            emptyAuthStore(),
        )
        assertThat(missing).hasSize(1)
        assertThat(missing[0].provider).isEqualTo(LLMProvider.OPENAI_API)
        assertThat(missing[0].message).contains("Unknown model: ghost-model")
    }

    @Test
    fun `returns empty when key present`() {
        val store = mockk<AuthStore>(relaxed = true)
        every { store.has(LLMProvider.OPENAI_API) } returns true
        val missing = findMissingCloudKeys(settings(), catalog, store)
        assertThat(missing).isEmpty()
    }

    @Test
    fun `other-custom short-circuits and flags every blank field`() {
        val missing = findMissingCloudKeys(
            settings(mainModel = "other-custom"),
            catalog,
            emptyAuthStore(),
        )
        assertThat(missing.map { it.provider }).containsExactly(
            LLMProvider.OTHER,
            LLMProvider.OTHER,
            LLMProvider.OTHER,
        )
        val messages = missing.map { it.message }
        assertThat(messages.any { it.contains("API key required") }).isTrue()
        assertThat(messages.any { it.contains("base URL required") }).isTrue()
        assertThat(messages.any { it.contains("custom model id required") }).isTrue()
    }

    @Test
    fun `other-custom flags invalid base URL`() {
        val store = mockk<AuthStore>(relaxed = true)
        every { store.has(LLMProvider.OTHER) } returns true
        val missing = findMissingCloudKeys(
            settings(
                mainModel = "other-custom",
                otherBaseUrl = "not a url",
                otherModelId = "vendor/model",
            ),
            catalog,
            store,
        )
        assertThat(missing).hasSize(1)
        assertThat(missing[0].message).contains("base URL invalid")
    }

    @Test
    fun `other-custom passes when key + url + modelId all present and valid`() {
        val store = mockk<AuthStore>(relaxed = true)
        every { store.has(LLMProvider.OTHER) } returns true
        val missing = findMissingCloudKeys(
            settings(
                mainModel = "other-custom",
                otherBaseUrl = "https://api.example.com/v1",
                otherModelId = "vendor/model",
            ),
            catalog,
            store,
        )
        assertThat(missing).isEmpty()
    }
}

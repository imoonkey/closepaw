package ai.closepaw.app

import ai.closepaw.auth.AuthStore
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.protocol.AgentMode
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
          "autoglm-9b": {"display_name":"AutoGLM 9B","provider":"NOVITA","api":"chat","model_id":"zai-org/autoglm-phone-9b"}
        }
        """.trimIndent()
    )

    private fun settings(
        backend: LLMBackendType = LLMBackendType.OPENAI,
        mainModel: String = "gpt-5.2",
        executor: String? = null,
        mode: AgentMode = AgentMode.BASIC,
    ): AppSettingsState {
        val s = mockk<AppSettingsState>(relaxed = true)
        every { s.llmBackend } returns backend
        every { s.selectedModel } returns mainModel
        every { s.subagentModel } returns executor
        every { s.agentMode } returns mode
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
    fun `pro mode flags both main and executor missing keys`() {
        val missing = findMissingCloudKeys(
            settings(mainModel = "gpt-5.2", executor = "autoglm-9b", mode = AgentMode.PRO),
            catalog,
            emptyAuthStore(),
        )
        val providers = missing.map { it.provider }
        assertThat(providers).containsExactly(LLMProvider.OPENAI_API, LLMProvider.NOVITA)
    }

    @Test
    fun `pro mode skips executor when only main missing`() {
        val store = mockk<AuthStore>(relaxed = true)
        every { store.has(LLMProvider.OPENAI_API) } returns false
        every { store.has(LLMProvider.NOVITA) } returns true
        val missing = findMissingCloudKeys(
            settings(mainModel = "gpt-5.2", executor = "autoglm-9b", mode = AgentMode.PRO),
            catalog,
            store,
        )
        assertThat(missing.map { it.provider }).containsExactly(LLMProvider.OPENAI_API)
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
}

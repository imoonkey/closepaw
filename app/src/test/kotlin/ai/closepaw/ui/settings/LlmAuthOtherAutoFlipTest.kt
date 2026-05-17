package ai.closepaw.ui.settings

import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Regression for Sub 1c Codex review HIGH #1: auto-flip must wait until the
 * `other-custom` catalog row reflects the CURRENT normalized UI values, not the
 * mere presence of any other-custom row. A stale row from a previous valid
 * config would otherwise let a mid-edit launch hit the old endpoint with the
 * new key.
 */
class LlmAuthOtherAutoFlipTest {

    private fun catalogWith(baseUrl: String, modelId: String): ModelCatalog =
        ModelCatalog.fromJson(
            """{ "other-custom": {"display_name":"Custom","provider":"OTHER","api":"chat","model_id":"$modelId","base_url":"$baseUrl"} }"""
        )

    private val emptyCatalog: ModelCatalog = ModelCatalog.fromJson(
        // ModelCatalog requires at least one entry; the test asserts absence of `other-custom`,
        // so any unrelated seed entry is fine.
        """{ "glm-5": {"display_name":"GLM-5","provider":"OPENROUTER","api":"chat","model_id":"z-ai/glm-5"} }"""
    )

    @Test
    fun `flips when provider OTHER and catalog matches normalized UI values`() {
        val flip = shouldAutoFlipToOtherCustom(
            selectedProvider = LLMProvider.OTHER,
            apiKeyText = "sk-x",
            otherBaseUrlText = "https://api.example.com/v1",
            otherModelIdText = "vendor/model",
            modelCatalog = catalogWith("https://api.example.com/v1", "vendor/model"),
            selectedModel = "glm-5",
        )
        assertThat(flip).isTrue()
    }

    @Test
    fun `does NOT flip when catalog entry is stale relative to base URL`() {
        // Stale row from previous valid config still present in the catalog (because
        // either field is non-blank). The new UI value hasn't been persisted yet.
        val flip = shouldAutoFlipToOtherCustom(
            selectedProvider = LLMProvider.OTHER,
            apiKeyText = "sk-x",
            otherBaseUrlText = "https://api.new.com/v1",
            otherModelIdText = "vendor/model",
            modelCatalog = catalogWith("https://api.old.com/v1", "vendor/model"),
            selectedModel = "glm-5",
        )
        assertThat(flip).isFalse()
    }

    @Test
    fun `does NOT flip when catalog entry is stale relative to model id`() {
        val flip = shouldAutoFlipToOtherCustom(
            selectedProvider = LLMProvider.OTHER,
            apiKeyText = "sk-x",
            otherBaseUrlText = "https://api.example.com/v1",
            otherModelIdText = "vendor/NEW-model",
            modelCatalog = catalogWith("https://api.example.com/v1", "vendor/old-model"),
            selectedModel = "glm-5",
        )
        assertThat(flip).isFalse()
    }

    @Test
    fun `does NOT flip when no catalog entry exists`() {
        val flip = shouldAutoFlipToOtherCustom(
            selectedProvider = LLMProvider.OTHER,
            apiKeyText = "sk-x",
            otherBaseUrlText = "https://api.example.com/v1",
            otherModelIdText = "vendor/model",
            modelCatalog = emptyCatalog,
            selectedModel = "glm-5",
        )
        assertThat(flip).isFalse()
    }

    @Test
    fun `does NOT flip when base URL invalid`() {
        val flip = shouldAutoFlipToOtherCustom(
            selectedProvider = LLMProvider.OTHER,
            apiKeyText = "sk-x",
            otherBaseUrlText = "not a url",
            otherModelIdText = "vendor/model",
            modelCatalog = catalogWith("https://api.example.com/v1", "vendor/model"),
            selectedModel = "glm-5",
        )
        assertThat(flip).isFalse()
    }

    @Test
    fun `does NOT flip when api key blank`() {
        val flip = shouldAutoFlipToOtherCustom(
            selectedProvider = LLMProvider.OTHER,
            apiKeyText = "",
            otherBaseUrlText = "https://api.example.com/v1",
            otherModelIdText = "vendor/model",
            modelCatalog = catalogWith("https://api.example.com/v1", "vendor/model"),
            selectedModel = "glm-5",
        )
        assertThat(flip).isFalse()
    }

    @Test
    fun `does NOT flip when provider is not OTHER`() {
        val flip = shouldAutoFlipToOtherCustom(
            selectedProvider = LLMProvider.OPENAI_API,
            apiKeyText = "sk-x",
            otherBaseUrlText = "https://api.example.com/v1",
            otherModelIdText = "vendor/model",
            modelCatalog = catalogWith("https://api.example.com/v1", "vendor/model"),
            selectedModel = "glm-5",
        )
        assertThat(flip).isFalse()
    }

    @Test
    fun `does NOT flip when already on other-custom`() {
        val flip = shouldAutoFlipToOtherCustom(
            selectedProvider = LLMProvider.OTHER,
            apiKeyText = "sk-x",
            otherBaseUrlText = "https://api.example.com/v1",
            otherModelIdText = "vendor/model",
            modelCatalog = catalogWith("https://api.example.com/v1", "vendor/model"),
            selectedModel = "other-custom",
        )
        assertThat(flip).isFalse()
    }

    @Test
    fun `normalizes trailing slash before comparing`() {
        // Catalog stores normalized URL (no trailing slash). User input still has it.
        // Normalization must be applied before equality check, else legitimate flips
        // would be blocked.
        val flip = shouldAutoFlipToOtherCustom(
            selectedProvider = LLMProvider.OTHER,
            apiKeyText = "sk-x",
            otherBaseUrlText = "https://api.example.com/v1/",
            otherModelIdText = "vendor/model",
            modelCatalog = catalogWith("https://api.example.com/v1", "vendor/model"),
            selectedModel = "glm-5",
        )
        assertThat(flip).isTrue()
    }
}

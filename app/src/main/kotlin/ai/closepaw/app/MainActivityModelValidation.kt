package ai.closepaw.app

import ai.closepaw.auth.AuthStore
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.protocol.LLMBackendType

/** Deep-link target for a missing-credential banner tap. */
data class MissingCredentialTarget(
    val provider: LLMProvider,
    val message: String,
)

/**
 * Validate that a credential exists for the main model selected for the next session.
 * Returns one entry per missing credential with provider info so the caller can deep-link
 * into the right settings tab.
 */
internal fun findMissingCloudKeys(
    settingsState: AppSettingsState,
    modelCatalog: ModelCatalog,
    authStore: AuthStore,
): List<MissingCredentialTarget> {
    if (settingsState.llmBackend != LLMBackendType.OPENAI) return emptyList()

    val modelName = settingsState.selectedModel
    val entry = modelCatalog.resolveOrNull(modelName)
        ?: return listOf(MissingCredentialTarget(LLMProvider.OPENAI_API, "Unknown model: $modelName"))
    val provider = entry.provider
    if (provider == LLMProvider.LOCAL_LFM) return emptyList()
    if (authStore.has(provider)) return emptyList()

    val label = when (provider) {
        LLMProvider.OPENAI_CODEX -> "ChatGPT sign-in required"
        LLMProvider.OPENAI_API -> "OpenAI API key required"
        LLMProvider.OPENROUTER -> "OpenRouter API key required"
        LLMProvider.NOVITA -> "Novita API key required"
        LLMProvider.LOCAL_LFM -> return emptyList()
    }
    return listOf(MissingCredentialTarget(provider, "${entry.displayName}: $label"))
}

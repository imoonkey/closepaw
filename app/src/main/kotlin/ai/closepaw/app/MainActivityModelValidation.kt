package ai.closepaw.app

import ai.closepaw.auth.AuthStore
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.llm.ModelCatalogRepository
import ai.closepaw.llm.ModelIdValidator
import ai.closepaw.llm.OtherBaseUrlValidator
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
 *
 * Short-circuits on `selectedModel == "other-custom"` because when `otherBaseUrl` or
 * `otherModelId` are blank, the synth entry is not yet in the catalog and
 * `modelCatalog.resolveOrNull` returns null. Without the short-circuit the banner would
 * say "Unknown model: other-custom" instead of the actionable "Other needs base URL".
 *
 * Also short-circuits discovered OTHER entries (`selectedModel.startsWith("other:")`)
 * that have fallen out of the catalog — typically because the user changed
 * `otherBaseUrl` after a previous refresh. In that case the cache still has the entry
 * but the visible-name scope hides it (see [ModelCatalogRepository]). The banner sends
 * the user to the Other tab with a "Refresh or select a model for this endpoint" hint
 * instead of the generic "Unknown model" wall.
 */
internal fun findMissingCloudKeys(
    settingsState: AppSettingsState,
    modelCatalog: ModelCatalog,
    authStore: AuthStore,
): List<MissingCredentialTarget> {
    if (settingsState.llmBackend != LLMBackendType.OPENAI) return emptyList()

    val modelName = settingsState.selectedModel

    if (modelName == ModelCatalogRepository.OTHER_CUSTOM_NAME) {
        return findOtherMissing(settingsState, authStore)
    }

    if (modelName.startsWith(DISCOVERED_OTHER_PREFIX) &&
        modelCatalog.resolveOrNull(modelName) == null) {
        return listOf(
            MissingCredentialTarget(
                LLMProvider.OTHER,
                "Refresh or select a model for this endpoint",
            )
        )
    }

    val entry = modelCatalog.resolveOrNull(modelName)
        ?: return listOf(MissingCredentialTarget(LLMProvider.OPENAI_API, "Unknown model: $modelName"))
    val provider = entry.provider
    if (provider == LLMProvider.LOCAL_LFM) return emptyList()
    if (authStore.has(provider)) return emptyList()

    val label = when (provider) {
        LLMProvider.OPENAI_CODEX -> "ChatGPT sign-in required"
        LLMProvider.OPENAI_API -> "OpenAI API key required"
        LLMProvider.OPENROUTER -> "OpenRouter API key required"
        LLMProvider.OTHER -> "API key required"
        LLMProvider.LOCAL_LFM -> return emptyList()
    }
    return listOf(MissingCredentialTarget(provider, "${entry.displayName}: $label"))
}

private const val DISCOVERED_OTHER_PREFIX = "other:"

private fun findOtherMissing(
    settingsState: AppSettingsState,
    authStore: AuthStore,
): List<MissingCredentialTarget> {
    val missing = mutableListOf<MissingCredentialTarget>()
    if (!authStore.has(LLMProvider.OTHER)) {
        missing += MissingCredentialTarget(LLMProvider.OTHER, "Other: API key required")
    }
    val baseUrl = settingsState.otherBaseUrl
    if (baseUrl.isBlank()) {
        missing += MissingCredentialTarget(LLMProvider.OTHER, "Other: base URL required")
    } else if (OtherBaseUrlValidator.validate(baseUrl).isFailure) {
        missing += MissingCredentialTarget(LLMProvider.OTHER, "Other: base URL invalid")
    }
    val modelId = settingsState.otherModelId
    if (modelId.isBlank()) {
        missing += MissingCredentialTarget(LLMProvider.OTHER, "Other: custom model id required")
    } else if (ModelIdValidator.validate(modelId).isFailure) {
        missing += MissingCredentialTarget(LLMProvider.OTHER, "Other: custom model id invalid")
    }
    return missing
}

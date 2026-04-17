package ai.closepaw.app

import ai.closepaw.llm.ModelCatalog
import ai.closepaw.protocol.AgentMode
import ai.closepaw.protocol.LLMBackendType

internal fun findMissingCloudKeys(
    settingsState: AppSettingsState,
    modelCatalog: ModelCatalog
): List<String> {
    if (settingsState.llmBackend != LLMBackendType.OPENAI) return emptyList()

    val apiKeys = settingsState.buildApiKeys()
    val modelsToValidate = linkedSetOf(settingsState.selectedModel)
    if (settingsState.agentMode == AgentMode.PRO) {
        settingsState.executorModel?.let(modelsToValidate::add)
    }

    return buildList {
        for (modelName in modelsToValidate) {
            val entry = modelCatalog.resolveOrNull(modelName)
            if (entry == null) {
                add("Unknown model: $modelName")
                continue
            }
            val requiredEnv = entry.effectiveApiKeyEnv
            if (apiKeys[requiredEnv].isNullOrBlank()) {
                add("${entry.displayName} requires $requiredEnv")
            }
        }
    }
}

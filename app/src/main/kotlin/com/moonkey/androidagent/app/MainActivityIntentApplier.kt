package com.moonkey.androidagent.app

internal data class MainActivityIntentApplyResult(
    val pendingTraceEnabled: Boolean?,
    val pendingTraceRunId: String?,
    val pendingExcludedTools: Set<String>
)

internal fun applyIntentPayloadToSettings(
    payload: MainActivityIntentPayload,
    settingsState: AppSettingsState,
    currentPendingTraceEnabled: Boolean?,
    currentPendingTraceRunId: String?,
    currentPendingExcludedTools: Set<String>,
    log: (String) -> Unit
): MainActivityIntentApplyResult {
    payload.apiKey?.let { key ->
        settingsState.updateApiKey(key)
        // Explicit API key from intent overrides OAuth — use direct API path
        settingsState.updateAuthMethod(null)
        log("API key set from intent (auth method reset to manual)")
    }
    payload.openRouterApiKey?.let { key ->
        settingsState.updateOpenRouterApiKey(key)
        log("OpenRouter API key set from intent")
    }
    payload.novitaApiKey?.let { key ->
        settingsState.updateNovitaApiKey(key)
        log("Novita API key set from intent")
    }
    payload.openaiBaseUrl?.let { url ->
        settingsState.updateOpenaiBaseUrl(url)
        log("OpenAI base URL override set from intent: $url")
    }
    payload.backendType?.let {
        settingsState.updateBackend(it)
        log("LLM backend set from intent: $it")
    }
    payload.agentMode?.let {
        settingsState.updateAgentMode(it)
        log("Agent mode set from intent: $it")
    }
    payload.perceptionMode?.let { mode ->
        settingsState.updatePerceptionMode(mode)
        log("Perception mode set from intent: $mode")
    }
    payload.platformMode?.let {
        settingsState.updatePlatformMode(it)
        log("Platform mode set from intent: $it")
    }
    payload.mainModel?.let {
        settingsState.updateModel(it)
        log("Main model set from intent: $it")
    }
    payload.executorModel?.let {
        settingsState.updateExecutorModel(it)
        log("Executor model set from intent: $it")
    }
    payload.maxTurns?.let {
        settingsState.updateMaxTurns(it)
        log("Max turns set from intent: $it")
    }
    payload.debugMode?.let { enabled ->
        settingsState.updateDebugMode(enabled)
        log("Debug mode set from intent: $enabled")
    }

    val pendingTraceEnabled =
        payload.traceEnabled?.also { enabled ->
            log("Trace enabled set from intent: $enabled")
        } ?: currentPendingTraceEnabled
    val pendingTraceRunId =
        payload.traceRunId?.also { runId ->
            log("Trace run id set from intent: $runId")
        } ?: currentPendingTraceRunId

    val pendingExcludedTools =
        payload.excludedTools.ifEmpty { currentPendingExcludedTools }.also { tools ->
            if (tools.isNotEmpty()) log("Excluded tools set from intent: $tools")
        }

    return MainActivityIntentApplyResult(
        pendingTraceEnabled = pendingTraceEnabled,
        pendingTraceRunId = pendingTraceRunId,
        pendingExcludedTools = pendingExcludedTools
    )
}

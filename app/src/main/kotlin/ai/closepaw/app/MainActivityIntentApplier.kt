package ai.closepaw.app

import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.llm.LLMProvider
import ai.closepaw.protocol.ApprovalMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MainActivityIntentApplyResult(
    val pendingTraceEnabled: Boolean?,
    val pendingTraceRunId: String?,
    val pendingExcludedTools: Set<String>,
    val pendingApprovalMode: ApprovalMode?,
    val pendingBrowserDebugTcpFallbackEnabled: Boolean?
)

/**
 * Apply intent extras to runtime state. Credential writes go to [authStore]
 * on [Dispatchers.IO] because [AuthStore.set] performs EncryptedSharedPreferences
 * init + disk I/O and must not block the main thread. Base URL override goes to
 * [AppSettingsState.openaiBaseUrl] (debug-only). Release builds no-op on every extra.
 *
 * This function is `suspend`; callers must invoke it inside a coroutine so that
 * any session launch observing the credential state happens-after the writes.
 */
internal suspend fun applyIntentPayloadToSettings(
    payload: MainActivityIntentPayload,
    settingsState: AppSettingsState,
    modelLoadingStatusHolder: ModelLoadingStatusHolder,
    authStore: AuthStore,
    isDebugBuild: Boolean,
    currentPendingTraceEnabled: Boolean?,
    currentPendingTraceRunId: String?,
    currentPendingExcludedTools: Set<String>,
    currentPendingApprovalMode: ApprovalMode?,
    currentPendingBrowserDebugTcpFallbackEnabled: Boolean?,
    log: (String) -> Unit
): MainActivityIntentApplyResult {
    if (!isDebugBuild) {
        return MainActivityIntentApplyResult(
            pendingTraceEnabled = currentPendingTraceEnabled,
            pendingTraceRunId = currentPendingTraceRunId,
            pendingExcludedTools = currentPendingExcludedTools,
            pendingApprovalMode = currentPendingApprovalMode,
            pendingBrowserDebugTcpFallbackEnabled =
                currentPendingBrowserDebugTcpFallbackEnabled
        )
    }

    // Credential writes are I/O-bound; batch on Dispatchers.IO off the caller's thread.
    withContext(Dispatchers.IO) {
        payload.apiKey?.let { key ->
            authStore.set(LLMProvider.OPENAI_API, AuthCredential.ApiKey(key))
            log("OPENAI_API key set from intent via AuthStore")
        }
        payload.openRouterApiKey?.let { key ->
            authStore.set(LLMProvider.OPENROUTER, AuthCredential.ApiKey(key))
            log("OPENROUTER key set from intent via AuthStore")
        }
        payload.novitaApiKey?.let { key ->
            authStore.set(LLMProvider.NOVITA, AuthCredential.ApiKey(key))
            log("NOVITA key set from intent via AuthStore")
        }
    }
    payload.openaiBaseUrl?.let { url ->
        settingsState.updateOpenaiBaseUrl(url)
        log("OpenAI base URL override set from intent: $url")
    }
    payload.backendType?.let {
        modelLoadingStatusHolder.updateBackend(it)
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
    payload.browserScriptEnabled?.let { enabled ->
        settingsState.updateBrowserScriptEnabled(enabled)
        log("browser_script enabled set from intent: $enabled")
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
    val pendingApprovalMode =
        payload.approvalMode?.also { mode ->
            log("Approval mode set from intent: $mode")
        } ?: currentPendingApprovalMode
    val pendingBrowserDebugTcpFallbackEnabled =
        payload.browserDebugTcpFallbackEnabled?.also { enabled ->
            log("Browser debug TCP fallback set from intent: $enabled")
        } ?: currentPendingBrowserDebugTcpFallbackEnabled

    return MainActivityIntentApplyResult(
        pendingTraceEnabled = pendingTraceEnabled,
        pendingTraceRunId = pendingTraceRunId,
        pendingExcludedTools = pendingExcludedTools,
        pendingApprovalMode = pendingApprovalMode,
        pendingBrowserDebugTcpFallbackEnabled = pendingBrowserDebugTcpFallbackEnabled
    )
}

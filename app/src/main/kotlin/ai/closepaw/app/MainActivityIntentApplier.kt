package ai.closepaw.app

import ai.closepaw.auth.AuthCredential
import ai.closepaw.auth.AuthStore
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelIdValidator
import ai.closepaw.llm.OtherBaseUrlValidator
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.ui.settings.BrowserScriptToggleError
import ai.closepaw.ui.settings.gateBrowserScriptEnable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MainActivityIntentApplyResult(
    val pendingTraceEnabled: Boolean?,
    val pendingTraceRunId: String?,
    val pendingExcludedTools: Set<String>,
    val pendingApprovalMode: ApprovalMode?,
    val pendingEvalTurnBudget: Int?,
)

/**
 * Apply intent extras to runtime state. Credential writes go to [authStore]
 * on [Dispatchers.IO] because [AuthStore.set] performs EncryptedSharedPreferences
 * init + disk I/O and must not block the main thread. Base URL override goes to
 * [AppSettingsState.openaiBaseUrl] (debug-only). Release builds no-op on every extra.
 *
 * Toggling `browser_script` ON via intent goes through the same gate the UI toggle uses
 * (Shizuku reachable + permission + writable command-line file). Persisting ON without the
 * gate would leave the toggle UI showing ON for an unusable tool — same trap the UI gates
 * against. Toggle OFF stays unconditional, mirroring the UI.
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
    currentPendingEvalTurnBudget: Int?,
    log: (String) -> Unit,
    browserScriptGate: suspend () -> BrowserScriptToggleError? = { gateBrowserScriptEnable() },
    invalidateCatalog: () -> Unit = {},
): MainActivityIntentApplyResult {
    if (!isDebugBuild) {
        return MainActivityIntentApplyResult(
            pendingTraceEnabled = currentPendingTraceEnabled,
            pendingTraceRunId = currentPendingTraceRunId,
            pendingExcludedTools = currentPendingExcludedTools,
            pendingApprovalMode = currentPendingApprovalMode,
            pendingEvalTurnBudget = currentPendingEvalTurnBudget,
        )
    }

    // Credential writes are I/O-bound; batch on Dispatchers.IO off the caller's thread.
    var otherChanged = false
    withContext(Dispatchers.IO) {
        payload.apiKey?.let { key ->
            authStore.set(LLMProvider.OPENAI_API, AuthCredential.ApiKey(key))
            log("OPENAI_API key set from intent via AuthStore")
        }
        payload.openRouterApiKey?.let { key ->
            authStore.set(LLMProvider.OPENROUTER, AuthCredential.ApiKey(key))
            log("OPENROUTER key set from intent via AuthStore")
        }
        payload.otherApiKey?.let { key ->
            authStore.set(LLMProvider.OTHER, AuthCredential.ApiKey(key))
            otherChanged = true
            log("OTHER key set from intent via AuthStore")
        }
    }
    payload.openaiBaseUrl?.let { url ->
        settingsState.updateOpenaiBaseUrl(url)
        log("OpenAI base URL override set from intent: $url")
    }
    payload.otherBaseUrl?.let { url ->
        // Validate at the intent boundary so we never persist junk into settings.
        // synthOtherEntry also re-validates, but rejecting here keeps both
        // AppSettingsState.otherBaseUrl and the on-disk preference clean — so a
        // later UI render doesn't show the user a bad value they didn't type.
        val validation = OtherBaseUrlValidator.validate(url)
        validation.onSuccess { normalized ->
            settingsState.updateOtherBaseUrl(normalized)
            otherChanged = true
            log("OTHER base URL set from intent: $normalized")
        }.onFailure { err ->
            // Don't echo the rejected URL — it could contain a sensitive host.
            log("OTHER base URL from intent rejected: ${err.message}")
        }
    }
    payload.otherModelId?.let { modelId ->
        // Validate at the intent boundary so a bad id (whitespace, leading
        // ":" / "/") never reaches settings — discovery and the synth path
        // would otherwise enforce the same rule and silently drop the entry.
        ModelIdValidator.validate(modelId).onSuccess { trimmed ->
            settingsState.updateOtherModelId(trimmed)
            otherChanged = true
            log("OTHER model id set from intent: $trimmed")
        }.onFailure { err ->
            // Don't echo the rejected id verbatim — keep the log non-secret.
            log("OTHER model id from intent rejected: ${err.message}")
        }
    }
    // Make absolutely sure the catalog reflects OTHER writes. The settings updates already
    // call `onOtherSettingsChanged()` (which invalidates the repo), but the OTHER api key
    // write goes through AuthStore directly — invalidate here so any synth entry that
    // depended on the new key/url/modelId trio is fresh in `catalog.value`.
    if (otherChanged) invalidateCatalog()
    payload.backendType?.let {
        modelLoadingStatusHolder.updateBackend(it)
        log("LLM backend set from intent: $it")
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
    payload.debugMode?.let { enabled ->
        settingsState.updateDebugMode(enabled)
        log("Debug mode set from intent: $enabled")
    }
    payload.browserScriptEnabled?.let { enabled ->
        if (!enabled) {
            // OFF is unconditional — never makes things worse, mirrors the UI toggle.
            settingsState.updateBrowserScriptEnabled(false)
            log("browser_script enabled set from intent: false")
        } else {
            // ON must clear the same gate the UI uses (Shizuku reachable + permission +
            // writable command-line file). Otherwise QA can persist ON via adb intent on a
            // device with no Shizuku, and the toggle UI later lies about a tool that can't
            // run. Same contract as BrowserScriptToggleGate.
            val gateError = browserScriptGate()
            if (gateError == null) {
                settingsState.updateBrowserScriptEnabled(true)
                log("browser_script enabled set from intent: true (gate ok)")
            } else {
                log("browser_script enable from intent skipped: gate denied ($gateError)")
            }
        }
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
    val pendingEvalTurnBudget =
        payload.evalTurnBudget?.also { budget ->
            log("Eval turn budget set from intent: $budget")
        } ?: currentPendingEvalTurnBudget

    return MainActivityIntentApplyResult(
        pendingTraceEnabled = pendingTraceEnabled,
        pendingTraceRunId = pendingTraceRunId,
        pendingExcludedTools = pendingExcludedTools,
        pendingApprovalMode = pendingApprovalMode,
        pendingEvalTurnBudget = pendingEvalTurnBudget,
    )
}

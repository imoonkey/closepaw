package com.moonkey.androidagent.app

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moonkey.androidagent.llm.LLMProvider
import com.moonkey.androidagent.protocol.AgentMode
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.ui.settings.LocalModelOption
import com.moonkey.androidagent.ui.settings.ModelLoadingStatus

class AppSettingsState(private val store: AppSettingsStore) {
    companion object {
        private const val TAG = "AppSettingsState"
    }

    var apiKey by mutableStateOf("")
        private set
    var openAiManualApiKey by mutableStateOf("")
        private set
    var openRouterApiKey by mutableStateOf("")
        private set
    var novitaApiKey by mutableStateOf("")
        private set
    var selectedModel by mutableStateOf(AppSettingsStore.DEFAULT_MODEL)
        private set
    var maxTurns by mutableStateOf(AppSettingsStore.DEFAULT_MAX_TURNS)
        private set
    var debugMode by mutableStateOf(AppSettingsStore.DEFAULT_DEBUG_MODE)
        private set
    var perceptionMode by mutableStateOf(AppSettingsStore.DEFAULT_PERCEPTION_MODE)
        private set
    var agentMode by mutableStateOf(AppSettingsStore.DEFAULT_AGENT_MODE)
        private set
    var llmBackend by mutableStateOf(AppSettingsStore.DEFAULT_LLM_BACKEND)
        private set
    var selectedLocalModelId by mutableStateOf(AppSettingsStore.DEFAULT_LOCAL_MODEL_ID)
        private set
    var localModelSlug by mutableStateOf(AppSettingsStore.DEFAULT_LOCAL_MODEL_SLUG)
        private set
    var localModelQuant by mutableStateOf(AppSettingsStore.DEFAULT_LOCAL_MODEL_QUANT)
        private set
    var modelLoadingStatus by mutableStateOf<ModelLoadingStatus>(ModelLoadingStatus.Idle)
        private set
    var executorModel by mutableStateOf<String?>(null)
        private set
    var platformMode by mutableStateOf(AppSettingsStore.DEFAULT_PLATFORM_MODE)
        private set
    var traceEnabled by mutableStateOf(AppSettingsStore.DEFAULT_TRACE_ENABLED)
        private set

    /** Transient base URL override for OPENAI provider (set from intent, not persisted). */
    var openaiBaseUrl by mutableStateOf("")
        private set

    /** Auth method for OpenAI provider ("oauth" or null). Set from OnboardingStore at startup. */
    var authMethod by mutableStateOf<String?>(null)
        private set

    /** Transient OAuth access token, loaded from OAuthCredentialStore by caller. */
    var openAiOAuthAccessToken by mutableStateOf("")
        private set

    /** True when any credential store failed to initialize encrypted storage. */
    var encryptionDegraded by mutableStateOf(false)
        private set

    fun load() {
        val settings = store.load()
        apiKey = settings.apiKey
        openAiManualApiKey = settings.openAiManualApiKey
        openRouterApiKey = settings.openRouterApiKey
        novitaApiKey = settings.novitaApiKey
        selectedModel = settings.selectedModel
        maxTurns = settings.maxTurns
        debugMode = settings.debugMode
        perceptionMode = settings.perceptionMode
        agentMode = settings.agentMode
        llmBackend = settings.llmBackend
        selectedLocalModelId = settings.localModelId
        localModelSlug = settings.localModelSlug
        localModelQuant = settings.localModelQuant
        executorModel = settings.executorModel
        platformMode = settings.platformMode
        traceEnabled = settings.traceEnabled
        encryptionDegraded = store.encryptionDegraded

        Log.d(
                TAG,
                "Settings loaded: backend=$llmBackend, model=$selectedModel, executorModel=$executorModel, localModel=$selectedLocalModelId, maxTurns=$maxTurns, debugMode=$debugMode, perceptionMode=$perceptionMode, agentMode=$agentMode, platformMode=$platformMode"
        )
    }

    fun updateBackend(backend: LLMBackendType) {
        llmBackend = backend
        store.saveBackend(backend)
        modelLoadingStatus = ModelLoadingStatus.Idle
    }

    fun updateModel(model: String) {
        selectedModel = model
        store.saveModel(model)
    }

    fun updateExecutorModel(value: String?) {
        executorModel = value
        store.saveExecutorModel(value)
    }

    fun updateLocalModel(model: LocalModelOption) {
        selectedLocalModelId = model.id
        localModelSlug = model.modelSlug
        localModelQuant = model.quantizationSlug
        store.saveLocalModel(model.id, model.modelSlug, model.quantizationSlug)
        modelLoadingStatus = ModelLoadingStatus.Idle
    }

    fun updateApiKey(key: String) {
        apiKey = key
        store.saveApiKey(key)
    }

    fun updateOpenRouterApiKey(key: String) {
        openRouterApiKey = key
        store.saveOpenRouterApiKey(key)
    }

    fun updateNovitaApiKey(key: String) {
        novitaApiKey = key
        store.saveNovitaApiKey(key)
    }

    fun updateOpenAiManualApiKey(key: String) {
        openAiManualApiKey = key
        store.saveOpenAiManualApiKey(key)
    }

    fun updateOpenAiOAuthAccessToken(token: String) {
        openAiOAuthAccessToken = token
    }

    fun updateOpenaiBaseUrl(url: String) {
        openaiBaseUrl = url
    }

    fun updateAuthMethod(method: String?) {
        authMethod = method
    }

    /** Merge degraded flag from other credential stores (OAuth, onboarding). */
    fun mergeEncryptionDegraded(otherDegraded: Boolean) {
        if (otherDegraded) encryptionDegraded = true
    }

    /**
     * Build a map of provider env var name → API key for [SessionServices.create].
     *
     * Selects the active OpenAI credential based on [authMethod]:
     * - OAuth: uses transient [openAiOAuthAccessToken]
     * - Manual: uses persisted [openAiManualApiKey]
     * Falls back to legacy [apiKey] during transition (callers not yet updated).
     *
     * Also includes provider base URL overrides with `__BASE_URL_<PROVIDER>` keys,
     * extracted by [SessionLlmBootstrapper] at catalog load time.
     */
    fun buildApiKeys(): Map<String, String> = buildMap {
        val openAiKey = if (authMethod == "oauth") {
            openAiOAuthAccessToken.ifBlank { apiKey }
        } else {
            openAiManualApiKey.ifBlank { apiKey }
        }
        if (openAiKey.isNotBlank()) put("OPENAI_API_KEY", openAiKey)
        if (openRouterApiKey.isNotBlank()) put("OPENROUTER_API_KEY", openRouterApiKey)
        if (novitaApiKey.isNotBlank()) put("NOVITA_API_KEY", novitaApiKey)
        if (openaiBaseUrl.isNotBlank()) put("__BASE_URL_OPENAI", openaiBaseUrl)
        if (authMethod == "oauth") put("__AUTH_METHOD_OPENAI", "oauth")
    }

    /** Build provider → base URL override map from intent-supplied values. */
    fun buildBaseUrlOverrides(): Map<LLMProvider, String> = buildMap {
        if (openaiBaseUrl.isNotBlank()) put(LLMProvider.OPENAI, openaiBaseUrl)
    }

    fun updateMaxTurns(value: Int) {
        maxTurns = value
        store.saveMaxTurns(value)
    }

    fun updateDebugMode(value: Boolean) {
        debugMode = value
        store.saveDebugMode(value)
    }

    fun updateTraceEnabled(value: Boolean) {
        traceEnabled = value
        store.saveTraceEnabled(value)
    }

    fun updatePerceptionMode(value: String) {
        perceptionMode = value
        store.savePerceptionMode(value)
    }

    fun updateAgentMode(value: AgentMode) {
        agentMode = value
        store.saveAgentMode(value)
    }

    fun updateModelLoadingStatus(status: ModelLoadingStatus) {
        modelLoadingStatus = status
    }

    fun updatePlatformMode(value: PlatformMode) {
        platformMode = value
        store.savePlatformMode(value)
    }
}

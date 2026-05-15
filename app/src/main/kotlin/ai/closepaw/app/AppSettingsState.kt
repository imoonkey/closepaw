package ai.closepaw.app

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.settings.LocalModelOption

class AppSettingsState(private val store: AppSettingsStore) {
    companion object {
        private const val TAG = "AppSettingsState"
    }

    var selectedModel by mutableStateOf(AppSettingsStore.DEFAULT_MODEL)
        private set
    var maxTurns by mutableStateOf(AppSettingsStore.DEFAULT_MAX_TURNS)
        private set
    var debugMode by mutableStateOf(AppSettingsStore.DEFAULT_DEBUG_MODE)
        private set
    var perceptionMode by mutableStateOf(AppSettingsStore.DEFAULT_PERCEPTION_MODE)
        private set
    var llmBackend by mutableStateOf(AppSettingsStore.DEFAULT_LLM_BACKEND)
        private set
    var localModel by mutableStateOf<LocalModelOption>(AppSettingsStore.DEFAULT_LOCAL_MODEL)
        private set
    var executorModel by mutableStateOf<String?>(null)
        private set
    var platformMode by mutableStateOf(AppSettingsStore.DEFAULT_PLATFORM_MODE)
        private set
    var traceEnabled by mutableStateOf(AppSettingsStore.DEFAULT_TRACE_ENABLED)
        private set
    var browserScriptEnabled by mutableStateOf(AppSettingsStore.DEFAULT_BROWSER_SCRIPT_ENABLED)
        private set

    /** Base URL override for OPENAI provider (set from intent / Settings; persisted). */
    var openaiBaseUrl by mutableStateOf("")
        private set

    fun load() {
        val settings = store.load()
        selectedModel = settings.selectedModel
        maxTurns = settings.maxTurns
        debugMode = settings.debugMode
        perceptionMode = settings.perceptionMode
        llmBackend = settings.llmBackend
        localModel = settings.localModel
        executorModel = settings.executorModel
        platformMode = settings.platformMode
        traceEnabled = settings.traceEnabled
        browserScriptEnabled = settings.browserScriptEnabled
        openaiBaseUrl = settings.openaiBaseUrl

        Log.d(
                TAG,
                "Settings loaded: backend=$llmBackend, model=$selectedModel, executorModel=$executorModel, localModel=${localModel.id}, maxTurns=$maxTurns, debugMode=$debugMode, perceptionMode=$perceptionMode, platformMode=$platformMode"
        )
    }

    fun updateBackend(backend: LLMBackendType) {
        llmBackend = backend
        store.saveBackend(backend)
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
        localModel = model
        store.saveLocalModel(model)
    }

    fun updateOpenaiBaseUrl(url: String) {
        openaiBaseUrl = url
        store.saveOpenaiBaseUrl(url)
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

    fun updateBrowserScriptEnabled(value: Boolean) {
        browserScriptEnabled = value
        store.saveBrowserScriptEnabled(value)
    }

    fun updatePerceptionMode(value: String) {
        perceptionMode = value
        store.savePerceptionMode(value)
    }

    fun updatePlatformMode(value: PlatformMode) {
        platformMode = value
        store.savePlatformMode(value)
    }
}

package ai.closepaw.app

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.closepaw.llm.ModelCatalogRepositoryHolder
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.settings.LocalModelOption

class AppSettingsState(
    private val store: AppSettingsStore,
    /**
     * Optional hook fired after `otherBaseUrl` / `otherModelId` writes. Lets the
     * caller invalidate the process-wide [ai.closepaw.llm.ModelCatalogRepository]
     * so the synthesized `other-custom` entry reflects the new settings. Pulled
     * out as a constructor parameter so plain JVM tests can substitute it
     * without holding a real Android Context.
     */
    private val onOtherSettingsChanged: () -> Unit = {},
) {
    companion object {
        private const val TAG = "AppSettingsState"

        /**
         * Production wiring: build a state that invalidates the app-singleton
         * [ai.closepaw.llm.ModelCatalogRepository] whenever the OTHER settings
         * change. Done as a factory so production callers don't repeat the
         * holder lookup and tests can use the bare constructor.
         */
        fun create(context: Context): AppSettingsState {
            val appContext = context.applicationContext
            return AppSettingsState(
                store = AppSettingsStore(appContext),
                onOtherSettingsChanged = {
                    ModelCatalogRepositoryHolder.get(appContext).invalidate()
                },
            )
        }
    }

    var selectedModel by mutableStateOf(AppSettingsStore.DEFAULT_MODEL)
        private set
    var debugMode by mutableStateOf(AppSettingsStore.DEFAULT_DEBUG_MODE)
        private set
    var perceptionMode by mutableStateOf(AppSettingsStore.DEFAULT_PERCEPTION_MODE)
        private set
    var llmBackend by mutableStateOf(AppSettingsStore.DEFAULT_LLM_BACKEND)
        private set
    var localModel by mutableStateOf<LocalModelOption>(AppSettingsStore.DEFAULT_LOCAL_MODEL)
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

    /** Base URL for the user-configured OTHER provider (persisted). */
    var otherBaseUrl by mutableStateOf("")
        private set

    /** Model id for the user-configured OTHER provider (persisted). */
    var otherModelId by mutableStateOf("")
        private set

    fun load() {
        val settings = store.load()
        selectedModel = settings.selectedModel
        debugMode = settings.debugMode
        perceptionMode = settings.perceptionMode
        llmBackend = settings.llmBackend
        localModel = settings.localModel
        platformMode = settings.platformMode
        traceEnabled = settings.traceEnabled
        browserScriptEnabled = settings.browserScriptEnabled
        openaiBaseUrl = settings.openaiBaseUrl
        otherBaseUrl = settings.otherBaseUrl
        otherModelId = settings.otherModelId

        Log.d(
                TAG,
                "Settings loaded: backend=$llmBackend, model=$selectedModel, localModel=${localModel.id}, debugMode=$debugMode, perceptionMode=$perceptionMode, platformMode=$platformMode"
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

    fun updateLocalModel(model: LocalModelOption) {
        localModel = model
        store.saveLocalModel(model)
    }

    fun updateOpenaiBaseUrl(url: String) {
        openaiBaseUrl = url
        store.saveOpenaiBaseUrl(url)
    }

    fun updateOtherBaseUrl(url: String) {
        otherBaseUrl = url
        store.saveOtherBaseUrl(url)
        onOtherSettingsChanged()
    }

    fun updateOtherModelId(modelId: String) {
        otherModelId = modelId
        store.saveOtherModelId(modelId)
        onOtherSettingsChanged()
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


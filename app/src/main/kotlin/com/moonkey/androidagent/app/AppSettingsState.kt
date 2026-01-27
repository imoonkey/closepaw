package com.moonkey.androidagent.app

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moonkey.androidagent.protocol.LLMBackendType
import com.moonkey.androidagent.ui.settings.LocalModelOption
import com.moonkey.androidagent.ui.settings.ModelLoadingStatus

/**
 * UI-backed settings state that persists to AppSettingsStore.
 */
class AppSettingsState(
    private val store: AppSettingsStore
) {
    companion object {
        private const val TAG = "AppSettingsState"
    }

    var apiKey by mutableStateOf("")
        private set
    var selectedModel by mutableStateOf(AppSettingsStore.DEFAULT_MODEL)
        private set
    var maxTurns by mutableStateOf(AppSettingsStore.DEFAULT_MAX_TURNS)
        private set
    var debugMode by mutableStateOf(AppSettingsStore.DEFAULT_DEBUG_MODE)
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

    fun load() {
        val settings = store.load()
        apiKey = settings.apiKey
        selectedModel = settings.selectedModel
        maxTurns = settings.maxTurns
        debugMode = settings.debugMode
        llmBackend = settings.llmBackend
        selectedLocalModelId = settings.localModelId
        localModelSlug = settings.localModelSlug
        localModelQuant = settings.localModelQuant

        Log.d(
            TAG,
            "Settings loaded: backend=$llmBackend, model=$selectedModel, localModel=$selectedLocalModelId, maxTurns=$maxTurns, debugMode=$debugMode"
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

    fun updateMaxTurns(value: Int) {
        maxTurns = value
        store.saveMaxTurns(value)
    }

    fun updateDebugMode(value: Boolean) {
        debugMode = value
        store.saveDebugMode(value)
    }

    fun updateModelLoadingStatus(status: ModelLoadingStatus) {
        modelLoadingStatus = status
    }
}

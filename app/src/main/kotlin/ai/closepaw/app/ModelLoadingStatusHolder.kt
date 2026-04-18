package ai.closepaw.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.ui.settings.LocalModelOption
import ai.closepaw.ui.settings.ModelLoadingStatus

/**
 * Transient UI state for the local model loading indicator.
 *
 * Wraps backend / local-model updates so the indicator is always reset to
 * [ModelLoadingStatus.Idle] when the underlying selection changes — keeping
 * the reset centralized regardless of which entry point (UI, intent) drives
 * the change.
 */
class ModelLoadingStatusHolder(private val settings: AppSettingsState) {
    var status by mutableStateOf<ModelLoadingStatus>(ModelLoadingStatus.Idle)
        private set

    fun update(value: ModelLoadingStatus) {
        status = value
    }

    fun updateBackend(backend: LLMBackendType) {
        settings.updateBackend(backend)
        status = ModelLoadingStatus.Idle
    }

    fun updateLocalModel(model: LocalModelOption) {
        settings.updateLocalModel(model)
        status = ModelLoadingStatus.Idle
    }
}

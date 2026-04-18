package ai.closepaw.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.closepaw.ui.settings.ModelLoadingStatus

/** Transient UI state for the local model loading indicator. */
class ModelLoadingStatusHolder {
    var status by mutableStateOf<ModelLoadingStatus>(ModelLoadingStatus.Idle)
        private set

    fun update(value: ModelLoadingStatus) {
        status = value
    }
}

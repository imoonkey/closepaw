package ai.closepaw.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.closepaw.llm.LFMLLMClient
import ai.closepaw.llm.LocalLLMConfig
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.ui.settings.LocalModelOption
import ai.closepaw.ui.settings.ModelLoadingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Transient UI state for the local model loading indicator.
 *
 * Picking a local model in settings kicks off a background download via a
 * disposable [LFMLLMClient]; once the disk cache is warm the runner is
 * released, so a later session boot reuses cached weights without re-downloading.
 */
class ModelLoadingStatusHolder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: AppSettingsState,
) {
    var status by mutableStateOf<ModelLoadingStatus>(ModelLoadingStatus.Idle)
        private set

    private var downloadJob: Job? = null
    private var warmedSlug: String? = null

    fun update(value: ModelLoadingStatus) {
        status = value
    }

    fun updateBackend(backend: LLMBackendType) {
        settings.updateBackend(backend)
        if (backend != LLMBackendType.LOCAL) {
            cancelDownload()
            status = ModelLoadingStatus.Idle
        }
    }

    fun updateLocalModel(model: LocalModelOption) {
        settings.updateLocalModel(model)
        if (warmedSlug == model.modelSlug && status is ModelLoadingStatus.Ready) return
        cancelDownload()
        status = ModelLoadingStatus.Idle
        startDownload(model)
    }

    private fun startDownload(model: LocalModelOption) {
        val cfg = LocalLLMConfig(
            modelSlug = model.modelSlug,
            quantizationSlug = model.quantizationSlug,
        )
        val client = LFMLLMClient(context, cfg)
        downloadJob = scope.launch {
            try {
                client.loadModel { state -> status = state.toUiStatus() }
                warmedSlug = model.modelSlug
            } catch (e: Exception) {
                status = ModelLoadingStatus.Error(e.message ?: "Download failed")
            } finally {
                // Disk cache is warm; release the runner so we don't hold tensor
                // memory until the real session starts.
                runCatching { client.cleanup() }
            }
        }
    }

    private fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        warmedSlug = null
    }
}

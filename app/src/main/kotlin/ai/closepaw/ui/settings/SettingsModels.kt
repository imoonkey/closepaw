package ai.closepaw.ui.settings

import ai.closepaw.llm.ModelEntry

/** Converts ModelEntry list to (id, displayName) pairs for dropdowns. */
fun catalogModelOptions(entries: List<ModelEntry>): List<Pair<String, String>> =
    entries.map { it.name to it.displayName }

/** Local LLM models available in Leap SDK. See LeapDownloadableModel.resolve() for catalog. */
internal val AVAILABLE_LOCAL_MODELS = listOf(
    LocalModelOption(
        id = "LFM2.5-1.2B-Instruct",
        displayName = "LFM 1.2B Instruct (Recommended)",
        modelSlug = "LFM2.5-1.2B-Instruct",
        quantizationSlug = "Q4_K_M",
        description = "On-device inference, ~1.2B parameters, tool-calling support"
    )
)

data class LocalModelOption(
    val id: String,
    val displayName: String,
    val modelSlug: String,
    val quantizationSlug: String,
    val description: String
)

sealed interface ModelLoadingStatus {
    data object Idle : ModelLoadingStatus
    data class Downloading(val progress: Float) : ModelLoadingStatus
    data object Loading : ModelLoadingStatus
    data object Ready : ModelLoadingStatus
    data class Error(val message: String) : ModelLoadingStatus
}

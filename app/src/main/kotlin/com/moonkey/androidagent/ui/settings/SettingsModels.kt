package com.moonkey.androidagent.ui.settings

/**
 * Available cloud LLM models for selection.
 */
internal val AVAILABLE_CLOUD_MODELS = listOf(
    "gpt-5.2" to "GPT-5.2 (Recommended)",
    "gpt-5.2-pro" to "GPT-5.2 Pro (Stronger)"
)

/**
 * Available local LLM models for selection.
 *
 * Note: Only models available in Leap SDK's downloadable model library are listed.
 * Check LeapDownloadableModel.resolve() for available models.
 */
internal val AVAILABLE_LOCAL_MODELS = listOf(
    LocalModelOption(
        id = "LFM2.5-1.2B-Instruct",
        displayName = "LFM 1.2B Instruct (Recommended)",
        modelSlug = "LFM2.5-1.2B-Instruct",
        quantizationSlug = "Q4_K_M",
        description = "On-device inference, ~1.2B parameters, tool-calling support"
    ),
    LocalModelOption(
        id = "lfm2-350m",
        displayName = "LFM 350M",
        modelSlug = "lfm2-350m",
        quantizationSlug = "lfm2-350m-20250710-8da4w",
        description = "On-device inference, ~350M parameters, smaller/faster"
    )
)

/**
 * Local model option data.
 */
data class LocalModelOption(
    val id: String,
    val displayName: String,
    val modelSlug: String,
    val quantizationSlug: String,
    val description: String
)

/**
 * Model loading state for UI display.
 */
sealed interface ModelLoadingStatus {
    data object Idle : ModelLoadingStatus
    data class Downloading(val progress: Float) : ModelLoadingStatus
    data object Loading : ModelLoadingStatus
    data object Ready : ModelLoadingStatus
    data class Error(val message: String) : ModelLoadingStatus
}

/**
 * Available max turns options.
 */
internal val MAX_TURNS_OPTIONS = listOf(10, 20, 50)

package ai.closepaw.perception

/**
 * Controls which perception modalities the agent captures each turn.
 *
 * Sealed class ensures exhaustive when-handling and prevents invalid states.
 * Replaces the boolean enableScreenshotInput in SessionConfig.
 */
sealed class PerceptionConfig {

    /** Accessibility tree only. Current production default. */
    data object AccessibilityOnly : PerceptionConfig()

    /** Screenshot only. For apps with poor a11y support. */
    data class ScreenshotOnly(
        val maxDimension: Int = DEFAULT_MAX_DIMENSION,
        val jpegQuality: Int = DEFAULT_JPEG_QUALITY
    ) : PerceptionConfig()

    /** Both modalities. Richest perception, highest token cost. */
    data class Hybrid(
        val maxDimension: Int = DEFAULT_MAX_DIMENSION,
        val jpegQuality: Int = DEFAULT_JPEG_QUALITY
    ) : PerceptionConfig()

    /** Whether this config exposes accessibility data to the LLM prompt */
    val capturesAccessibility: Boolean get() = this !is ScreenshotOnly

    /** Whether this config captures screenshots */
    val capturesScreenshot: Boolean get() = this !is AccessibilityOnly

    companion object {
        const val DEFAULT_MAX_DIMENSION = 1024
        const val DEFAULT_JPEG_QUALITY = 70
        val DEFAULT: PerceptionConfig = AccessibilityOnly
    }
}

/** Max dimension for screenshot scaling. Uses defaults when config does not capture screenshots. */
val PerceptionConfig.screenshotMaxDimension: Int
    get() = when (this) {
        is PerceptionConfig.ScreenshotOnly -> maxDimension
        is PerceptionConfig.Hybrid -> maxDimension
        is PerceptionConfig.AccessibilityOnly -> PerceptionConfig.DEFAULT_MAX_DIMENSION
    }

/** JPEG quality for screenshot compression. Uses defaults when config does not capture screenshots. */
val PerceptionConfig.screenshotJpegQuality: Int
    get() = when (this) {
        is PerceptionConfig.ScreenshotOnly -> jpegQuality
        is PerceptionConfig.Hybrid -> jpegQuality
        is PerceptionConfig.AccessibilityOnly -> PerceptionConfig.DEFAULT_JPEG_QUALITY
    }

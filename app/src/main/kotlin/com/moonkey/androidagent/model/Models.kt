package com.moonkey.androidagent.model

// --- Geometry Models ---

/**
 * Bounds - Rectangle bounds for UI elements.
 * Uses named properties instead of IntArray for type safety and proper equality.
 */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * Point - 2D coordinate point.
 * Uses named properties instead of IntArray for type safety and proper equality.
 */
data class Point(
    val x: Int,
    val y: Int
)

// --- Perception Models ---

/**
 * ScreenSnapshot - Captured state of the screen.
 * 
 * No longer stores AccessibilityNodeInfo references to avoid memory leaks.
 * All necessary data for action execution is stored in PerceptionElement.
 * 
 * Note: rootOriginal and rawMap have been removed. Actions now use:
 * - Gesture-based clicks using stored bounds/center coordinates
 * - Re-querying accessibility tree for text input when needed
 */
data class ScreenSnapshot(
    val timestamp: Long,
    val elements: List<PerceptionElement>,   // Always present (may be empty)
    val image: ScreenImage? = null,
    val debug: ScreenSnapshotDebug? = null
) {
    /** True when accessibility elements were found */
    val hasElements: Boolean get() = elements.isNotEmpty()

    /** True when a screenshot is available */
    val hasScreenshot: Boolean get() = image != null
}

data class ScreenSnapshotDebug(
        /** Relative path (within trace run folder) to raw accessibility tree JSON */
        val rawA11yTreePath: String? = null,
        /** Relative path (within trace run folder) to Perceptor prompt JSON */
        val sanitizedA11yTreePath: String? = null,
        /** Relative path (within trace run folder) to a screenshot captured for this snapshot (if any) */
        val screenshotPath: String? = null
)

data class ScreenImage(
        val width: Int,
        val height: Int,
        val mimeType: String,
        val bytes: ByteArray,
        val source: ScreenImageSource
) {
    fun toDataUrl(): String {
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return "data:$mimeType;base64,$base64"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenImage) return false

        if (width != other.width) return false
        if (height != other.height) return false
        if (mimeType != other.mimeType) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (source != other.source) return false

        return true
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + source.hashCode()
        return result
    }
}

enum class ScreenImageSource {
    ACCESSIBILITY_SCREENSHOT,
    VIRTUAL_DISPLAY_CAPTURE
}

data class PerceptionElement(
        val index: Int,
        val text: String,
        val resourceId: String,
        val className: String,
        val description: String,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val isEnabled: Boolean,
        val isFocused: Boolean,
        val isLongClickable: Boolean,
        val bounds: Bounds,
        val center: Point
)

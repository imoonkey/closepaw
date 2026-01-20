package com.moonkey.androidagent.domain.models

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
        val elements: List<PerceptionElement> // For LLM and action execution
)

data class PerceptionElement(
        val index: Int,
        val text: String,
        val resourceId: String,
        val className: String,
        val description: String,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val bounds: Bounds,
        val center: Point
)

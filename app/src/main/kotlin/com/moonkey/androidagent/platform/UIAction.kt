package com.moonkey.androidagent.platform

/**
 * UIAction - Platform-agnostic representation of UI actions.
 * 
 * These actions can be executed by any AndroidPlatform implementation,
 * whether real (AccessibilityPlatform) or mock (MockPlatform).
 */
sealed interface UIAction {

    /**
     * Perform ACTION_CLICK on the clickable accessibility node at coordinates.
     *
     * This does not include any gesture fallback. Invocation code
     * can compose retry/fallback policies explicitly.
     */
    data class ClickNodeAt(
        val x: Int,
        val y: Int
    ) : UIAction

    /**
     * Perform a gesture tap at coordinates.
     *
     * This is an explicit atomic tap action used when callers need strict
     * API-level fallback orchestration.
     */
    data class TapAt(
        val x: Int,
        val y: Int
    ) : UIAction
    
    // --- Node-based (AccessibilityNodeInfo.performAction) ---

    /** Find node at (x,y), perform ACTION_LONG_CLICK */
    data class LongClickNodeAt(val x: Int, val y: Int) : UIAction

    /** Find node at (x,y), perform ACTION_SET_TEXT */
    data class SetTextOnNodeAt(
        val x: Int, val y: Int,
        val text: String, val clear: Boolean = false
    ) : UIAction

    /** Find focused editable node, perform ACTION_SET_TEXT */
    data class SetTextOnFocused(
        val text: String, val clear: Boolean = false
    ) : UIAction

    // --- Gesture-based (AccessibilityService.dispatchGesture) ---

    /** Gesture long press (hold) at coordinates for duration */
    data class LongPressAt(
        val x: Int, val y: Int,
        val durationMs: Long
    ) : UIAction
    
    /**
     * Swipe from one point to another.
     * 
     * Note: Scroll functionality is implemented via swipe - use appropriate
     * start/end coordinates to achieve scrolling behavior.
     */
    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long = 300
    ) : UIAction
    
    /**
     * Press a system button.
     */
    data class SystemButton(
        val button: SystemButtonType
    ) : UIAction
    
    /**
     * Wait for a specified duration.
     */
    data class Wait(
        val durationMs: Long
    ) : UIAction
}

/**
 * SystemButtonType - System buttons that can be pressed.
 */
enum class SystemButtonType {
    BACK,
    HOME,
    RECENTS,
    ENTER  // Enter/Return key
}

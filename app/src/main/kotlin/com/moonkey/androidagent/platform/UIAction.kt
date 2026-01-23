package com.moonkey.androidagent.platform

/**
 * UIAction - Platform-agnostic representation of UI actions.
 * 
 * These actions can be executed by any AndroidPlatform implementation,
 * whether real (AccessibilityPlatform) or mock (MockPlatform).
 */
sealed interface UIAction {
    
    /**
     * Click on an element by its index in the screen snapshot.
     */
    data class Click(
        val elementIndex: Int
    ) : UIAction
    
    /**
     * Click at specific screen coordinates.
     */
    data class ClickAt(
        val x: Int,
        val y: Int
    ) : UIAction
    
    /**
     * Long press on an element by its index.
     */
    data class LongClick(
        val elementIndex: Int,
        val durationMs: Long = 1000
    ) : UIAction
    
    /**
     * Type text into a field.
     * 
     * @param text The text to type
     * @param elementIndex Optional element to focus first. If null, types into current focus.
     */
    data class Type(
        val text: String,
        val elementIndex: Int? = null
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


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
     * Type text into an element.
     */
    data class Type(
        val elementIndex: Int,
        val text: String
    ) : UIAction
    
    /**
     * Scroll in a direction.
     */
    data class Scroll(
        val direction: ScrollDirection
    ) : UIAction
    
    /**
     * Swipe from one point to another.
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
 * ScrollDirection - Direction for scroll actions.
 */
enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT
}

/**
 * SystemButtonType - System buttons that can be pressed.
 */
enum class SystemButtonType {
    BACK,
    HOME,
    RECENTS
}


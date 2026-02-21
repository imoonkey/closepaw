package com.moonkey.androidagent.tool.action

/**
 * Central action priority configuration.
 *
 * Each dual-path action's fallback chain order is defined here.
 * To swap priority for any action, just reorder the list entries.
 *
 * Reference: doc/todo/eval_tune/round4/debug5/align/design/design.md
 *
 * Single-path actions not listed here:
 *   - swipe: gesture only (no fallback)
 *   - type: node text path (SetTextOnNodeAt → TapToFocus+SetTextOnFocused)
 */
object ActionPriorityOrder {

    /** click: gesture_tap → node_click */
    val click = listOf(ClickChannel.GESTURE_TAP, ClickChannel.NODE_CLICK)

    /** long_press: gesture_long_press → node_long_click */
    val longPress = listOf(LongPressChannel.GESTURE_LONG_PRESS, LongPressChannel.NODE_LONG_CLICK)

    /** scroll: gesture_swipe → a11y_scroll */
    val scroll = listOf(ScrollChannel.GESTURE_SWIPE, ScrollChannel.A11Y_SCROLL)

    enum class ClickChannel { GESTURE_TAP, NODE_CLICK }
    enum class LongPressChannel { GESTURE_LONG_PRESS, NODE_LONG_CLICK }
    enum class ScrollChannel { GESTURE_SWIPE, A11Y_SCROLL }
}

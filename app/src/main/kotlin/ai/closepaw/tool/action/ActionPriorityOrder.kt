package ai.closepaw.tool.action

/**
 * Central action priority configuration.
 *
 * Each dual-path action's fallback chain order is defined here.
 * To swap priority for any action, just reorder the list entries.
 *
 * Single-path actions not listed here:
 *   - swipe: gesture only (no fallback)
 *   - type: node text path (SetTextOnNodeAt → TapToFocus+SetTextOnFocused)
 */
object ActionPriorityOrder {

    /** click: node_click → gesture_tap */
    val click = listOf(ClickChannel.NODE_CLICK, ClickChannel.GESTURE_TAP)

    /** long_press: node_long_click → gesture_long_press */
    val longPress = listOf(LongPressChannel.NODE_LONG_CLICK, LongPressChannel.GESTURE_LONG_PRESS)

    /** scroll: a11y_scroll → gesture_swipe */
    val scroll = listOf(ScrollChannel.A11Y_SCROLL, ScrollChannel.GESTURE_SWIPE)

    enum class ClickChannel { GESTURE_TAP, NODE_CLICK }
    enum class LongPressChannel { GESTURE_LONG_PRESS, NODE_LONG_CLICK }
    enum class ScrollChannel { GESTURE_SWIPE, A11Y_SCROLL }
}

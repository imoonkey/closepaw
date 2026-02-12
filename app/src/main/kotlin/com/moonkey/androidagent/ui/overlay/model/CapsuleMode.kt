package com.moonkey.androidagent.ui.overlay.model

/**
 * CapsuleMode — the single source of truth for Smart Capsule UI.
 *
 * One value drives the entire capsule rendering.
 * No boolean soup, no ambient state, no side channels.
 * You look at the mode, you know exactly what to draw.
 */
sealed interface CapsuleMode {

    /** Agent is actively executing. Shows thought + [补充][接管][停止]. */
    data class Running(val thought: String) : CapsuleMode

    /** User requested takeover, waiting for current action to finish. */
    data class TakeoverPending(val lastThought: String) : CapsuleMode

    /** User has control. Agent paused. Shows dimmed thought + [补充][继续][停止]. */
    data class Takeover(val lastThought: String) : CapsuleMode

    /** User is typing a supplement message. Transient overlay on previous mode. */
    data class SupplementInput(val previousMode: CapsuleMode) : CapsuleMode

    /** Agent asked a question, waiting for text answer. Capsule expanded. */
    data class WaitingForInput(val question: String, val callId: String) : CapsuleMode

    /** Agent asked user to do something on phone. Capsule expanded. */
    data class WaitingForAction(val instruction: String, val callId: String) : CapsuleMode

    /** Task completed successfully. Auto-hides after 3s. */
    data class Done(val message: String) : CapsuleMode

    /** Error occurred. Stays until dismissed. */
    data class Error(val message: String) : CapsuleMode

    /** No active task. Capsule hidden. */
    data object Hidden : CapsuleMode
}

/**
 * Extract the thought text to display, regardless of mode.
 * Returns null for modes that don't show a thought line.
 */
fun CapsuleMode.displayThought(): String? = when (this) {
    is CapsuleMode.Running -> thought
    is CapsuleMode.TakeoverPending -> lastThought
    is CapsuleMode.Takeover -> lastThought
    is CapsuleMode.SupplementInput -> previousMode.displayThought()
    is CapsuleMode.Done -> "✓ $message"
    is CapsuleMode.Error -> "⚠ $message"
    is CapsuleMode.WaitingForInput -> null // Uses expanded layout
    is CapsuleMode.WaitingForAction -> null // Uses expanded layout
    is CapsuleMode.Hidden -> null
}

/**
 * True if mode shows expanded body (question/instruction) and optional input area.
 */
fun CapsuleMode.isExpanded(): Boolean = when (this) {
    is CapsuleMode.WaitingForInput,
    is CapsuleMode.WaitingForAction,
    is CapsuleMode.SupplementInput -> true
    else -> false
}

/**
 * Sanitize raw agent_thought text for capsule display.
 * Simple and minimal — no hacky adhoc rules.
 */
fun sanitizeThought(raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.length > 40) trimmed.take(40) + "..." else trimmed
}

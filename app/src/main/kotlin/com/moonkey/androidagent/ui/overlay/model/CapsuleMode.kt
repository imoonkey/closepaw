package com.moonkey.androidagent.ui.overlay.model

/**
 * CapsuleMode — the single source of truth for Smart Capsule UI.
 *
 * One value drives the entire capsule rendering.
 * No boolean soup, no ambient state, no side channels.
 * You look at the mode, you know exactly what to draw.
 */
sealed interface CapsuleMode {

    /** Agent is actively executing. Shows thought + controls. */
    data class Running(val thought: String) : CapsuleMode

    /** User requested takeover, waiting for current action to finish. */
    data class TakeoverPending(val lastThought: String) : CapsuleMode

    /** User has control. Agent paused. Shows dimmed thought + [Resume][Stop]. */
    data class Takeover(val lastThought: String) : CapsuleMode

    /** Agent asked a question, waiting for text answer. Capsule expanded. */
    data class WaitingForInput(val question: String, val callId: String) : CapsuleMode

    /** Agent asked user to do something on phone. Capsule expanded. */
    data class WaitingForAction(val instruction: String, val callId: String) : CapsuleMode

    /** Agent needs user approval for a tool action. Shows Allow/Session/Always/Deny buttons. */
    data class WaitingForApproval(
        val callId: String,
        val description: String,    // "Click 'Settings' button"
        val appLabel: String,       // "Chrome" (resolved from packageName)
        val packageName: String?,   // for allow-list persistence; null = hide Session/Always
        val reason: String          // "Unknown app — action requires approval"
    ) : CapsuleMode

    /** Task completed successfully. Auto-hides after 3s. */
    data class Done(val message: String) : CapsuleMode

    /** Error occurred. Stays until dismissed. */
    data class Error(val message: String) : CapsuleMode

    /** No active task. Capsule hidden. */
    data object Hidden : CapsuleMode
}

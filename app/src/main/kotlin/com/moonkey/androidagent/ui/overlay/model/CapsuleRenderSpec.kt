package com.moonkey.androidagent.ui.overlay.model

import com.moonkey.androidagent.protocol.PlatformMode

/**
 * CapsuleRenderSpec — pure rendering specification derived from CapsuleMode.
 *
 * Maps CapsuleMode → visual properties. Both View (SmartCapsuleRenderer) and
 * Compose (SmartCapsuleCompose) read from this spec. This is the SINGLE source
 * of truth for "what does the capsule look like in each mode."
 *
 * No business logic. No callbacks. No state management.
 * Just data that renderers mechanically apply to their UI framework.
 */
data class CapsuleRenderSpec(
    val dot: DotSpec?,
    val thought: ThoughtSpec,
    val expandedBody: String?,
    val buttons: ButtonsSpec,
    val row3: Row3Spec?,
) {
    /** Status dot configuration. null = dot hidden. */
    data class DotSpec(val color: Int, val pulsing: Boolean)

    /** Thought line (Row 1 text). */
    data class ThoughtSpec(val text: String, val alpha: Float = 1f)

    /** A single button in Row 2. */
    data class ButtonSpec(val icon: String, val text: String, val enabled: Boolean = true)

    /** Row 2 button configuration. null fields = button hidden. */
    data class ButtonsSpec(val primary: ButtonSpec?, val stop: ButtonSpec?)

    /** Row 3 (input + action button). null = entire row hidden. */
    data class Row3Spec(
        val hint: String,
        val buttonText: String,
        val clearInput: Boolean = false,
    )

    companion object {
        /**
         * Derive the render spec from a CapsuleMode.
         * [previousMode] is used to decide whether to clear the input field
         * on transitions into WaitingForInput.
         */
        fun from(
            mode: CapsuleMode,
            previousMode: CapsuleMode? = null,
            isStopPending: Boolean = false,
        ): CapsuleRenderSpec =
            when (mode) {
                is CapsuleMode.Running -> CapsuleRenderSpec(
                    dot = DotSpec(CapsuleColors.BLUE, pulsing = true),
                    thought = ThoughtSpec(mode.thought.ifEmpty { "Thinking..." }),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec("✋", "Takeover"),
                        stop = stopButtonSpec(isStopPending),
                    ),
                    row3 = Row3Spec("Got ideas? Add a note...", "Add note"),
                )

                is CapsuleMode.TakeoverPending -> CapsuleRenderSpec(
                    dot = DotSpec(CapsuleColors.AMBER, pulsing = false),
                    thought = ThoughtSpec("Handing over..."),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec("✋", "Handing over", enabled = false),
                        stop = stopButtonSpec(isStopPending),
                    ),
                    row3 = Row3Spec("Got ideas? Add a note...", "Add note"),
                )

                is CapsuleMode.Takeover -> CapsuleRenderSpec(
                    dot = DotSpec(CapsuleColors.AMBER, pulsing = false),
                    thought = ThoughtSpec(
                        mode.lastThought.ifEmpty { "Paused" },
                        alpha = 0.6f,
                    ),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec("▶", "Resume"),
                        stop = stopButtonSpec(isStopPending),
                    ),
                    row3 = Row3Spec("Got ideas? Add a note...", "Add note"),
                )

                is CapsuleMode.WaitingForInput -> CapsuleRenderSpec(
                    dot = null,
                    thought = ThoughtSpec("💬 Awaiting response"),
                    expandedBody = mode.question,
                    buttons = ButtonsSpec(
                        primary = null,
                        stop = stopButtonSpec(isStopPending),
                    ),
                    row3 = Row3Spec(
                        hint = "Type your response...",
                        buttonText = "Send →",
                        clearInput = previousMode != null
                            && previousMode !is CapsuleMode.WaitingForInput,
                    ),
                )

                is CapsuleMode.WaitingForAction -> CapsuleRenderSpec(
                    dot = null,
                    thought = ThoughtSpec("✋ Action needed"),
                    expandedBody = mode.instruction,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec("✅", "Done"),
                        stop = stopButtonSpec(isStopPending),
                    ),
                    row3 = null,
                )

                is CapsuleMode.Done -> CapsuleRenderSpec(
                    dot = DotSpec(CapsuleColors.TEAL, pulsing = false),
                    thought = ThoughtSpec("✓ ${mode.message}"),
                    expandedBody = null,
                    buttons = ButtonsSpec(primary = null, stop = null),
                    row3 = null,
                )

                is CapsuleMode.Error -> CapsuleRenderSpec(
                    dot = DotSpec(CapsuleColors.RED, pulsing = false),
                    thought = ThoughtSpec("⚠ ${mode.message}"),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = null,
                        stop = ButtonSpec("✕", "Close"),
                    ),
                    row3 = null,
                )

                is CapsuleMode.Hidden -> CapsuleRenderSpec(
                    dot = null,
                    thought = ThoughtSpec(""),
                    expandedBody = null,
                    buttons = ButtonsSpec(primary = null, stop = null),
                    row3 = Row3Spec("What can I help you with?", "Send →"),
                )
            }

        private fun stopButtonSpec(isStopPending: Boolean): ButtonSpec =
            if (isStopPending) ButtonSpec("⏹", "Stopping...", enabled = false)
            else ButtonSpec("⏹", "Stop")
    }
}

/**
 * NavSpec — navigation button visibility derived from context.
 *
 * Separate from CapsuleRenderSpec because nav visibility depends on
 * CapsuleContext + PlatformMode, not CapsuleMode.
 */
data class NavSpec(
    val showMinimize: Boolean,
    val showApp: Boolean,
    val showWatch: Boolean,
) {
    companion object {
        fun from(
            context: CapsuleContext,
            platformMode: PlatformMode,
            hasIsland: Boolean,
            mode: CapsuleMode? = null,
        ): NavSpec = NavSpec(
            showMinimize = platformMode == PlatformMode.VIRTUAL_DISPLAY
                && hasIsland
                && context != CapsuleContext.MAIN_APP
                && mode !is CapsuleMode.WaitingForInput
                && mode !is CapsuleMode.WaitingForAction
                && mode !is CapsuleMode.Error,
            showApp = context != CapsuleContext.MAIN_APP
                && platformMode != PlatformMode.ACCESSIBILITY,
            showWatch = platformMode != PlatformMode.ACCESSIBILITY
                && context != CapsuleContext.SCREEN_VIEWING,
        )
    }
}

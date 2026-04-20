package ai.closepaw.ui.overlay.model

import ai.closepaw.protocol.PlatformMode

/**
 * CapsuleRenderSpec — pure rendering specification derived from CapsuleMode.
 *
 * Maps CapsuleMode → visual properties. Both overlay and in-app Compose renderers
 * read from this spec. This is the SINGLE source of truth for "what does the
 * capsule look like in each mode."
 *
 * No business logic. No callbacks. No state management.
 * Just data that renderers mechanically apply to their UI framework.
 */
data class CapsuleRenderSpec(
    val dot: DotSpec?,
    val thought: ThoughtSpec,
    val expandedBody: String?,
    val buttons: ButtonsSpec,
    val input: InputSpec?,
) {
    /** Status dot configuration. null = dot hidden. */
    data class DotSpec(val status: GlowState, val pulsing: Boolean)

    /** Status-line text (the agent's thought). */
    data class ThoughtSpec(val text: String, val dimmed: Boolean = false)

    /** A single button in the control bar. The Compose layer chooses the icon. */
    data class ButtonSpec(val text: String, val enabled: Boolean = true)

    /** Control-bar button configuration. null fields = button hidden. */
    data class ButtonsSpec(
        val primary: ButtonSpec?,
        val secondary: ButtonSpec? = null,
        val tertiary: ButtonSpec? = null,
        val stop: ButtonSpec?,
    )

    /** Input-bar specification. null = input bar hidden. */
    data class InputSpec(
        val hint: String,
        val submitLabel: String,
        val clearDraft: Boolean = false,
    )

    companion object {
        /**
         * Derive the render spec from a CapsuleMode.
         * [previousMode] is used to decide whether to clear the input-bar draft
         * on transitions into WaitingForInput.
         */
        fun from(
            mode: CapsuleMode,
            previousMode: CapsuleMode? = null,
            isStopPending: Boolean = false,
        ): CapsuleRenderSpec =
            when (mode) {
                is CapsuleMode.Running -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Active, pulsing = true),
                    thought = ThoughtSpec(mode.thought.ifEmpty { "Thinking..." }),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec("Takeover"),
                        stop = stopButtonSpec(isStopPending),
                    ),
                    input = InputSpec("Got ideas? Add a note...", "Add note"),
                )

                is CapsuleMode.TakeoverPending -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Paused, pulsing = false),
                    thought = ThoughtSpec("Handing over..."),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec("Handing over", enabled = false),
                        stop = stopButtonSpec(isStopPending),
                    ),
                    input = InputSpec("Got ideas? Add a note...", "Add note"),
                )

                is CapsuleMode.Takeover -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Paused, pulsing = false),
                    thought = ThoughtSpec(
                        mode.lastThought.ifEmpty { "Paused" },
                        dimmed = true,
                    ),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec("Resume"),
                        stop = stopButtonSpec(isStopPending),
                    ),
                    input = InputSpec("Got ideas? Add a note...", "Add note"),
                )

                is CapsuleMode.WaitingForInput -> CapsuleRenderSpec(
                    dot = null,
                    thought = ThoughtSpec("Awaiting response"),
                    expandedBody = mode.question,
                    buttons = ButtonsSpec(
                        primary = null,
                        stop = stopButtonSpec(isStopPending),
                    ),
                    input = InputSpec(
                        hint = "Type your response...",
                        submitLabel = "Send",
                        clearDraft = previousMode != null
                            && previousMode !is CapsuleMode.WaitingForInput,
                    ),
                )

                is CapsuleMode.WaitingForAction -> CapsuleRenderSpec(
                    dot = null,
                    thought = ThoughtSpec("Action needed"),
                    expandedBody = mode.instruction,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec("Done"),
                        stop = stopButtonSpec(isStopPending),
                    ),
                    input = null,
                )

                is CapsuleMode.WaitingForApproval -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Paused, pulsing = false),
                    thought = ThoughtSpec("Approve action?"),
                    expandedBody = "${mode.description}\n${mode.appLabel} · ${mode.reason}",
                    buttons = ButtonsSpec(
                        primary = ButtonSpec("Allow"),
                        secondary = if (mode.packageName != null) ButtonSpec("Session") else null,
                        tertiary = if (mode.packageName != null) ButtonSpec("Always") else null,
                        stop = ButtonSpec("Deny"),
                    ),
                    input = null,
                )

                is CapsuleMode.Done -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Success, pulsing = false),
                    thought = ThoughtSpec(mode.message),
                    expandedBody = null,
                    buttons = ButtonsSpec(primary = null, stop = null),
                    input = null,
                )

                is CapsuleMode.Error -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Error, pulsing = false),
                    thought = ThoughtSpec(mode.message),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = null,
                        stop = ButtonSpec("Close"),
                    ),
                    input = null,
                )

                is CapsuleMode.Hidden -> CapsuleRenderSpec(
                    dot = null,
                    thought = ThoughtSpec(""),
                    expandedBody = null,
                    buttons = ButtonsSpec(primary = null, stop = null),
                    input = InputSpec("What can I help you with?", "Send"),
                )
            }

        private fun stopButtonSpec(isStopPending: Boolean): ButtonSpec =
            if (isStopPending) ButtonSpec("Stopping...", enabled = false)
            else ButtonSpec("Stop")
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
        ): NavSpec {
            // Done mode hides the entire control bar, so its nav cluster must also hide.
            val controlBarHidden = mode is CapsuleMode.Done

            return NavSpec(
                showMinimize = !controlBarHidden
                    && hasIsland
                    && context != CapsuleContext.MAIN_APP
                    && mode !is CapsuleMode.WaitingForInput
                    && mode !is CapsuleMode.WaitingForAction
                    && mode !is CapsuleMode.WaitingForApproval
                    && mode !is CapsuleMode.Error,
                showApp = !controlBarHidden
                    && context != CapsuleContext.MAIN_APP
                    && platformMode != PlatformMode.ACCESSIBILITY,
                showWatch = !controlBarHidden
                    && platformMode != PlatformMode.ACCESSIBILITY
                    && context != CapsuleContext.SCREEN_VIEWING,
            )
        }
    }
}

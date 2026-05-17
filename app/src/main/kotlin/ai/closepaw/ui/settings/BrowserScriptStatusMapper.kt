package ai.closepaw.ui.settings

/**
 * Possible CDP probe states the unified card needs to render. Wraps [ChromeCdpProbe.Result]
 * with an additional [Probing] state for the brief window the probe coroutine is in flight.
 * Lives in the mapper file (not the probe class) because it's a display concern.
 */
internal sealed interface BrowserScriptProbeState {
    data object Probing : BrowserScriptProbeState
    data object Bound : BrowserScriptProbeState
    data object NotBound : BrowserScriptProbeState
    data object Unknown : BrowserScriptProbeState
}

/**
 * What the card's row-tap should do, computed by the mapper so the composable doesn't need
 * to re-derive it from raw state. Null in [BrowserScriptStatusResult.rowAction] means the
 * row is non-interactive (the Switch is still tappable independently).
 */
internal sealed class RowAction {
    /** Wipe the inline gate error and re-attempt enabling — mirrors the Switch's tap contract. */
    data object ClearErrorAndRetry : RowAction()
    /** Explicit no-op; mappers may emit null instead to mean the same thing. */
    data object None : RowAction()
}

internal data class BrowserScriptStatusResult(
    val status: ToolStatusUi,
    val rowAction: RowAction?,
)

/**
 * Pure mapper: collapse the four observable inputs (enable pref, gate pending flag, gate
 * error, CDP probe result) into the unified card's [ToolStatusUi] plus an optional row
 * action. Mapping table is the source of truth in `design_claude.md`.
 *
 * Probe state is ignored when the pref is off or the gate is busy/errored — those states
 * always win because the probe result is meaningless if the tool isn't actually enabled.
 */
internal fun browserScriptStatusUi(
    enabledPref: Boolean,
    gatePending: Boolean,
    gateError: BrowserScriptToggleError?,
    probeResult: BrowserScriptProbeState,
): BrowserScriptStatusResult {
    if (gatePending) {
        return BrowserScriptStatusResult(
            status = ToolStatusUi(
                label = "Setting up…",
                subtitle = "Checking Shizuku…",
                tone = ToolStatusTone.Neutral,
                showSpinner = true,
            ),
            rowAction = null,
        )
    }
    if (gateError != null) {
        return BrowserScriptStatusResult(
            status = ToolStatusUi(
                label = "Needs Setup",
                subtitle = gateError.message(),
                tone = ToolStatusTone.Warning,
                showSpinner = false,
            ),
            rowAction = RowAction.ClearErrorAndRetry,
        )
    }
    if (!enabledPref) {
        return BrowserScriptStatusResult(
            status = ToolStatusUi(
                label = "Disabled",
                subtitle = "Toggle to enable",
                tone = ToolStatusTone.Neutral,
                showSpinner = false,
            ),
            rowAction = null,
        )
    }
    return when (probeResult) {
        BrowserScriptProbeState.Probing -> BrowserScriptStatusResult(
            status = ToolStatusUi(
                label = "Checking…",
                subtitle = "Checking Chrome devtools socket…",
                tone = ToolStatusTone.Neutral,
                showSpinner = true,
            ),
            rowAction = null,
        )
        BrowserScriptProbeState.Bound -> BrowserScriptStatusResult(
            status = ToolStatusUi(
                label = "Ready",
                subtitle = "browser_script active",
                tone = ToolStatusTone.Positive,
                showSpinner = false,
            ),
            rowAction = null,
        )
        BrowserScriptProbeState.NotBound -> BrowserScriptStatusResult(
            status = ToolStatusUi(
                label = "Needs Setup",
                subtitle = "Chrome devtools socket not exposed. Enable the flag and restart Chrome.",
                tone = ToolStatusTone.Warning,
                showSpinner = false,
            ),
            rowAction = null,
        )
        BrowserScriptProbeState.Unknown -> BrowserScriptStatusResult(
            status = ToolStatusUi(
                label = "Not available",
                subtitle = "Cannot probe socket on this device — the agent will still try to connect when needed.",
                tone = ToolStatusTone.Warning,
                showSpinner = false,
            ),
            rowAction = null,
        )
    }
}

package ai.closepaw.ui.overlay.model

import ai.closepaw.protocol.TurnPhase

/**
 * GlowState — semantic status for capsule status dot, edge glow, and status island.
 *
 * Carries no visual values. Compose renderers map this to theme colors at render time.
 */
enum class GlowState {
    Active,
    Executing,
    Success,
    Error,
    Paused,
}

/**
 * Derive GlowState from CapsuleMode + TurnPhase.
 *
 * Eliminates the parallel GlowState state machine — no manual tracking needed.
 * Call this whenever you need the current glow state.
 */
fun deriveGlowState(mode: CapsuleMode, turnPhase: TurnPhase?): GlowState = when {
    mode is CapsuleMode.Error -> GlowState.Error
    mode is CapsuleMode.Done -> GlowState.Success
    mode is CapsuleMode.TakeoverPending || mode is CapsuleMode.Takeover -> GlowState.Paused
    mode is CapsuleMode.WaitingForInput || mode is CapsuleMode.WaitingForAction ||
        mode is CapsuleMode.WaitingForApproval -> GlowState.Paused
    mode is CapsuleMode.Running && turnPhase == TurnPhase.EXECUTION -> GlowState.Executing
    mode is CapsuleMode.Running -> GlowState.Active
    else -> GlowState.Active
}

package com.moonkey.androidagent.ui.overlay.model

import com.moonkey.androidagent.protocol.TurnPhase

/**
 * GlowState — visual states for the edge glow effect.
 *
 * Each state maps to a color. Colors are defined here (not in CapsuleColors)
 * because glow uses slightly different shades for visibility on the edge.
 */
enum class GlowState(val colorHex: Int) {
    Active(CapsuleColors.BLUE),
    Executing(0xFF3B82F6.toInt()),  // Lighter blue for execution
    Success(CapsuleColors.TEAL),
    Error(0xFFDC2626.toInt()),      // Brighter red for glow visibility
    Paused(CapsuleColors.AMBER),
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
    mode is CapsuleMode.WaitingForInput || mode is CapsuleMode.WaitingForAction -> GlowState.Paused
    mode is CapsuleMode.Running && turnPhase == TurnPhase.EXECUTION -> GlowState.Executing
    mode is CapsuleMode.Running -> GlowState.Active
    else -> GlowState.Active
}

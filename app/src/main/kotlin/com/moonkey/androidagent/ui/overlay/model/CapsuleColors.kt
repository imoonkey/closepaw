package com.moonkey.androidagent.ui.overlay.model

/**
 * CapsuleColors — centralized color constants for Smart Capsule UI.
 *
 * Used by CapsuleRenderSpec, EdgeGlowManager, StatusIslandManager,
 * and SmartCapsuleLayoutBuilder. One place to change the palette.
 */
object CapsuleColors {
    /** Running / Active — Blue */
    const val BLUE = 0xFF2563EB.toInt()

    /** Takeover / Paused — Amber */
    const val AMBER = 0xFFF59E0B.toInt()

    /** Done / Success — Teal */
    const val TEAL = 0xFF0D9488.toInt()

    /** Error — Red */
    const val RED = 0xFFEF4444.toInt()

    /** Executing (glow only) — Purple */
    const val PURPLE = 0xFF7C3AED.toInt()
}

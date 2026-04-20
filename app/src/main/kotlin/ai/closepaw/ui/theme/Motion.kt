package ai.closepaw.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// D1 §5: four durations, two easings, no springs. Every motion call site picks
// one of these primitives — no ad-hoc timing constants in feature code.
object ClosePawMotion {

    // Durations (ms)
    const val Quick: Int = 120
    const val Standard: Int = 240
    const val Pulse: Int = 480
    const val Breath: Int = 900

    // Easings
    val EaseInOutSine: Easing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
    val EaseOutCubic: Easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

    // Named primitives — one per real surface need.
    // Map directly onto the contract in design_aligned.md §2.
    const val StatusFlip: Int = Quick           // 120ms status/glyph flip
    const val TraceEnter: Int = Standard        // 240ms trace-item enter
    const val RowExpand: Int = Standard         // 240ms row expand/collapse
    const val PageSlide: Int = Standard         // 240ms settings page slide
    const val SurfaceSwap: Int = Standard       // 240ms surface/content/color transition
    const val CursorBlink: Int = Pulse          // 480ms cursor blink
    const val ThinkingPulse: Int = Pulse        // 480ms thinking pulse
    const val OverlayFadeOut: Int = Pulse       // 480ms overlay fade-out
    const val CapsuleBreath: Int = Breath       // 900ms capsule breath in running mode
    const val GlowPulse: Int = Breath           // 900ms glow pulse

    // D1 §8 reduced-motion contract:
    //  - trace enter → instant + 120ms fade
    //  - collapse/expand → instant
    //  - capsule breath → static paw at full alpha
    //  - looping decorative motion (glow, thinking pulse) → paused
    //  - streaming cursor → keeps blinking (liveness signal)
    //
    // Each motion call site reads this once and chooses; there is no global wrapper
    // that mutates every transition.
    @Composable
    fun reducedMotion(): Boolean {
        val context = LocalContext.current
        return remember(context) {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }
    }
}

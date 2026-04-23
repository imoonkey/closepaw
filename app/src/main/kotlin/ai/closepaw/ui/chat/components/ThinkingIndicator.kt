package ai.closepaw.ui.chat.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.closepaw.ui.theme.ClosePawMotion
import ai.closepaw.ui.theme.closePaw

/**
 * ThinkingIndicator — paw-toe sequence (motion spec §4). Three toes + pad
 * fill cumulatively over a 900ms cycle (225ms phase boundaries), then reset.
 * Ink tint, alpha-only animation (30% → 100%); no scale, no Claw color.
 */
@Composable
fun ThinkingIndicator(modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.closePaw.spacing
    Row(
        modifier = modifier
            .testTag("qa-thinking-indicator")
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Thinking"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        PawToeSequence(tint = THINKING_INDICATOR_TINT.resolve())
        Text(
            text = "Thinking…",
            style = MaterialTheme.closePaw.bodyItalic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Color role used by [ThinkingIndicator]. Pinned by ThinkingIndicatorTintTest. */
internal enum class ThinkingTintRole {
    OnSurface;

    @Composable
    fun resolve(): Color = when (this) {
        OnSurface -> MaterialTheme.colorScheme.onSurface
    }
}

internal val THINKING_INDICATOR_TINT: ThinkingTintRole = ThinkingTintRole.OnSurface

@Composable
private fun PawToeSequence(tint: Color) {
    val reduced = ClosePawMotion.reducedMotion()
    val phase = if (reduced) {
        // D1 §8: looping decoration paused → static full paw at full alpha.
        ELEMENT_COUNT.toFloat()
    } else {
        val transition = rememberInfiniteTransition(label = "paw-thinking")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = ELEMENT_COUNT.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(ClosePawMotion.Breath, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )
        animated
    }
    Canvas(modifier = Modifier.size(28.dp)) {
        // Cumulative fill per spec §4: element lights at its phase and stays
        // lit until the 900ms reset.
        fun alphaFor(index: Int) = pawToeAlpha(phase, index)
        val s = size.minDimension / 64f  // 64x64 design viewport.
        // Order: toe₁ → toe₂ → toe₃ → pad.
        // toe₁ — left
        drawOval(
            color = tint.copy(alpha = alphaFor(0)),
            topLeft = Offset(11f * s, 8f * s),
            size = Size(11f * s, 14f * s),
        )
        // toe₂ — center
        drawOval(
            color = tint.copy(alpha = alphaFor(1)),
            topLeft = Offset(26.5f * s, 0f),
            size = Size(11f * s, 14f * s),
        )
        // toe₃ — right
        drawOval(
            color = tint.copy(alpha = alphaFor(2)),
            topLeft = Offset(42f * s, 8f * s),
            size = Size(11f * s, 14f * s),
        )
        // pad
        drawOval(
            color = tint.copy(alpha = alphaFor(3)),
            topLeft = Offset(16f * s, 28f * s),
            size = Size(32f * s, 26f * s),
        )
    }
}

private const val ELEMENT_COUNT = 4

/** Alpha for paw-toe `index` at animation `phase` ∈ [0, ELEMENT_COUNT].
 *  Active and prior elements at full alpha (1.0); not-yet-active at 0.30.
 *  Pinned by ThinkingIndicatorCadenceTest. */
internal fun pawToeAlpha(phase: Float, index: Int): Float {
    val active = phase.toInt().coerceIn(0, ELEMENT_COUNT - 1)
    return if (index <= active) 1.0f else 0.30f
}

internal const val PAW_TOE_ELEMENT_COUNT = ELEMENT_COUNT

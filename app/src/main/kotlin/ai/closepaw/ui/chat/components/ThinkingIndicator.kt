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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.closepaw.ui.theme.ClosePawMotion
import ai.closepaw.ui.theme.closePaw

/**
 * ThinkingIndicator — line-art paw matching [R.drawable.ic_paw] / logo.
 * Body (C-spiral) always full alpha; 4 toes light cumulatively over 900ms
 * (225ms phase boundaries), then reset. Ink tint, alpha-only animation
 * (30% → 100%); no scale, no Claw color.
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
    // 52×64 design viewport (matches ic_paw.xml); preserve aspect.
    Canvas(modifier = Modifier.size(width = 23.dp, height = 28.dp)) {
        val s = size.height / 64f
        val stroke = Stroke(
            width = 3.4f * s,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )

        // Body (C-spiral) — always fully lit.
        val body = Path().apply {
            moveTo(33.1f * s, 58.8f * s)
            cubicTo(29.8f * s, 61.7f * s, 25.5f * s, 63.4f * s, 20.9f * s, 62.4f * s)
            cubicTo(12.4f * s, 60.6f * s, 7.4f * s, 52.7f * s, 7.8f * s, 43.7f * s)
            cubicTo(8.3f * s, 33.9f * s, 15.2f * s, 26.2f * s, 25.7f * s, 25.1f * s)
            cubicTo(35.8f * s, 24.1f * s, 41.7f * s, 31.6f * s, 40.7f * s, 40.3f * s)
            cubicTo(39.9f * s, 47.2f * s, 34.1f * s, 51.6f * s, 28.6f * s, 49.8f * s)
            cubicTo(27.3f * s, 49.4f * s, 26.3f * s, 48.7f * s, 25.6f * s, 48.3f * s)
        }
        drawPath(body, tint, style = stroke)

        // 4 toes — phased left → right.
        fun toe(cx: Float, cy: Float, rx: Float, ry: Float, alpha: Float, rotDeg: Float = 0f) {
            val draw = {
                drawOval(
                    color = tint.copy(alpha = alpha),
                    topLeft = Offset((cx - rx) * s, (cy - ry) * s),
                    size = Size(2 * rx * s, 2 * ry * s),
                    style = stroke,
                )
            }
            if (rotDeg != 0f) {
                rotate(rotDeg, pivot = Offset(cx * s, cy * s)) { draw() }
            } else {
                draw()
            }
        }
        toe(5.85f, 19.45f, 4.35f, 6.95f, pawToeAlpha(phase, 0), rotDeg = -18f)
        toe(18.3f, 9.15f, 4.35f, 8.05f, pawToeAlpha(phase, 1))
        toe(33.6f, 9.15f, 4.35f, 8.05f, pawToeAlpha(phase, 2))
        toe(45.95f, 19.45f, 4.35f, 6.95f, pawToeAlpha(phase, 3), rotDeg = 18f)
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

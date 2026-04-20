package ai.closepaw.ui.chat.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
    Surface(
        modifier = modifier.testTag("qa-thinking-indicator"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm)) {
            PawToeSequence(tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun PawToeSequence(tint: Color) {
    val transition = rememberInfiniteTransition(label = "paw-thinking")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = ELEMENT_COUNT.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(ClosePawMotion.Breath, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    Canvas(modifier = Modifier.size(28.dp)) {
        val active = phase.toInt().coerceIn(0, ELEMENT_COUNT - 1)
        // Cumulative fill per spec §4: element lights at its phase and stays
        // lit until the 900ms reset.
        fun alphaFor(index: Int) = if (index <= active) 1.0f else 0.30f
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

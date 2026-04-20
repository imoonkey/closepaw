package ai.closepaw.ui.chat.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ai.closepaw.ui.theme.ClosePawMotion
import ai.closepaw.ui.theme.closePaw

/**
 * ThinkingIndicator — three pulsing dots shown before any content arrives.
 * Uses the shared [ClosePawMotion.ThinkingPulse] cadence (480ms).
 */
@Composable
fun ThinkingIndicator(modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.closePaw.spacing
    Surface(
        modifier = modifier.testTag("qa-thinking-indicator"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs + 2.dp)
        ) {
            repeat(3) { index ->
                AnimatedDot(index = index)
            }
        }
    }
}

@Composable
private fun AnimatedDot(index: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "dot$index")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = ClosePawMotion.ThinkingPulse,
                delayMillis = index * (ClosePawMotion.ThinkingPulse / 3)
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha$index"
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
    )
}

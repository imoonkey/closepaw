package ai.closepaw.ui.capsule.surface

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import ai.closepaw.R
import ai.closepaw.ui.theme.ClosePawMotion

// D1 §6.2: paw glyph replaces the generic status dot on capsule + island.
// Tinted by semantic status color; pulsing == true drives the 900ms breath
// (alpha-only, paused under reduced-motion to satisfy D1 §8).
@Composable
fun StatusPawGlyph(
    color: Color,
    size: Dp,
    pulsing: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val reduced = ClosePawMotion.reducedMotion()
    val alpha = if (pulsing && !reduced) {
        val transition = rememberInfiniteTransition(label = "pawBreath")
        val animated by transition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(ClosePawMotion.CapsuleBreath, easing = ClosePawMotion.EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pawBreathAlpha",
        )
        animated
    } else {
        1f
    }
    Image(
        painter = painterResource(R.drawable.ic_paw),
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier
            .size(size)
            .alpha(alpha),
    )
}

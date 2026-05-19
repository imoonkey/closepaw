package ai.closepaw.ui.chat.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Lucide
import ai.closepaw.R
import ai.closepaw.ui.theme.ClosePawMotion
import ai.closepaw.ui.theme.Fraunces
import ai.closepaw.ui.theme.closePaw
import kotlinx.coroutines.delay

private data class Suggestion(val verb: String, val gloss: String) {
    val full: String get() = "$verb $gloss"
}

private val SUGGESTIONS = listOf(
    Suggestion("Get me", "a ride home"),
    Suggestion("Catch me up", "on emails and messages"),
    Suggestion("Search", "for nearby restaurants"),
)

/**
 * EmptyState — Bound Edition first page. The 240dp paw bleeds from the top-right
 * corner; the question and marginalia suggestions sit in the lower-left third,
 * separated by a 64dp hairline rule. Reveal staggers at 80ms intervals over
 * `TraceEnter`; reduced-motion renders everything immediately.
 */
@Composable
fun EmptyState(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.closePaw.spacing
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        // Paw glyph is rendered by ChatScreen at the outer Box layer so it can
        // bleed up through the masthead band (transparent ChatHeader sits on
        // top, "ClosePaw" wordmark stays readable). Kept out of this composable
        // to avoid Scaffold-content clipping.

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.xl),
        ) {
            StaggeredReveal(index = 0) {
                Text(
                    text = "What can I\nhelp you with?",
                    style = TextStyle(
                        fontFamily = Fraunces,
                        fontStyle = FontStyle.Italic,
                        fontSize = 32.sp,
                        lineHeight = 38.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.size(spacing.md))

            StaggeredReveal(index = 1) {
                HorizontalDivider(
                    modifier = Modifier.width(64.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(Modifier.size(spacing.md))

            SUGGESTIONS.forEachIndexed { i, s ->
                StaggeredReveal(index = 2 + i) {
                    MarginaliaSuggestion(
                        verb = s.verb,
                        gloss = s.gloss,
                        onClick = { onSuggestionClick(s.full) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MarginaliaSuggestion(
    verb: String,
    gloss: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.closePaw.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Lucide.ArrowRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.closePaw.inkFaint,
        )
        Spacer(Modifier.width(spacing.sm))
        Column {
            Text(
                text = verb,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = gloss,
                style = MaterialTheme.closePaw.bodyItalic,
                color = MaterialTheme.closePaw.inkFaint,
            )
        }
    }
}

// 4-step choreography per motion.md: each index waits 80ms longer than the
// previous, then fades + slides 8dp into place over `TraceEnter`. Reduced-motion
// short-circuits to immediate render.
@Composable
private fun StaggeredReveal(
    index: Int,
    content: @Composable () -> Unit,
) {
    if (ClosePawMotion.reducedMotion()) {
        content()
        return
    }
    val offsetPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 80L)
        revealed = true
    }
    val progress by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(ClosePawMotion.TraceEnter, easing = ClosePawMotion.EaseOutCubic),
        label = "StaggeredReveal",
    )
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * offsetPx
        },
    ) {
        content()
    }
}

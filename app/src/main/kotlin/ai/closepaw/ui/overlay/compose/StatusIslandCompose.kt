package ai.closepaw.ui.overlay.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.closepaw.protocol.compactThought
import ai.closepaw.ui.capsule.surface.StatusPawGlyph
import ai.closepaw.ui.theme.ClosePawMotion
import ai.closepaw.ui.theme.closePaw

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatusIslandCompose(
    text: String,
    dotColor: Color,
    pulsing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.large
    val spacing = MaterialTheme.closePaw.spacing
    val reducedMotion = ClosePawMotion.reducedMotion()
    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .clickable(
                role = Role.Button,
                onClickLabel = "Open current app",
                onClick = onClick,
            )
            .semantics {
                contentDescription = "Status: $text"
            },
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 32.dp)
                .padding(horizontal = spacing.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            StatusPawGlyph(
                color = dotColor,
                size = 14.dp,
            )
            val textModifier = Modifier.widthIn(max = 220.dp)
            if (reducedMotion) {
                Text(
                    text = compactThought(text),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = textModifier,
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = textModifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        velocity = 30.dp,
                        initialDelayMillis = 1500,
                    ),
                )
            }
        }
    }
}

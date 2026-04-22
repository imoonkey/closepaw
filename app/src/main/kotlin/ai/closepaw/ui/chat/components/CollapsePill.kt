package ai.closepaw.ui.chat.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ai.closepaw.ui.theme.closePaw

/**
 * CollapsePill — D2 toggle for AgentRow trace visibility.
 *
 * Compact `[ ▸  ✓ N actions · 12s ]` chip. Owns the click + Role.Button +
 * stateDescription so the row root no longer fights selection in the final-
 * answer surface.
 */
@Composable
fun CollapsePill(
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.closePaw.spacing
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "CollapsePillChevronRotation"
    )
    val state = if (expanded) "expanded" else "collapsed"
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .testTag("qa-collapse-pill")
            .semantics {
                role = Role.Button
                stateDescription = state
            }
            .clickable(onClick = onToggle),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = spacing.md, vertical = spacing.xs),
        ) {
            Text(
                text = "▸",
                style = MaterialTheme.closePaw.monoBody,
                modifier = Modifier.rotate(rotation),
            )
            Spacer(Modifier.width(spacing.sm))
            Text(
                text = summary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

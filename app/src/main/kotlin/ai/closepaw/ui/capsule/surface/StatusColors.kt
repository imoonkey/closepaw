package ai.closepaw.ui.capsule.surface

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import ai.closepaw.ui.overlay.model.GlowState

/**
 * Theme-driven color resolver for semantic capsule status states.
 *
 * Render models carry [GlowState] (semantic), Compose maps it to the active
 * theme's color slot here. Used by the capsule status dot, edge glow, and
 * status island so all three stay in sync.
 */
@Composable
@ReadOnlyComposable
fun GlowState.toStatusColor(): Color = when (this) {
    GlowState.Active, GlowState.Executing -> MaterialTheme.colorScheme.primary
    GlowState.Success -> MaterialTheme.colorScheme.secondary
    GlowState.Error -> MaterialTheme.colorScheme.error
    GlowState.Paused -> MaterialTheme.colorScheme.tertiary
}

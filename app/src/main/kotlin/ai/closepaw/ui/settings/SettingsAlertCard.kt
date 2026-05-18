package ai.closepaw.ui.settings

import ai.closepaw.ui.theme.closePaw
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

internal enum class AlertTone { Error, Warning, Info }

// Inline alert surface. Stays flat (no foldedPaper) — these read as banners
// nested inside cards or page bodies, not as standalone leaves of the book.
// Shape is `shapes.small` (tighter than navigation cards); padding is
// `spacing.md`. Tone selects from container/onContainer color pairs.
@Composable
internal fun SettingsAlertCard(
    message: String,
    tone: AlertTone = AlertTone.Error,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        AlertTone.Error -> colors.errorContainer to colors.onErrorContainer
        AlertTone.Warning -> colors.tertiaryContainer to colors.onTertiaryContainer
        AlertTone.Info -> colors.surfaceContainerHigh to colors.onSurface
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.closePaw.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.sm),
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = fg)
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = fg,
                modifier = Modifier.weight(1f),
            )
            action?.invoke()
        }
    }
}

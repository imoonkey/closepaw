package ai.closepaw.ui.settings

import ai.closepaw.ui.theme.closePaw
import ai.closepaw.ui.theme.foldedPaper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

// Canonical Settings card primitive. Settings sub-pages live as folded leaves
// in the bound book; any card that represents a navigable or tappable
// page-of-the-book uses this surface so the chrome (color, shape, padding,
// folded-paper shadow) stays unified.
@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = MaterialTheme.closePaw.spacing.cardPadding,
        vertical = MaterialTheme.closePaw.spacing.md,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier.fillMaxWidth().clip(shape)
    val interactive = if (onClick != null) base.clickable(onClick = onClick) else base
    Surface(
        modifier = interactive.foldedPaper(shape),
        color = color,
        shape = shape,
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

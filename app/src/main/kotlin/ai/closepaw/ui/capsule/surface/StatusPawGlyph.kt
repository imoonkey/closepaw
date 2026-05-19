package ai.closepaw.ui.capsule.surface

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import ai.closepaw.R

// D1 §6.2: paw glyph replaces the generic status dot on capsule + island.
// Tinted by semantic status color.
@Composable
fun StatusPawGlyph(
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(R.drawable.ic_paw),
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier.size(size),
    )
}

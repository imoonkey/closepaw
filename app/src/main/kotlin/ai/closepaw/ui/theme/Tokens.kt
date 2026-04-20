package ai.closepaw.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The single thin extension surface beyond Material. Carries D1 residue that
// Material slots do not model: extra text roles, identity/mono styles, and
// the four spacing tiers Material does not standardize.
@Immutable
data class ClosePawTokens(
    val inkFaint: Color,
    val bodyItalic: TextStyle,
    val serifItalic: TextStyle,
    val monoBody: TextStyle,
    val monoSmall: TextStyle,
    val spacing: ClosePawSpacing,
)

@Immutable
data class ClosePawSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 32.dp,
)

internal val LocalClosePawTokens = compositionLocalOf<ClosePawTokens> {
    error("ClosePawTokens not provided. Wrap your content in ClosePawTheme { ... }.")
}

val MaterialTheme.closePaw: ClosePawTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalClosePawTokens.current

internal fun lightClosePawTokens() = ClosePawTokens(
    inkFaint = InkFaint,
    bodyItalic = BodyItalicStyle,
    serifItalic = SerifItalicStyle,
    monoBody = MonoBodyStyle,
    monoSmall = MonoSmallStyle,
    spacing = ClosePawSpacing(),
)

internal fun darkClosePawTokens() = ClosePawTokens(
    inkFaint = InkFaintDark,
    bodyItalic = BodyItalicStyle,
    serifItalic = SerifItalicStyle,
    monoBody = MonoBodyStyle,
    monoSmall = MonoSmallStyle,
    spacing = ClosePawSpacing(),
)

// D1 §4.4: subtle warm under-shadow plus a top hairline. The capsule and the
// modal sheet/drawer are the only callers; everything else stays flat.
@Composable
fun Modifier.foldedPaper(shape: Shape = MaterialTheme.shapes.large): Modifier {
    val warm = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val hairline = MaterialTheme.colorScheme.outline
    val strokePx = with(LocalDensity.current) { 1.dp.toPx() }
    return this
        .shadow(elevation = 4.dp, shape = shape, ambientColor = warm, spotColor = warm)
        .drawWithContent {
            drawContent()
            drawLine(hairline, Offset.Zero, Offset(size.width, 0f), strokePx)
        }
}

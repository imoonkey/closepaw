package ai.closepaw.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// D1 → Material role mapping (design_aligned.md §1).
// InkFaint stays in ClosePawTokens — it is a text role, not a Material slot.
private val ClosePawLightColorScheme = lightColorScheme(
    primary = Claw,
    onPrimary = Paper,
    secondary = Moss,
    onSecondary = Paper,
    tertiary = Amber,
    onTertiary = Ink,
    error = Rust,
    onError = Paper,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = Ink,
    secondaryContainer = PaperInset,
    onSecondaryContainer = Ink,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = Ink,
    errorContainer = ErrorContainerLight,
    onErrorContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperInset,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = Paper,
    surfaceContainerLow = PaperInset,
    surfaceContainer = PaperInset,
    surfaceContainerHigh = PaperInset,
    surfaceContainerHighest = PaperInset,
    outline = Hairline,
    outlineVariant = InkGhost,
)

private val ClosePawDarkColorScheme = darkColorScheme(
    primary = ClawDark,
    onPrimary = PaperDark,
    secondary = MossDark,
    onSecondary = PaperDark,
    tertiary = AmberDark,
    onTertiary = InkDark,
    error = RustDark,
    onError = PaperDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = InkDark,
    secondaryContainer = PaperInsetDark,
    onSecondaryContainer = InkDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = InkDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = InkDark,
    background = PaperDark,
    onBackground = InkDark,
    surface = PaperDark,
    onSurface = InkDark,
    surfaceVariant = PaperInsetDark,
    onSurfaceVariant = InkMutedDark,
    surfaceContainerLowest = PaperDark,
    surfaceContainerLow = PaperInsetDark,
    surfaceContainer = PaperInsetDark,
    surfaceContainerHigh = PaperInsetDark,
    surfaceContainerHighest = PaperInsetDark,
    outline = HairlineDark,
    outlineVariant = InkGhostDark,
)

@Composable
fun ClosePawTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) ClosePawDarkColorScheme else ClosePawLightColorScheme
    val tokens = if (darkTheme) darkClosePawTokens() else lightClosePawTokens()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity
            if (activity != null) {
                val window = activity.window
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                if (Build.VERSION.SDK_INT < 35) {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = colorScheme.background.toArgb()
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = colorScheme.background.toArgb()
                }
            }
        }
    }

    CompositionLocalProvider(LocalClosePawTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ClosePawTypography,
            shapes = ClosePawShapes,
            content = content,
        )
    }
}

package com.moonkey.androidagent.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Elegant light color scheme - Notion-inspired
private val AgentLightColorScheme = lightColorScheme(
    // Primary colors
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = SurfaceVariant,
    onPrimaryContainer = TextPrimary,
    
    // Secondary colors  
    secondary = Secondary,
    onSecondary = OnPrimary,
    secondaryContainer = SecondaryLight,
    onSecondaryContainer = Secondary,
    
    // Tertiary colors - Accent
    tertiary = Accent,
    onTertiary = OnPrimary,
    tertiaryContainer = AccentSoft,
    onTertiaryContainer = Accent,
    
    // Background colors
    background = Background,
    onBackground = TextPrimary,
    
    // Surface colors
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    
    // Container colors
    surfaceContainerLowest = Surface,
    surfaceContainerLow = Background,
    surfaceContainer = SurfaceVariant,
    surfaceContainerHigh = SurfaceVariant,
    surfaceContainerHighest = Hover,
    
    // Other colors
    outline = Border,
    outlineVariant = Divider,
    
    // Error colors
    error = StatusError,
    onError = OnPrimary,
    errorContainer = StatusErrorBg,
    onErrorContainer = StatusError,
    
    // Inverse colors
    inverseSurface = Primary,
    inverseOnSurface = Surface,
    inversePrimary = Surface
)

@Composable
fun AgentTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = AgentLightColorScheme
    
    // Set status bar color
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = colorScheme.background.toArgb()
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AgentTypography,
        content = content
    )
}

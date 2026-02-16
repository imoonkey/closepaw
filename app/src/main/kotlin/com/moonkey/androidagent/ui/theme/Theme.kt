package com.moonkey.androidagent.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ============================================
// Chat UI Color Schemes
// ============================================

/**
 * Chat light color scheme - Modern, confident, minimal.
 */
private val ChatLightColorScheme = lightColorScheme(
    // Primary - Confident blue
    primary = ChatPrimary,
    onPrimary = ChatOnPrimary,
    primaryContainer = ChatPrimaryContainer,
    onPrimaryContainer = ChatOnPrimaryContainer,
    
    // Secondary - Success teal
    secondary = ChatSecondary,
    onSecondary = ChatOnSecondary,
    secondaryContainer = ChatSecondaryContainer,
    onSecondaryContainer = ChatOnSecondaryContainer,
    
    // Tertiary (same as secondary for consistency)
    tertiary = ChatSecondary,
    onTertiary = ChatOnSecondary,
    tertiaryContainer = ChatSecondaryContainer,
    onTertiaryContainer = ChatOnSecondaryContainer,
    
    // Surface - Clean, minimal
    surface = ChatSurface,
    onSurface = ChatOnSurface,
    surfaceVariant = ChatSurfaceVariant,
    onSurfaceVariant = ChatOnSurfaceVariant,
    
    // Background
    background = ChatBackground,
    onBackground = ChatOnBackground,
    
    // Container colors
    surfaceContainerLowest = ChatBackground,
    surfaceContainerLow = ChatSurface,
    surfaceContainer = ChatSurfaceVariant,
    surfaceContainerHigh = ChatSurfaceVariant,
    surfaceContainerHighest = ChatSurfaceVariant,
    
    // Error
    error = ChatError,
    onError = ChatOnPrimary,
    errorContainer = ChatErrorBg,
    onErrorContainer = ChatError,
    
    // Outline
    outline = ChatOutline,
    outlineVariant = ChatOutlineVariant,
    
    // Inverse colors
    inverseSurface = ChatOnSurface,
    inverseOnSurface = ChatSurface,
    inversePrimary = ChatPrimaryDark
)

/**
 * Chat dark color scheme.
 */
private val ChatDarkColorScheme = darkColorScheme(
    // Primary - Brighter blue
    primary = ChatPrimaryDark,
    onPrimary = ChatOnPrimaryDark,
    primaryContainer = ChatPrimaryContainerDark,
    onPrimaryContainer = ChatOnPrimaryContainerDark,
    
    // Secondary - Bright teal
    secondary = ChatSecondaryDark,
    onSecondary = ChatOnSecondaryDark,
    secondaryContainer = ChatSecondaryContainerDark,
    onSecondaryContainer = ChatOnSecondaryContainerDark,
    
    // Tertiary
    tertiary = ChatSecondaryDark,
    onTertiary = ChatOnSecondaryDark,
    tertiaryContainer = ChatSecondaryContainerDark,
    onTertiaryContainer = ChatOnSecondaryContainerDark,
    
    // Surface - Deep grays
    surface = ChatSurfaceDark,
    onSurface = ChatOnSurfaceDark,
    surfaceVariant = ChatSurfaceVariantDark,
    onSurfaceVariant = ChatOnSurfaceVariantDark,
    
    // Background
    background = ChatBackgroundDark,
    onBackground = ChatOnBackgroundDark,
    
    // Container colors
    surfaceContainerLowest = ChatBackgroundDark,
    surfaceContainerLow = ChatSurfaceDark,
    surfaceContainer = ChatSurfaceVariantDark,
    surfaceContainerHigh = ChatSurfaceVariantDark,
    surfaceContainerHighest = ChatSurfaceVariantDark,
    
    // Error
    error = ChatErrorDark,
    onError = ChatOnPrimaryDark,
    errorContainer = ChatErrorContainerDark,
    onErrorContainer = ChatErrorDark,
    
    // Outline
    outline = ChatOutlineDark,
    outlineVariant = ChatOutlineVariantDark,
    
    // Inverse colors
    inverseSurface = ChatOnSurfaceDark,
    inverseOnSurface = ChatSurfaceDark,
    inversePrimary = ChatPrimary
)

/**
 * ChatTheme - New chat UI theme with dark mode support.
 */
@Composable
fun ChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) ChatDarkColorScheme else ChatLightColorScheme
    
    // Configure system bars appearance
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity
            if (activity != null) {
                val window = activity.window
                // Set status bar icons based on theme
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme

                // On API < 35, we need to explicitly set bar colors.
                if (Build.VERSION.SDK_INT < 35) {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = colorScheme.background.toArgb()
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = colorScheme.background.toArgb()
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AgentTypography,
        shapes = AgentShapes,
        content = content
    )
}

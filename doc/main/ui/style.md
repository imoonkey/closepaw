# UI Style Guide

> This document defines the design system: colors, typography, shapes, and visual specifications.

## Table of Contents

1. [Design System](#design-system)
2. [Color Palette](#color-palette)
3. [Typography](#typography)
4. [Shapes](#shapes)
5. [Theme Structure](#theme-structure)
6. [Visual Identity](#visual-identity)
7. [Component Specifications](#component-specifications)

---

## Design System

The Android Agent uses Material 3 design with a premium chat-focused aesthetic.

### Theme Files

```
ui/theme/
├── Color.kt       # Light/Dark color schemes
├── Shape.kt       # Bubble shapes, card shapes
├── Theme.kt       # ChatTheme composable + system bar config
└── Type.kt        # Typography definitions
```

---

## Color Palette

Premium chat-focused palette with confident blue primary:

### Light Theme

```kotlin
val LightColorScheme = lightColorScheme(
    // Primary - Confident blue
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E40AF),
    
    // Secondary - Success teal
    secondary = Color(0xFF0D9488),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF115E59),
    
    // Surface - Clean, minimal
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF525252),
    
    // Background
    background = Color.White,
    onBackground = Color(0xFF171717),
    
    // Error
    error = Color(0xFFDC2626),
    onError = Color.White,
    
    // Outline
    outline = Color(0xFFD4D4D4),
    outlineVariant = Color(0xFFE5E5E5)
)
```

### Semantic Colors

| Role | Color | Hex |
|------|-------|-----|
| Primary Blue | Confident blue | `#2563EB` |
| Success Teal | Secondary | `#0D9488` |
| Light Blue | Executing state | `#3B82F6` |
| Purple | Long press | `#7C3AED` |
| Indigo | Scroll actions | `#6366F1` |
| Amber | Paused state | `#F59E0B` |
| Error Red | Error state | `#DC2626` |

---

## Typography

Material 3 typography scale optimized for chat:

```kotlin
val AgentTypography = Typography(
    // Display - Empty state title
    displayMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    
    // Title - Header
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    
    // Body - Chat messages
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    
    // Labels - Action cards, timestamps
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
)
```

### Typography Usage

| Style | Usage |
|-------|-------|
| `displayMedium` | Empty state title |
| `titleLarge` | Header title |
| `bodyLarge` | Chat messages |
| `labelLarge` | Action cards, timestamps |

---

## Shapes

Custom shapes for chat bubbles and cards:

```kotlin
// User bubble: rounded except bottom-right
val BubbleShapeUser = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomStart = 20.dp,
    bottomEnd = 6.dp
)

// Agent bubble: rounded except top-left
val BubbleShapeAgent = RoundedCornerShape(
    topStart = 6.dp,
    topEnd = 20.dp,
    bottomStart = 20.dp,
    bottomEnd = 20.dp
)

// Action cards
val CardShape = RoundedCornerShape(12.dp)

// Smart Capsule
val CapsuleShape = RoundedCornerShape(24.dp)
```

---

## Theme Structure

### ChatTheme Composable

The theme wraps the entire app with Material 3 theming and edge-to-edge configuration.

### System Bar Configuration

- Edge-to-edge enabled
- Status bar: Transparent
- Navigation bar: Transparent with scrim
- Proper inset handling via `AppWindowInsets`

---

## Visual Identity

| Element | Style |
|---------|-------|
| Background | Clean white (#FFFFFF) |
| User Bubbles | Primary blue (#2563EB), white text |
| Agent Bubbles | Light surface (#F5F5F5), dark text |
| Action Cards | Bordered cards with status colors |
| Task Banner | Subtle surface variant with pulsing dot |
| Smart Capsule | White with shadow, status dot |
| Edge Glow | State-colored gradient glow on screen edges |
| Click Ripple | Expanding blue/purple circle at touch point |
| Swipe Trail | Animated line with dots showing gesture path |

---

## Component Specifications

### Smart Capsule

| Property | Value |
|----------|-------|
| Height (compact) | 48dp |
| Width | Screen width - 32dp margins |
| Corner Radius | 24dp (capsule) |
| Background | White with subtle shadow |
| Status Dot | 8dp, color-coded |
| Typography | 14sp, Medium weight |
| Button Size | 40dp circular |

### Edge Glow Colors

| State | Color | Hex |
|-------|-------|-----|
| Active | Primary Blue | `#2563EB` |
| Executing | Light Blue | `#3B82F6` |
| Success | Teal | `#0D9488` |
| Error | Red | `#DC2626` |
| Paused | Amber | `#F59E0B` |

### Click Ripple

| Property | Value |
|----------|-------|
| Initial radius | 8dp |
| Final radius | 48dp |
| Duration | 500ms |
| Animation | EaseOut (fast start, slow end) |
| Click color | Blue (`#2563EB`) at 60% opacity |
| Long press color | Purple (`#7C3AED`) at 60% opacity |

### Swipe Trail

| Property | Value |
|----------|-------|
| Line width | 4dp |
| Start dot radius | 8dp |
| End dot radius | 6dp |
| Swipe color | Light Blue (`#3B82F6`) at 50% opacity |
| Scroll color | Indigo (`#6366F1`) at 50% opacity |
| Animation | Linear, matches gesture duration |

---

## References

- [Jetpack Compose Documentation](https://developer.android.com/jetpack/compose)
- [Material 3 for Compose](https://developer.android.com/jetpack/compose/designsystems/material3)
- [Compose BOM](https://developer.android.com/jetpack/compose/bom)

---

## Related Docs

- [UI User Interaction](user_interaction.md) - Pages, components, user behaviors
- [UI Tech Design](tech_design.md) - Technical implementation details

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

Clean, modern palette inspired by ChatGPT and contemporary AI assistants:

### Light Theme

```kotlin
val LightColorScheme = lightColorScheme(
    // Primary - Soft black for CTA (send button)
    primary = Color(0xFF3B3B3B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0F0F0),
    onPrimaryContainer = Color(0xFF3B3B3B),
    
    // Secondary - ChatGPT teal for success/accent
    secondary = Color(0xFF10A37F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6F4F1),
    onSecondaryContainer = Color(0xFF0D7355),
    
    // Surface - Pure white, unified
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0D0D0D),
    surfaceVariant = Color(0xFFF7F7F8),
    onSurfaceVariant = Color(0xFF5D5D5D),
    
    // Background - Pure white
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0D0D0D),
    
    // Error
    error = Color(0xFFEF4146),
    onError = Color.White,
    
    // Outline - Visible borders
    outline = Color(0xFFE5E5E5),
    outlineVariant = Color(0xFFEEEEEE)
)
```

### Semantic Colors

| Role | Color | Hex |
|------|-------|-----|
| Primary (Send Button) | Soft black | `#3B3B3B` |
| Success Teal | ChatGPT green | `#10A37F` |
| Warning | Warm amber | `#F5A623` |
| Error Red | Clear red | `#EF4146` |
| Info Blue | Standard blue | `#2563EB` |
| User Bubble | Light gray | `#EFEFEF` |
| Icon Tint | Medium gray | `#5D5D5D` |

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
    
    // Title - Header (Medium weight for elegance)
    titleLarge = TextStyle(
        fontWeight = FontWeight.Medium,
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
| Background | Pure white (#FFFFFF) |
| User Bubbles | Light gray (#EFEFEF), dark text — modern chat style |
| Agent Bubbles | Surface variant (#F7F7F8), dark text |
| Action Cards | Bordered cards with status colors |
| Task Banner | Subtle surface variant with pulsing dot |
| Smart Capsule | White with shadow, status dot |
| Send Button | Pure black when text entered, gray when empty |
| Edge Glow | State-colored gradient glow on screen edges |
| Click Ripple | Expanding circle at touch point |
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
| Active | Teal | `#10A37F` |
| Executing | Light Blue | `#3B82F6` |
| Success | Teal | `#10A37F` |
| Error | Red | `#EF4146` |
| Paused | Amber | `#F5A623` |

### Click Ripple

| Property | Value |
|----------|-------|
| Initial radius | 8dp |
| Final radius | 48dp |
| Duration | 500ms |
| Animation | EaseOut (fast start, slow end) |
| Click color | Gray at 60% opacity |
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

# UI Style Guide

> Design system: colors, typography, shapes, and visual specifications.
> Last updated: 2026-02-04 (commit: da83b53ba4e849e52b45158a3485261d7399facb)

## Design System

The Android Agent uses Material 3 design with a premium chat-focused aesthetic.

### Theme Files

→ See: `ui/theme/`

| File | Purpose |
|------|---------|
| `Color.kt` | Light/Dark color schemes |
| `Shape.kt` | Bubble shapes, card shapes |
| `Theme.kt` | ChatTheme composable + system bar config |
| `Type.kt` | Typography definitions |
| `WindowInsets.kt` | AppWindowInsets for consistent inset handling |

---

## Color Palette

Clean, modern palette inspired by ChatGPT and contemporary AI assistants.

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
    displayMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    
    // Title - Header (Medium weight for elegance)
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp),
    
    // Body - Chat messages
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    
    // Labels - Action cards, timestamps
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
)
```

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
    topStart = 20.dp, topEnd = 20.dp,
    bottomStart = 20.dp, bottomEnd = 6.dp
)

// Agent bubble: rounded except top-left
val BubbleShapeAgent = RoundedCornerShape(
    topStart = 6.dp, topEnd = 20.dp,
    bottomStart = 20.dp, bottomEnd = 20.dp
)

// Action cards
val CardShape = RoundedCornerShape(12.dp)

// Smart Capsule
val CapsuleShape = RoundedCornerShape(24.dp)
```

---

## Visual Identity

| Element | Style |
|---------|-------|
| Background | Pure white (#FFFFFF) |
| User Bubbles | Light gray (#EFEFEF), dark text |
| Agent Bubbles | Surface variant (#F7F7F8), dark text |
| Action Cards | Bordered cards with status colors |
| Task Banner | Subtle surface variant with pulsing dot |
| Send Button | Pure black when text entered, gray when empty |

---

## System Configuration

### Edge-to-Edge

- Status bar: Transparent
- Navigation bar: Transparent with scrim
- Proper inset handling via `AppWindowInsets`

---

## Related Docs

- [User Interaction](user_interaction.md) - Pages, components, user behaviors
- [Tech Design](tech_design.md) - Technical implementation details
- [Overlay](overlay.md) - Overlay visual specifications

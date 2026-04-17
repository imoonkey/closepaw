# UI Style Guide

> Design system: colors, typography, shapes, and visual specifications.
> Last updated: 2026-02-20 (commit: 2493be6)

## Design System

ClosePaw uses Material 3 with a chat-focused aesthetic. Dark mode support via system theme detection.

### Theme Files

> See: `ui/theme/`

| File | Purpose |
|------|---------|
| `Color.kt` | Light/Dark color definitions |
| `Shape.kt` | Bubble shapes, card shapes, special shapes |
| `Theme.kt` | `ChatTheme` composable + system bar config |
| `Type.kt` | `AgentTypography` scale |
| `WindowInsets.kt` | `AppWindowInsets` singleton for consistent inset handling |

### ChatTheme

> See: `ui/theme/Theme.kt`

```kotlin
@Composable
fun ChatTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)
```

- Selects `ChatLightColorScheme` or `ChatDarkColorScheme` based on system theme
- Configures status bar icon appearance (`isAppearanceLightStatusBars`)
- Sets bar colors on API < 35
- Applies `AgentTypography` and `AgentShapes`

---

## Color Palette

Clean, modern palette inspired by contemporary AI assistants. High clarity, warm neutrals.

### Light Theme

> See: `ui/theme/Color.kt`

| Role | Color | Hex | Usage |
|------|-------|-----|-------|
| Primary | Soft black | `#3B3B3B` | Send button, CTA elements |
| On Primary | White | `#FFFFFF` | Text on primary |
| Primary Container | Light gray | `#F0F0F0` | Chips, secondary containers |
| Secondary | Teal | `#10A37F` | Success states, accents |
| Secondary Container | Light teal | `#E6F4F1` | Success backgrounds |
| Surface | Pure white | `#FFFFFF` | Main surface |
| Surface Variant | Light gray | `#F7F7F8` | Agent bubble background |
| On Surface | Near black | `#0D0D0D` | Primary text |
| On Surface Variant | Medium gray | `#5D5D5D` | Secondary text, icon tints |
| Background | Pure white | `#FFFFFF` | Screen background |
| Error | Red | `#EF4146` | Error states |
| Error Background | Light red | `#FEEEF` | Error containers |
| Outline | Light border | `#E5E5E5` | Visible borders |
| Outline Variant | Subtle border | `#EEEEEE` | Secondary borders |

### Dark Theme

| Role | Color | Hex |
|------|-------|-----|
| Primary | Light gray | `#EEEEEE` |
| On Primary | Dark text | `#1A1A1A` |
| Primary Container | Dark container | `#2D2D2D` |
| Secondary | Bright teal | `#4ADE9E` |
| Surface | Dark surface | `#1A1A1A` |
| Surface Variant | Slightly lighter | `#2D2D2D` |
| On Surface | Light text | `#EEEEEE` |
| On Surface Variant | Medium text | `#B4B4B4` |
| Background | Near black | `#0D0D0D` |
| Error | Light red | `#FF6B6B` |
| Outline | Dark border | `#3D3D3D` |

### Semantic Colors

| Role | Light | Dark | Hex (Light) |
|------|-------|------|------------|
| Success | Teal | Bright teal | `#10A37F` |
| Warning | Warm amber | — | `#F5A623` |
| Error | Clear red | Light red | `#EF4146` |
| Info | Blue | — | `#2563EB` |

### Chat-Specific Colors

| Element | Light Hex | Description |
|---------|-----------|-------------|
| User Bubble | `#EFEFEF` | Light gray background |
| User Bubble Text | `#1A1A1A` | Dark text on light bubble |
| Send Button Active | `#000000` | Pure black when input has text |
| Send Button Icon | `#FFFFFF` | White icon on black button |
| Icon Primary | `#5D5D5D` | Medium gray icon tint |
| Icon Secondary | `#8E8E8E` | Lighter icon tint |

### Interactive States

| State | Hex |
|-------|-----|
| Hover | `#F7F7F8` |
| Pressed | `#EEEEEE` |
| Disabled Background | `#E5E5E5` |
| Disabled Text | `#B4B4B4` |

### Overlay Colors

Overlay elements use a separate palette defined in `CapsuleColors`:

| Role | Color | Hex |
|------|-------|-----|
| Running / Active | Blue | `#2563EB` |
| Takeover / Paused | Amber | `#F59E0B` |
| Done / Success | Teal | `#0D9488` |
| Error | Red | `#EF4444` |
| Executing (glow) | Purple | `#7C3AED` |

See [Overlay](overlay.md) for full color specifications per component.

---

## Typography

> See: `ui/theme/Type.kt`

Full Material 3 typography scale (`AgentTypography`):

### Display

| Style | Weight | Size | Line Height | Usage |
|-------|--------|------|-------------|-------|
| `displayLarge` | Bold | 48sp | 56sp | — |
| `displayMedium` | Bold | 36sp | 44sp | — |
| `displaySmall` | SemiBold | 28sp | 36sp | Empty state title |

### Headline

| Style | Weight | Size | Line Height | Usage |
|-------|--------|------|-------------|-------|
| `headlineLarge` | SemiBold | 24sp | 32sp | — |
| `headlineMedium` | SemiBold | 20sp | 28sp | — |
| `headlineSmall` | Medium | 18sp | 24sp | — |

### Title

| Style | Weight | Size | Line Height | Usage |
|-------|--------|------|-------------|-------|
| `titleLarge` | SemiBold | 18sp | 24sp | Header title |
| `titleMedium` | Medium | 16sp | 22sp | — |
| `titleSmall` | Medium | 14sp | 20sp | — |

### Body

| Style | Weight | Size | Line Height | Usage |
|-------|--------|------|-------------|-------|
| `bodyLarge` | Normal | 16sp | 24sp | Chat messages |
| `bodyMedium` | Normal | 14sp | 20sp | Secondary content |
| `bodySmall` | Normal | 12sp | 16sp | Timestamps |

### Label

| Style | Weight | Size | Line Height | Usage |
|-------|--------|------|-------------|-------|
| `labelLarge` | Medium | 14sp | 20sp | Action cards, buttons |
| `labelMedium` | Medium | 12sp | 16sp | Status island text |
| `labelSmall` | Medium | 10sp | 14sp | Captions |

---

## Shapes

> See: `ui/theme/Shape.kt`

### Material 3 Shape Scale (`AgentShapes`)

| Scale | Radius | Usage |
|-------|--------|-------|
| `small` | 8dp | Chips, small cards |
| `medium` | 12dp | Action cards, list items |
| `large` | 20dp | Bubbles, sheets, dialogs |
| `extraLarge` | 24dp | Extra large sheets |

### Chat Bubble Shapes

Asymmetric corners for natural conversation feel:

```kotlin
// User bubble: rounded except bottom-right (pointing right)
val BubbleShapeUser = RoundedCornerShape(
    topStart = 20.dp, topEnd = 20.dp,
    bottomStart = 20.dp, bottomEnd = 6.dp
)

// Agent bubble: rounded except top-left (pointing left)
val BubbleShapeAgent = RoundedCornerShape(
    topStart = 6.dp, topEnd = 20.dp,
    bottomStart = 20.dp, bottomEnd = 20.dp
)
```

### Special Shapes

| Shape | Definition | Usage |
|-------|-----------|-------|
| `CapsuleShape` | `RoundedCornerShape(24.dp)` | Smart Capsule overlay |
| `PillShape` | `RoundedCornerShape(percent = 50)` | Fully rounded pills |
| `CardShape` | `RoundedCornerShape(12.dp)` | Action cards, containers |
| `InputShape` | `RoundedCornerShape(24.dp)` | Text input fields |
| `SheetShape` | Top corners 20dp, bottom 0dp | Bottom sheets |

---

## Visual Identity

| Element | Light Mode | Dark Mode |
|---------|-----------|-----------|
| Background | Pure white (`#FFFFFF`) | Near black (`#0D0D0D`) |
| User Bubbles | Light gray (`#EFEFEF`) + dark text | Dark container + light text |
| Agent Bubbles | Surface variant (`#F7F7F8`) + dark text | Dark variant + light text |
| Action Cards | Bordered cards with status colors | Same pattern, dark surfaces |
| Send Button | Pure black when text entered, gray when empty | Light gray when text, dark when empty |

---

## System Configuration

### Edge-to-Edge

- Status bar: Transparent (icon color adapts to theme)
- Navigation bar: Transparent (color set on API < 35)
- Insets managed via `AppWindowInsets` singleton

### AppWindowInsets

> See: `ui/theme/WindowInsets.kt`

```kotlin
object AppWindowInsets {
    val systemBars: WindowInsets     // Full system bars (status + navigation)
    val statusBars: WindowInsets     // Status bar only (headers, drawers)
    val navigationBars: WindowInsets // Navigation bar only (bottom content)
    val none: WindowInsets           // Edge-to-edge (parent handles insets)
}
```

Use Material 3 built-in `windowInsets` parameters on components (`ModalBottomSheet`, `Scaffold`, etc.) rather than manual `windowInsetsPadding()` modifiers.

---

## File Structure

```
ui/theme/
├── Color.kt          # Light/Dark color definitions (semantic + chat-specific)
├── Shape.kt          # AgentShapes, bubble shapes, special shapes
├── Theme.kt          # ChatTheme composable (light/dark scheme selection)
├── Type.kt           # AgentTypography (full Material 3 scale)
└── WindowInsets.kt   # AppWindowInsets singleton
```

---

## Related Docs

- [User Interaction](user_interaction.md) - Pages, components, user behaviors
- [Tech Design](tech_design.md) - Technical implementation details
- [Overlay](overlay.md) - Overlay visual specifications and capsule colors

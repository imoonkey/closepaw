# Android Agent UI Stack Design

> **Created**: January 16, 2026
>
> Modernizing the Android Agent UI with Jetpack Compose and Material 3.

## Table of Contents

1. [Overview](#overview)
2. [Current State](#current-state)
3. [Recommended Stack](#recommended-stack)
4. [Migration Plan](#migration-plan)
5. [Design System](#design-system)
6. [Component Architecture](#component-architecture)

---

## Overview

### Goals

| Goal | Description |
|------|-------------|
| **Modern DX** | Declarative UI with Compose - less boilerplate, easier to iterate |
| **Beautiful UI** | Material 3 with dynamic colors and polished animations |
| **Minimal Effort** | Leverage M3 defaults + pre-built components |
| **Future-Proof** | Compose is Google's recommended UI toolkit for Android |

### Why Jetpack Compose + Material 3?

1. **Declarative** - Describe what UI should look like, not how to build it
2. **Less Code** - ~40% less code compared to XML + View binding
3. **Type-Safe** - Kotlin-first, compile-time checks
4. **Dynamic Theming** - Material You adapts to device wallpaper colors
5. **Animation Built-In** - First-class animation APIs
6. **Interop** - Can coexist with existing Views during migration

---

## Current State

### Existing UI Components

| Component | Implementation | Lines of Code |
|-----------|---------------|---------------|
| MainActivity | XML Layout + findViewById | ~160 lines |
| OverlayManager | Programmatic Views | ~120 lines |
| Theme | Basic AppCompat | Minimal |

### Current Dependencies

```kotlin
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("com.google.android.material:material:1.11.0")
implementation("androidx.constraintlayout:constraintlayout:2.1.4")
```

### Pain Points

- **Verbose**: `findViewById`, manual state management
- **Limited Styling**: Basic Material Design 2 components
- **No Animation**: Static, utilitarian UI
- **Hard to Iterate**: XML changes require rebuild to preview

---

## Recommended Stack

### Core Dependencies

```kotlin
// Compose BOM (Bill of Materials) - manages version compatibility
val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
implementation(composeBom)

// Compose UI
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.ui:ui-graphics")
implementation("androidx.compose.ui:ui-tooling-preview")
implementation("androidx.compose.foundation:foundation")

// Material 3
implementation("androidx.compose.material3:material3")

// Activity Compose integration
implementation("androidx.activity:activity-compose:1.9.3")

// Lifecycle + ViewModel integration
implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

// Debug tooling
debugImplementation("androidx.compose.ui:ui-tooling")
debugImplementation("androidx.compose.ui:ui-test-manifest")
```

### Why This Stack?

| Library | Purpose | Benefit |
|---------|---------|---------|
| **Compose BOM** | Version management | No version conflicts, single source of truth |
| **Material 3** | Design system | Dynamic colors, modern components, accessibility |
| **Activity Compose** | Integration | `setContent {}` entry point |
| **Lifecycle Compose** | State management | `collectAsStateWithLifecycle()` |

### Version Strategy

Using **Compose BOM 2024.12.01** ensures:
- Kotlin 2.0+ compatibility
- Latest M3 components
- Stable production-ready APIs

---

## Migration Plan

### Phase 1: Setup (This PR)

1. Add Compose dependencies to `build.gradle.kts`
2. Enable Compose compiler in build config
3. Create base theme (`AgentTheme.kt`)
4. Migrate `MainActivity` to Compose

### Phase 2: Polish (Future)

1. Add animations and transitions
2. Implement agent execution progress visualization
3. Consider Compose-based overlay (or keep View-based for simplicity)

### Migration Strategy

**Side-by-Side Approach**: Compose can render inside existing Activities using `setContent {}`. No need to migrate everything at once.

```kotlin
// Before: XML-based
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // findViewById...
    }
}

// After: Compose-based
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgentTheme {
                AgentScreen()
            }
        }
    }
}
```

---

## Design System

### Color Palette

Elegant, minimal palette inspired by Notion:

```kotlin
// Background & Surface - Warm off-whites
val Background = Color(0xFFFBFBFA)
val Surface = Color(0xFFFFFFFF)
val SurfaceVariant = Color(0xFFF7F6F3)

// Primary - Soft charcoal (professional, calm)
val Primary = Color(0xFF2F3437)

// Accent - Warm coral for CTAs
val Accent = Color(0xFFEB5757)

// Secondary - Soft teal for secondary actions
val Secondary = Color(0xFF0F7B6C)

// Text hierarchy
val TextPrimary = Color(0xFF37352F)
val TextSecondary = Color(0xFF6B6B6B)
val TextMuted = Color(0xFF9B9A97)
```

### Typography

Material 3 default typography with custom display font:

```kotlin
val AgentTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp
    ),
    // ... M3 provides sensible defaults for all other styles
)
```

### Theme Structure

```
AgentTheme/
├── Color.kt       # Color definitions
├── Theme.kt       # Main theme composable
├── Type.kt        # Typography definitions
└── Shape.kt       # Shape definitions (optional, M3 has good defaults)
```

### Visual Identity

| Element | Style |
|---------|-------|
| Background | Warm off-white (#FBFBFA) |
| Cards | Clean white surfaces with subtle borders |
| Inputs | Outlined text fields with rounded corners |
| Buttons | Solid charcoal primary, outlined secondary |
| Status Log | Clean list with color-coded status indicators |
| Overlay | Bottom-positioned floating card with controls |

---

## Component Architecture

### Screen Structure

```
AgentScreen
├── TopBar (App title, settings)
├── ConfigSection
│   ├── ApiKeyField (outlined, password)
│   └── GoalField (outlined, multiline)
├── ActionButtons
│   ├── StartButton (filled, prominent)
│   └── AccessibilityButton (tonal, secondary)
└── StatusSection
    ├── StatusHeader
    └── StatusLog (scrollable, monospace)
```

### State Management

```kotlin
// UI State
data class AgentUiState(
    val apiKey: String = "",
    val goal: String = "",
    val status: List<String> = emptyList(),
    val isServiceEnabled: Boolean = false,
    val isRunning: Boolean = false
)

// In Compose
@Composable
fun AgentScreen(
    state: AgentUiState,
    onApiKeyChange: (String) -> Unit,
    onGoalChange: (String) -> Unit,
    onStartClick: () -> Unit,
    onAccessibilityClick: () -> Unit
)
```

### Component Guidelines

1. **Single Responsibility**: Each composable does one thing
2. **State Hoisting**: UI state lifted to screen level
3. **Preview Support**: All components should have `@Preview`
4. **Accessibility**: Use semantic modifiers (`contentDescription`, etc.)

---

## File Structure (After Migration)

```
app/src/main/kotlin/com/moonkey/androidagent/
├── MainActivity.kt              # Compose entry point
├── AgentService.kt              # (unchanged)
├── ui/
│   ├── theme/
│   │   ├── Color.kt
│   │   ├── Theme.kt
│   │   └── Type.kt
│   ├── screen/
│   │   └── AgentScreen.kt       # Main screen composable
│   └── component/
│       ├── ApiKeyField.kt
│       ├── GoalField.kt
│       ├── ActionButtons.kt
│       └── StatusLog.kt
├── agent/                       # (unchanged)
├── data/                        # (unchanged)
...
```

---

## Quick Reference

### Starting with Compose

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge() // Modern edge-to-edge display
        
        setContent {
            AgentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AgentScreen()
                }
            }
        }
    }
}
```

### Material 3 Components Used

| Component | Usage |
|-----------|-------|
| `OutlinedTextField` | API key and goal inputs |
| `Button` | Primary action (Start Agent) |
| `FilledTonalButton` | Secondary action (Accessibility) |
| `Card` | Status log container |
| `Surface` | Background containers |
| `Text` | All text content |
| `Icon` | Visual indicators |

---

## References

- [Jetpack Compose Documentation](https://developer.android.com/jetpack/compose)
- [Material 3 for Compose](https://developer.android.com/jetpack/compose/designsystems/material3)
- [Compose BOM](https://developer.android.com/jetpack/compose/bom)
- [Migration Guide](https://developer.android.com/jetpack/compose/migrate)


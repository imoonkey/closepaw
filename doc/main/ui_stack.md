# Android Agent UI Stack

> This document describes the UI architecture, design system, and component structure.

## Table of Contents

1. [Overview](#overview)
2. [Tech Stack](#tech-stack)
3. [Design System](#design-system)
4. [Component Architecture](#component-architecture)
5. [File Structure](#file-structure)
6. [Quick Reference](#quick-reference)

---

## Overview

The Android Agent uses Jetpack Compose with Material 3 for a modern, elegant user interface.

| Goal | Implementation |
|------|----------------|
| **Modern DX** | Declarative UI with Compose |
| **Beautiful UI** | Material 3 with Notion-inspired aesthetic |
| **Edge-to-Edge** | Full screen utilization with proper insets |
| **Reactive** | State-driven UI with automatic recomposition |

### Key Components

```
┌────────────────────────────────────────────────────────────────┐
│                        MainActivity                             │
│  (Compose entry point, state management, event collection)      │
├────────────────────────────────────────────────────────────────┤
│                        AgentScreen                              │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Header        │ Title + subtitle                        │   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ ConfigSection │ API Key input, Goal input               │   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ ActionButtons │ Start Agent, Accessibility Settings     │   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ StatusLog     │ Activity feed with color-coded entries  │   │
│  └─────────────────────────────────────────────────────────┘   │
├────────────────────────────────────────────────────────────────┤
│                     OverlayManager                              │
│  (View-based floating control bar during agent execution)       │
└────────────────────────────────────────────────────────────────┘
```

---

## Tech Stack

### Dependencies

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
implementation("androidx.compose.material:material-icons-extended")

// Activity Compose integration
implementation("androidx.activity:activity-compose:1.9.3")

// Debug tooling
debugImplementation("androidx.compose.ui:ui-tooling")
debugImplementation("androidx.compose.ui:ui-test-manifest")
```

### Why This Stack?

| Library | Purpose |
|---------|---------|
| **Compose BOM** | Version management, no conflicts |
| **Material 3** | Modern design system, accessibility built-in |
| **Activity Compose** | `setContent {}` entry point |
| **Material Icons** | Comprehensive icon set |

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
val OnPrimary = Color(0xFFFFFFFF)

// Secondary - Soft teal for secondary actions
val Secondary = Color(0xFF0F7B6C)
val SecondaryLight = Color(0xFFE6F4F1)

// Accent - Warm coral for emphasis
val Accent = Color(0xFFEB5757)

// Text hierarchy
val TextPrimary = Color(0xFF37352F)
val TextSecondary = Color(0xFF6B6B6B)
val TextMuted = Color(0xFF9B9A97)
val TextPlaceholder = Color(0xFFB4B4B4)

// Borders
val Border = Color(0xFFE9E9E7)
val BorderFocused = Color(0xFF2F3437)

// Status colors
val StatusSuccess = Color(0xFF0F7B6C)
val StatusWarning = Color(0xFFF2994A)
val StatusError = Color(0xFFEB5757)
val StatusInfo = Color(0xFF2F80ED)
```

### Typography

Material 3 typography scale with custom weights:

```kotlin
val AgentTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    // ... full scale in Type.kt
)
```

### Theme Structure

```
ui/theme/
├── Color.kt       # Color definitions (semantic tokens)
├── Theme.kt       # AgentTheme composable + system bar config
└── Type.kt        # Typography definitions (M3 scale)
```

**System Bar Handling**: `MainActivity` calls `enableEdgeToEdge()` for modern edge-to-edge display. On API levels 26-34, the theme configures status/navigation bar colors via `Window` APIs. On API 35+, bars are transparent by default.

### Visual Identity

| Element | Style |
|---------|-------|
| Background | Warm off-white (#FBFBFA) |
| Cards | Clean white surfaces with subtle borders |
| Inputs | Outlined text fields with rounded corners (10dp) |
| Buttons | Solid charcoal primary, outlined secondary |
| Status Log | Color-coded entries with status icons |
| Overlay | Bottom-positioned floating card |

---

## Component Architecture

### AgentScreen

The main screen composable with state hoisting pattern.

```kotlin
data class AgentUiState(
    val apiKey: String = "",
    val goal: String = "",
    val statusLines: List<String> = emptyList(),
    val isServiceEnabled: Boolean = false,
    val isRunning: Boolean = false
)

@Composable
fun AgentScreen(
    state: AgentUiState,
    onApiKeyChange: (String) -> Unit,
    onGoalChange: (String) -> Unit,
    onStartClick: () -> Unit,
    onAccessibilityClick: () -> Unit
)
```

### Screen Sections

| Section | Components | Purpose |
|---------|------------|---------|
| Header | Title, subtitle | App branding |
| ConfigSection | API key field, goal field | User inputs |
| ActionButtons | Start button, accessibility button | Actions |
| StatusLog | Scrollable activity feed | Execution feedback |

### StatusLog

Displays agent activity with semantic color coding:

```kotlin
@Composable
private fun StatusLine(text: String, isLatest: Boolean) {
    val (bgColor, textColor, icon) = when (StatusUtils.getStatusType(text)) {
        StatusType.SUCCESS -> Triple(StatusSuccessBg, StatusSuccess, "✓")
        StatusType.ERROR -> Triple(StatusErrorBg, StatusError, "✗")
        StatusType.WARNING -> Triple(StatusWarningBg, StatusWarning, "!")
        StatusType.THINKING -> Triple(StatusInfoBg, StatusInfo, "◉")
        StatusType.TOOL -> Triple(Color.Transparent, TextSecondary, "→")
        StatusType.RUNNING -> Triple(StatusInfoBg, StatusInfo, "▶")
        StatusType.NEUTRAL -> Triple(Color.Transparent, TextSecondary, "·")
    }
    // ... render with icon and clean text
}
```

### OverlayManager

View-based floating control bar for agent execution. Uses Views instead of Compose because:
- Window overlays require `WindowManager.addView()`
- Simpler lifecycle management for system-level UI
- Matches the theme colors programmatically

**Features:**
- Status text with truncation
- Status indicator dot (color-coded)
- Pause/Resume toggle
- Stop button

### StatusUtils

Shared utilities for consistent status processing across all UI components:

```kotlin
object StatusUtils {
    // Remove emojis for clean display
    fun cleanStatusText(status: String): String
    
    // Categorize status messages semantically
    fun getStatusType(status: String): StatusType
    
    // Detect terminal states (session completed/failed)
    fun isTerminalStatus(status: String): Boolean
}
```

Used by:
- `AgentScreen.kt` - Status log display
- `OverlayManager.kt` - Floating overlay
- `MainActivity.kt` - Completion detection

---

## File Structure

```
app/src/main/kotlin/com/moonkey/androidagent/
├── app/
│   ├── MainActivity.kt              # Compose entry point, state management
│   └── AgentService.kt              # Accessibility service
│
├── ui/
│   ├── theme/
│   │   ├── Color.kt             # Notion-inspired color palette
│   │   ├── Theme.kt             # AgentTheme composable
│   │   └── Type.kt              # Material 3 typography
│   ├── screen/
│   │   └── AgentScreen.kt       # Main screen (all sections inline)
│   └── overlay/
│       └── OverlayManager.kt    # View-based floating control bar
│
├── util/
│   └── StatusUtils.kt           # Shared status processing
│
└── ... (agent, tool, etc.)
```

---

## Quick Reference

### MainActivity Setup

```kotlin
class MainActivity : ComponentActivity() {
    // UI State (simple mutableStateOf, no ViewModel)
    private var apiKey by mutableStateOf("")
    private var goal by mutableStateOf("")
    private var statusLines by mutableStateOf(listOf<String>())
    private var isServiceEnabled by mutableStateOf(false)
    private var isRunning by mutableStateOf(false)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        
        // Collect status updates lifecycle-aware
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgentService.statusFlow.collect { status ->
                    statusLines = (statusLines + status).takeLast(MAX_STATUS_LINES)
                    if (StatusUtils.isTerminalStatus(status)) {
                        isRunning = false
                    }
                }
            }
        }
        
        setContent {
            AgentTheme {
                AgentScreen(
                    state = AgentUiState(apiKey, goal, statusLines, isServiceEnabled, isRunning),
                    onApiKeyChange = { apiKey = it },
                    onGoalChange = { goal = it },
                    onStartClick = { startAgent() },
                    onAccessibilityClick = { openAccessibilitySettings() }
                )
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
| `OutlinedButton` | Secondary action (Accessibility) |
| `Box`, `Column`, `Row` | Layout containers |
| `Surface` | Background containers |
| `Text` | All text content |
| `Icon` | Visual indicators |
| `CircularProgressIndicator` | Loading state |
| `AnimatedVisibility` | Entry animations |

### Status Flow

```
AgentService.statusFlow
        │
        ▼
MainActivity (lifecycle-aware collection)
        │
        ├──► statusLines state update
        │
        └──► AgentScreen recomposition
                    │
                    └──► StatusLog display
```

### Intent Extras

MainActivity supports launching with pre-filled values:

```kotlin
companion object {
    const val EXTRA_API_KEY = "api_key"
    const val EXTRA_GOAL = "goal"
    const val EXTRA_AUTO_START = "auto_start"
}
```

---

## References

- [Jetpack Compose Documentation](https://developer.android.com/jetpack/compose)
- [Material 3 for Compose](https://developer.android.com/jetpack/compose/designsystems/material3)
- [Compose BOM](https://developer.android.com/jetpack/compose/bom)

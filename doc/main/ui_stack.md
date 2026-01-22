# Android Agent UI Stack

> This document describes the UI architecture, design system, and component structure.

## Table of Contents

1. [Overview](#overview)
2. [Tech Stack](#tech-stack)
3. [Design System](#design-system)
4. [Component Architecture](#component-architecture)
5. [File Structure](#file-structure)
6. [Smart Capsule](#smart-capsule)
7. [Quick Reference](#quick-reference)

---

## Overview

The Android Agent uses a **chat-first conversational interface** built with Jetpack Compose and Material 3. The UI is designed around the principle of "Invisible Intelligence" — the interface disappears, leaving only the conversation.

| Goal | Implementation |
|------|----------------|
| **Chat-First** | Conversational UI with streaming responses |
| **Modern DX** | Declarative UI with Compose |
| **Beautiful UI** | Material 3 with premium polish |
| **Edge-to-Edge** | Full screen utilization with proper insets |
| **Reactive** | State-driven with real-time streaming |
| **Ubiquitous** | Smart Capsule overlay follows users across apps |

### Key Components

```
┌────────────────────────────────────────────────────────────────┐
│                        MainActivity                             │
│  (Compose entry point, ChatViewModel, event collection)         │
├────────────────────────────────────────────────────────────────┤
│                        ChatScreen                               │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ ChatHeader     │ "Android Agent" (long-press for settings)│   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ TaskBanner    │ "Working on: ..." with status dot        │   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ MessageList   │ User/Agent bubbles, Action cards         │   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ InputDock     │ Text input + Send/Stop button            │   │
│  └─────────────────────────────────────────────────────────┘   │
├────────────────────────────────────────────────────────────────┤
│                     SettingsSheet                               │
│  (Modal bottom sheet for model/config)                          │
├────────────────────────────────────────────────────────────────┤
│                   SmartCapsuleManager                           │
│  (View-based floating overlay during agent execution)           │
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

Premium chat-focused palette with confident blue primary:

```kotlin
// Light Theme
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

### Typography

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

### Shapes

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

### Theme Structure

```
ui/theme/
├── Color.kt       # Light/Dark color schemes
├── Shape.kt       # Bubble shapes, card shapes
├── Theme.kt       # ChatTheme composable + system bar config
└── Type.kt        # Typography definitions
```

### Visual Identity

| Element | Style |
|---------|-------|
| Background | Clean white (#FFFFFF) |
| User Bubbles | Primary blue (#2563EB), white text |
| Agent Bubbles | Light surface (#F5F5F5), dark text |
| Action Cards | Bordered cards with status colors |
| Task Banner | Subtle surface variant with pulsing dot |
| Smart Capsule | White with shadow, status dot |

---

## Component Architecture

### ChatScreen

The main screen composable that integrates all chat components.

```kotlin
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
)
```

### ChatViewModel

State management for chat UI with event handling:

```kotlin
class ChatViewModel(
    private val session: AgentSession
) : ViewModel() {
    
    // UI State
    val uiState: StateFlow<ChatUiState>
    
    // Messages (Compose observable list)
    val messages: List<ChatMessage>
    
    // Task banner state
    val taskBannerState: StateFlow<TaskBannerState>
    
    // Actions
    fun sendMessage(text: String)
    fun stopTask()
    fun clearConversation()
}

data class ChatUiState(
    val inputState: InputState = InputState.Idle,
    val showEmptyState: Boolean = true
)
```

### Screen Components

| Component | File | Purpose |
|-----------|------|---------|
| **ChatHeader** | `ChatHeader.kt` | Minimal header with app title (long-press for settings) |
| **TaskBanner** | `TaskBanner.kt` | Shows current task context with animated status dot |
| **MessageBubble** | `MessageBubble.kt` | User/Agent message bubbles with proper styling |
| **StreamingText** | `StreamingText.kt` | Text with blinking cursor during streaming |
| **ThinkingIndicator** | `ThinkingIndicator.kt` | Animated dots while agent is processing |
| **ActionCard** | `ActionCard.kt` | Tool execution cards with status states |
| **InputDock** | `InputDock.kt` | Input field with Send/Stop toggle |
| **EmptyState** | `EmptyState.kt` | First-launch experience with suggestions |

### Message Types

```kotlin
sealed interface ChatMessage {
    val id: String
    val timestamp: Long
    
    data class User(
        override val id: String,
        override val timestamp: Long,
        val text: String
    ) : ChatMessage
    
    data class Agent(
        override val id: String,
        override val timestamp: Long,
        val content: String,
        val state: AgentMessageState,
        val actions: List<ActionCardData>
    ) : ChatMessage
}

enum class AgentMessageState {
    Thinking,   // Before first MessageDelta
    Streaming,  // Receiving deltas
    Complete    // Task finished
}
```

### TaskBanner States

```kotlin
sealed interface TaskBannerState {
    data object Idle : TaskBannerState
    data class Working(val taskTitle: String, val phase: String? = null) : TaskBannerState
    data class Completed(val summary: String) : TaskBannerState
    data class Error(val message: String) : TaskBannerState
}
```

### ActionCard States

```kotlin
enum class ActionState {
    Proposed,   // Tool call proposed (dashed border)
    Executing,  // Currently running (pulsing)
    Success,    // Completed successfully (green)
    Failed,     // Failed (red)
    Skipped     // Skipped (muted)
}
```

### Settings Sheet

Modal bottom sheet for configuration:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    currentModel: String,
    onModelChange: (String) -> Unit,
    maxTurns: Int,
    onMaxTurnsChange: (Int) -> Unit,
    onClearConversation: () -> Unit
)
```

**Settings Items:**
- Model selection (GPT-4o, GPT-4o-mini, GPT-4-turbo)
- Max turns (10, 20, 50)
- Accessibility service status
- Overlay permission status
- Clear conversation
- About & Debug

---

## File Structure

```
app/src/main/kotlin/com/moonkey/androidagent/
├── app/
│   ├── MainActivity.kt              # Compose entry, ChatViewModel setup
│   └── AgentService.kt              # AccessibilityService
│
├── ui/
│   ├── theme/
│   │   ├── Color.kt                 # Light/Dark color schemes
│   │   ├── Shape.kt                 # Bubble shapes, card shapes
│   │   ├── Theme.kt                 # ChatTheme composable
│   │   └── Type.kt                  # Typography scale
│   │
│   ├── chat/
│   │   ├── ChatScreen.kt            # Main screen composable
│   │   ├── ChatViewModel.kt         # State management
│   │   ├── components/
│   │   │   ├── ChatHeader.kt        # Minimal header
│   │   │   ├── TaskBanner.kt        # Task context strip
│   │   │   ├── MessageBubble.kt     # User/Agent bubbles
│   │   │   ├── StreamingText.kt     # Text with cursor
│   │   │   ├── ThinkingIndicator.kt # Animated dots
│   │   │   ├── ActionCard.kt        # Tool execution card
│   │   │   ├── InputDock.kt         # Input area
│   │   │   └── EmptyState.kt        # First launch
│   │   └── model/
│   │       └── ChatMessage.kt       # UI data classes
│   │
│   ├── overlay/
│   │   └── SmartCapsuleManager.kt   # Floating overlay with streaming
│   │
│   ├── settings/
│   │   └── SettingsSheet.kt         # Configuration bottom sheet
│   │
│   └── screen/
│       └── AgentScreen.kt           # DEPRECATED (kept for reference)
│
└── util/
    └── StatusUtils.kt               # Status processing utilities
```

---

## Smart Capsule

The Smart Capsule is a floating overlay that follows users across all apps during agent execution.

### Features

- **Streaming text**: Shows live agent response
- **Status dot**: Color-coded with pulsing animation
- **Control buttons**: Pause, Stop, Open App
- **Morphing states**: Visual feedback through color and animation

### States

| State | Visual | Behavior |
|-------|--------|----------|
| **Thinking** | Pulsing glow, "Thinking..." | Agent processing |
| **Acting** | Status text | Shows current tool |
| **Streaming** | Live text | Agent response streaming |
| **Success** | Green flash | Task complete |
| **Error** | Red tint, shake | Something went wrong |
| **Paused** | Amber tint | User paused execution |

### Integration

The `SmartCapsuleManager` is called from `AgentService` (which collects the event stream):

```kotlin
// In AgentService
session.events.collect { event ->
    when (event) {
        is AgentEvent.TaskStarted -> capsuleManager.onTaskStarted(event.taskId, event.input)
        is AgentEvent.MessageDelta -> capsuleManager.onMessageDelta(event.turnId, event.delta)
        is AgentEvent.ActionExecuted -> capsuleManager.onActionExecuted(event.toolName, event.success)
        is AgentEvent.TaskCompleted -> capsuleManager.onTaskCompleted()
        // ...
    }
}
```

### Visual Specifications

| Property | Value |
|----------|-------|
| Height (compact) | 48dp |
| Width | Screen width - 32dp margins |
| Corner Radius | 24dp (capsule) |
| Background | White with subtle shadow |
| Status Dot | 8dp, color-coded |
| Typography | 14sp, Medium weight |
| Button Size | 40dp circular |

---

## Quick Reference

### MainActivity Setup

```kotlin
class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ChatViewModel
    private var showSettings by mutableStateOf(false)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Initialize ViewModel with session
        viewModel = ChatViewModel(session)
        
        setContent {
            ChatTheme {
                ChatScreen(
                    viewModel = viewModel,
                    onOpenSettings = { showSettings = true }
                )
                
                if (showSettings) {
                    SettingsSheet(
                        onDismiss = { showSettings = false },
                        // ... settings props
                    )
                }
            }
        }
    }
}
```

### Event → UI Mapping

| AgentEvent | UI Update |
|------------|-----------|
| `TaskStarted` | Add user message, show Task Banner, disable input |
| `TurnPhaseChanged` | Update Task Banner subtitle |
| `MessageDelta` | Append to agent bubble, show streaming cursor |
| `ActionExecuted` | Add action card to agent bubble |
| `TaskCompleted` | Mark bubble complete, enable input, show "Done" |
| `SessionError` | Show error in Task Banner, enable input |

### Material 3 Components Used

| Component | Usage |
|-----------|-------|
| `OutlinedTextField` | Chat input |
| `FilledIconButton` | Send/Stop button |
| `Surface` | Bubbles, cards, banner |
| `LazyColumn` | Message list |
| `AnimatedVisibility` | Entry/exit animations |
| `ModalBottomSheet` | Settings sheet |

### Status Flow

```
AgentService.session.events
        │
        ├──► SmartCapsuleManager (overlay updates)
        │
        └──► ChatViewModel (message list updates)
                    │
                    └──► ChatScreen recomposition
```

---

## References

- [Jetpack Compose Documentation](https://developer.android.com/jetpack/compose)
- [Material 3 for Compose](https://developer.android.com/jetpack/compose/designsystems/material3)
- [Compose BOM](https://developer.android.com/jetpack/compose/bom)
- [UI Final Design](../todo/chat_design/ui_design/ui_final_design.md) - Full design specification

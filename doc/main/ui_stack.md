# Android Agent UI Stack

> This document describes the UI architecture, design system, and component structure.
> Last updated: 2026-01-27

## Table of Contents

1. [Overview](#overview)
2. [Tech Stack](#tech-stack)
3. [Design System](#design-system)
4. [Component Architecture](#component-architecture)
5. [Session History UI](#session-history-ui)
6. [File Structure](#file-structure)
7. [Smart Capsule](#smart-capsule)
8. [Edge Glow](#edge-glow)
9. [Action Visualizer](#action-visualizer)
10. [Quick Reference](#quick-reference)

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
| **Session History** | Browse and resume past conversations |

### Key Components

```
┌────────────────────────────────────────────────────────────────┐
│                        MainActivity                             │
│  (Compose entry point, ChatViewModel, event collection)         │
├────────────────────────────────────────────────────────────────┤
│                        ChatScreen                               │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ ChatHeader     │ [≡] Android Agent [+] (menu & new chat) │   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ TaskBanner    │ "Working on: ..." with status dot        │   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ MessageList   │ User/Agent bubbles, Action cards         │   │
│  ├───────────────┼─────────────────────────────────────────┤   │
│  │ InputDock     │ Text input + Send/Stop button            │   │
│  └─────────────────────────────────────────────────────────┘   │
├────────────────────────────────────────────────────────────────┤
│                   NavigationDrawer                              │
│  (Side drawer with session history + settings entry)            │
├────────────────────────────────────────────────────────────────┤
│                     SettingsSheet                               │
│  (Modal bottom sheet for model/config)                          │
├────────────────────────────────────────────────────────────────┤
│                    Overlay System                               │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ EdgeGlowManager     │ Ambient glow around screen edges   │   │
│  ├─────────────────────┼───────────────────────────────────┤   │
│  │ ActionVisualizer    │ Ripple/trail for touch actions     │   │
│  ├─────────────────────┼───────────────────────────────────┤   │
│  │ SmartCapsuleManager │ Floating capsule during execution  │   │
│  └─────────────────────────────────────────────────────────┘   │
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

Clean, modern palette inspired by ChatGPT and contemporary AI assistants:

```kotlin
// Light Theme
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
    
    // Surface - Pure white, unified with background
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
| Edge Glow | State-colored gradient glow on screen edges |
| Click Ripple | Expanding blue/purple circle at touch point |
| Swipe Trail | Animated line with dots showing gesture path |

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
| **ChatHeader** | `ChatHeader.kt` | Header with menu (≡) left, title center, new chat (+) right |
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
    llmBackend: LLMBackendType,
    onBackendChange: (LLMBackendType) -> Unit,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    selectedLocalModel: String,
    onLocalModelChange: (LocalModelOption) -> Unit,
    modelLoadingStatus: ModelLoadingStatus,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    maxTurns: Int,
    onMaxTurnsChange: (Int) -> Unit,
    screenshotInputEnabled: Boolean,
    onScreenshotInputChange: (Boolean) -> Unit,
    debugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    onAccessibilityClick: () -> Unit,
    onOverlayClick: () -> Unit,
    onDismiss: () -> Unit
)
```

**Settings Items:**
- LLM backend (Cloud/OpenAI vs Local)
- Cloud model selection (GPT-5.2, GPT-5.2 Pro)
- Local model selection (LFM 1.2B Instruct, LFM 350M) with download status
- Screenshot input toggle (compressed screenshots)
- API key (cloud only)
- Max turns (10, 20, 50)
- Accessibility service status
- Overlay permission status
- Debug mode toggle
- About & version info

---

## Session History UI

The session history UI enables users to browse, resume, and manage past chat sessions via a **navigation drawer**.

### Navigation Drawer

Side drawer containing session history and settings access. Opens via the menu button (≡) in the header.

```kotlin
@Composable
fun NavigationDrawerContent(
    sessions: List<SessionInfo>,
    currentModel: String,
    appVersion: String,
    onSessionSelect: (SessionInfo) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (SessionInfo) -> Unit,
    onSettingsClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
)
```

**Features:**
- Header with "Sessions" title and close button
- "New Conversation" button (primary action)
- Scrollable list of past sessions (sorted by last updated)
- Delete action on each session
- Settings entry at bottom with model/version info
- Proper system bar inset handling via `AppWindowInsets`

**Visual Layout:**
```
┌─────────────────────────────────┐
│  Sessions                   [X] │
├─────────────────────────────────┤
│  [ + New Conversation ]         │
├─────────────────────────────────┤
│  Recent                         │
│  ───────────────────────────────│
│  ┌───────────────────────────┐  │
│  │ "Check my email and..."   │  │
│  │  5 messages • 2 hours ago │🗑│
│  └───────────────────────────┘  │
│  ┌───────────────────────────┐  │
│  │ "Open Settings app"       │  │
│  │  3 messages • Yesterday   │🗑│
│  └───────────────────────────┘  │
│  ...                            │
├─────────────────────────────────┤
│  ⚙ Settings                     │
│    gpt-5.2 • v1.0               │
└─────────────────────────────────┘
```

### DrawerSessionItem

Individual session card in the drawer list.

**Displays:**
- Display title (summary or first user message, truncated)
- Message count and relative timestamp
- Delete button (trailing icon)

### TimeUtils

Utility for user-friendly relative time formatting.

```kotlin
object TimeUtils {
    /**
     * Format a timestamp as a relative time string.
     * Examples: "Just now", "5 min ago", "2 hours ago", "Yesterday", "Jan 15"
     */
    fun formatRelativeTime(timestamp: Long): String
}
```

**Output Examples:**

| Time Difference | Output |
|----------------|--------|
| < 1 minute | "Just now" |
| 1-59 minutes | "5 min ago" |
| 1-23 hours | "2 hours ago" |
| 1 day | "Yesterday" |
| 2-6 days | "3 days ago" |
| 7+ days (same year) | "Jan 15" |
| Different year | "Jan 15, 2025" |

### Integration with ChatViewModel

```kotlin
class ChatViewModel(
    private val session: AgentSession,
    private val sessionHistoryManager: SessionHistoryManager?
) : ViewModel() {
    
    // Session list state
    private val _sessions = mutableStateOf<List<SessionInfo>>(emptyList())
    val sessions: List<SessionInfo> by _sessions
    
    // Load sessions for display
    fun loadSessions() {
        viewModelScope.launch {
            _sessions.value = sessionHistoryManager?.listSessions() ?: emptyList()
        }
    }
    
    // Resume a selected session
    fun resumeSession(sessionInfo: SessionInfo) {
        viewModelScope.launch {
            sessionHistoryManager?.loadSession(sessionInfo.id)?.onSuccess { data ->
                sessionHistoryManager.resumeSession(data)
                // Restore messages to UI
                restoreMessages(data.session.messages)
            }
        }
    }
    
    // Delete a session
    fun deleteSession(sessionInfo: SessionInfo) {
        viewModelScope.launch {
            sessionHistoryManager?.deleteSession(sessionInfo.id)
            loadSessions() // Refresh list
        }
    }
    
    // Start fresh session
    fun startNewSession() {
        clearMessages()
        sessionHistoryManager?.startNewSession(model = currentModel)
    }
}
```

### Usage in MainActivity

```kotlin
// Drawer is integrated into ChatScreen - no separate state needed
ChatScreen(
    viewModel = viewModel,
    sessions = sessions,                      // Observed from viewModel
    currentModel = selectedModel,
    appVersion = BuildConfig.VERSION_NAME,
    onOpenSettings = { showSettings = true },
    onSessionSelect = { session ->
        viewModel.resumeSession(session)
    },
    onNewSession = {
        viewModel.startNewSession()
    },
    onDeleteSession = { session ->
        viewModel.deleteSession(session)
    },
    onLoadSessions = {
        viewModel.loadSessions()
    }
)
```

The drawer opens automatically when the user taps the menu (≡) button in the header. Sessions are loaded when the drawer opens via the `onLoadSessions` callback.

### Session History UI Components Table

| Component | File | Purpose |
|-----------|------|---------|
| **NavigationDrawer** | `ui/navigation/NavigationDrawer.kt` | Side drawer with history + settings |
| **TimeUtils** | `ui/session/TimeUtils.kt` | Relative time formatting |

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
│   │   ├── Type.kt                  # Typography scale
│   │   └── WindowInsets.kt          # AppWindowInsets for consistent inset handling
│   │
│   ├── chat/
│   │   ├── ChatScreen.kt            # Main screen with drawer integration
│   │   ├── ChatViewModel.kt         # State management
│   │   ├── components/
│   │   │   ├── ChatHeader.kt        # Header: [≡] Title [+]
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
│   ├── navigation/
│   │   └── NavigationDrawer.kt      # Side drawer with history + settings
│   │
│   ├── overlay/
│   │   ├── SmartCapsuleManager.kt   # Floating overlay with streaming
│   │   ├── SmartCapsuleLayoutBuilder.kt # Capsule view construction
│   │   ├── EdgeGlowManager.kt       # Edge glow effect during execution
│   │   ├── EdgeGlowView.kt          # Custom glow rendering view
│   │   ├── model/
│   │   │   └── GlowState.kt         # Glow state definitions
│   │   └── visualizer/
│   │       ├── ActionVisualizerManager.kt  # Touch action visualization
│   │       ├── ClickRippleView.kt          # Ripple effect for clicks
│   │       └── SwipeTrailView.kt           # Trail effect for swipes
│   │
│   ├── session/                     # Session history utilities
│   │   └── TimeUtils.kt             # Relative time formatting
│   │
│   ├── settings/
│   │   ├── SettingsSheet.kt         # Configuration bottom sheet
│   │   ├── SettingsModels.kt        # Settings data + defaults
│   │   ├── SettingsDropdowns.kt     # Model/backend dropdowns
│   │   └── SettingsWidgets.kt       # Shared settings UI widgets
│   │
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

## Edge Glow

The Edge Glow provides ambient visual feedback showing the agent is actively controlling the device. It displays a glowing border around the screen edges that changes color based on the agent's state.

### Features

- **Full-screen edge glow** with gradient fade from edges
- **State-based colors** matching agent execution phases
- **Pulse animation** when active or executing
- **Touch pass-through** (doesn't block interaction)
- **Display cutout handling** for notched devices
- **Auto-hide** after success state (2 seconds)

### Glow States

| State | Color | Hex | Behavior |
|-------|-------|-----|----------|
| **Active** | Primary Blue | `#2563EB` | Pulsing animation |
| **Executing** | Light Blue | `#3B82F6` | Pulsing animation |
| **Success** | Teal | `#0D9488` | Static, auto-hides after 2s |
| **Error** | Red | `#DC2626` | Static |
| **Paused** | Amber | `#F59E0B` | Static |

### Integration

The `EdgeGlowManager` is called from `AgentService` to show ambient feedback:

```kotlin
// In AgentService
private var edgeGlowManager: EdgeGlowManager? = null

// Show glow when task starts
edgeGlowManager?.show(GlowState.Active)

// Update state during execution
edgeGlowManager?.updateState(GlowState.Executing)

// Show success and auto-hide
edgeGlowManager?.updateState(GlowState.Success)

// Or hide manually
edgeGlowManager?.hide()
```

### Visibility Control

The edge glow is only visible when the main app is **not** in the foreground. This prevents visual clutter when the user is viewing the chat interface.

```kotlin
// In AgentService - track foreground state
private var isAppInForeground = false

// Show/hide based on foreground state
if (!isAppInForeground && shouldShowGlow) {
    edgeGlowManager?.show(currentGlowState)
} else {
    edgeGlowManager?.hide()
}
```

### Z-Order

The edge glow should be added **before** SmartCapsule so it renders below it in the overlay stack.

---

## Action Visualizer

The Action Visualizer provides visual feedback when the agent performs touch actions (clicks, swipes, scrolls). It helps users understand where and how the agent interacts with the screen.

### Features

- **Ripple effect** for tap/click actions
- **Trail animation** for swipe/scroll actions
- **Non-intrusive** - passes all touch events through
- **Automatic cleanup** after animation completes
- **Color-coded** actions (different colors for different action types)

### Visualization Types

#### Click Ripple (`ClickRippleView`)

Expanding circle animation for tap/click visualization.

| Property | Value |
|----------|-------|
| Initial radius | 8dp |
| Final radius | 48dp |
| Duration | 500ms |
| Animation | EaseOut (fast start, slow end) |
| Click color | Blue (`#2563EB`) at 60% opacity |
| Long press color | Purple (`#7C3AED`) at 60% opacity |

#### Swipe Trail (`SwipeTrailView`)

Line drawing animation for swipe/scroll visualization.

| Property | Value |
|----------|-------|
| Line width | 4dp |
| Start dot radius | 8dp |
| End dot radius | 6dp |
| Swipe color | Light Blue (`#3B82F6`) at 50% opacity |
| Scroll color | Indigo (`#6366F1`) at 50% opacity |
| Animation | Linear, matches gesture duration |

### Integration

The `ActionVisualizerManager` is called from `AccessibilityPlatform` right before dispatching gestures:

```kotlin
// In AccessibilityPlatform
class AccessibilityPlatform(
    private val service: AccessibilityService,
    private val visualizer: ActionVisualizerManager? = null
) {
    private suspend fun performTap(x: Float, y: Float): ActionResult {
        visualizer?.showClick(x, y)
        // ... dispatch gesture
    }
    
    private suspend fun performSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long
    ): ActionResult {
        visualizer?.showSwipe(startX, startY, endX, endY, durationMs)
        // ... dispatch gesture
    }
}
```

### API Reference

```kotlin
class ActionVisualizerManager(context: AccessibilityService) {
    /** Enable/disable visualization */
    var enabled: Boolean
    
    /** Show click ripple at coordinates */
    fun showClick(x: Float, y: Float, longPress: Boolean = false)
    
    /** Show swipe trail between coordinates */
    fun showSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long)
    
    /** Show scroll visualization (indigo color) */
    fun showScrollAsSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long)
    
    /** Clear all active visualizations */
    fun clearAll()
    
    /** Release resources */
    fun dispose()
}
```

### Overlay Components Table

| Component | File | Purpose |
|-----------|------|---------|
| **SmartCapsuleManager** | `ui/overlay/SmartCapsuleManager.kt` | Floating capsule behavior + updates |
| **SmartCapsuleLayoutBuilder** | `ui/overlay/SmartCapsuleLayoutBuilder.kt` | Capsule view construction |
| **EdgeGlowManager** | `ui/overlay/EdgeGlowManager.kt` | Manages edge glow lifecycle |
| **EdgeGlowView** | `ui/overlay/EdgeGlowView.kt` | Custom glow rendering |
| **GlowState** | `ui/overlay/model/GlowState.kt` | State enum with colors |
| **ActionVisualizerManager** | `ui/overlay/visualizer/ActionVisualizerManager.kt` | Action visualization orchestrator |
| **ClickRippleView** | `ui/overlay/visualizer/ClickRippleView.kt` | Ripple effect view |
| **SwipeTrailView** | `ui/overlay/visualizer/SwipeTrailView.kt` | Swipe trail view |

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
                val sessions by viewModel.sessions.collectAsStateWithLifecycle()
                
                ChatScreen(
                    viewModel = viewModel,
                    sessions = sessions,
                    currentModel = selectedModel,
                    appVersion = BuildConfig.VERSION_NAME,
                    onOpenSettings = { showSettings = true },
                    onSessionSelect = { viewModel.resumeSession(it) },
                    onNewSession = { viewModel.startNewSession() },
                    onDeleteSession = { viewModel.deleteSession(it) },
                    onLoadSessions = { viewModel.loadSessions() }
                )
                
                if (showSettings) {
                    ModalBottomSheet(onDismissRequest = { showSettings = false }) {
                        SettingsSheet(/* ... settings props */)
                    }
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
| `LazyColumn` | Message list, session list |
| `AnimatedVisibility` | Entry/exit animations |
| `ModalNavigationDrawer` | Navigation drawer with session history |
| `ModalDrawerSheet` | Drawer container with inset handling |
| `ModalBottomSheet` | Settings sheet |

### Status Flow

```
AgentService.session.events
        │
        ├──► EdgeGlowManager (ambient glow state)
        │
        ├──► SmartCapsuleManager (overlay updates)
        │
        └──► ChatViewModel (message list updates)
                    │
                    └──► ChatScreen recomposition

AccessibilityPlatform.performAction()
        │
        └──► ActionVisualizerManager (touch visualization)
```

---

## References

- [Jetpack Compose Documentation](https://developer.android.com/jetpack/compose)
- [Material 3 for Compose](https://developer.android.com/jetpack/compose/designsystems/material3)
- [Compose BOM](https://developer.android.com/jetpack/compose/bom)
- [UI Final Design](../todo/chat_design/ui_design/ui_final_design.md) - Full design specification

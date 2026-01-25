# UI Tech Design

> This document covers the technical implementation: tech stack, code structure, state management, and integration.

## Table of Contents

1. [Tech Stack](#tech-stack)
2. [File Structure](#file-structure)
3. [Component Architecture](#component-architecture)
4. [State Management](#state-management)
5. [Session History Integration](#session-history-integration)
6. [Overlay Integration](#overlay-integration)
7. [Quick Reference](#quick-reference)

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
│   │   ├── SessionListSheet.kt      # DEPRECATED (use NavigationDrawer)
│   │   ├── SessionListItem.kt       # Individual session card
│   │   └── TimeUtils.kt             # Relative time formatting
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

### Components Table

| Component | File | Purpose |
|-----------|------|---------|
| **ChatHeader** | `ui/chat/components/ChatHeader.kt` | Header: [≡] Title [+] |
| **TaskBanner** | `ui/chat/components/TaskBanner.kt` | Task context strip |
| **MessageBubble** | `ui/chat/components/MessageBubble.kt` | User/Agent bubbles |
| **StreamingText** | `ui/chat/components/StreamingText.kt` | Text with cursor |
| **ThinkingIndicator** | `ui/chat/components/ThinkingIndicator.kt` | Animated dots |
| **ActionCard** | `ui/chat/components/ActionCard.kt` | Tool execution card |
| **InputDock** | `ui/chat/components/InputDock.kt` | Input area |
| **EmptyState** | `ui/chat/components/EmptyState.kt` | First launch |
| **NavigationDrawer** | `ui/navigation/NavigationDrawer.kt` | Side drawer with history + settings |
| **TimeUtils** | `ui/session/TimeUtils.kt` | Relative time formatting |

### Overlay Components Table

| Component | File | Purpose |
|-----------|------|---------|
| **EdgeGlowManager** | `ui/overlay/EdgeGlowManager.kt` | Manages edge glow lifecycle |
| **EdgeGlowView** | `ui/overlay/EdgeGlowView.kt` | Custom glow rendering |
| **GlowState** | `ui/overlay/model/GlowState.kt` | State enum with colors |
| **ActionVisualizerManager** | `ui/overlay/visualizer/ActionVisualizerManager.kt` | Action visualization orchestrator |
| **ClickRippleView** | `ui/overlay/visualizer/ClickRippleView.kt` | Ripple effect view |
| **SwipeTrailView** | `ui/overlay/visualizer/SwipeTrailView.kt` | Swipe trail view |

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

---

## State Management

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

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    currentModel: String,
    onModelChange: (String) -> Unit,
    maxTurns: Int,
    onMaxTurnsChange: (Int) -> Unit
    // Notes:
    // - Has custom header with close button and display cutout handling
    // - The previous onClearConversation callback was removed to decouple
    //   settings UI from conversation lifecycle. Clearing a conversation
    //   is now handled from the main chat surface instead of this sheet.
)
```

---

## Session History Integration

### NavigationDrawer API

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

### TimeUtils

```kotlin
object TimeUtils {
    /**
     * Format a timestamp as a relative time string.
     * Examples: "Just now", "5 min ago", "2 hours ago", "Yesterday", "Jan 15"
     */
    fun formatRelativeTime(timestamp: Long): String
}
```

### ChatViewModel Session Management

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

---

## Overlay Integration

### Smart Capsule

The `SmartCapsuleManager` is called from `AgentService`:

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

### Edge Glow

The `EdgeGlowManager` is called from `AgentService`:

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

Foreground state tracking:

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

### Action Visualizer

The `ActionVisualizerManager` is called from `AccessibilityPlatform`:

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

### Action Visualizer API

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

### Z-Order

The edge glow should be added **before** SmartCapsule so it renders below it in the overlay stack.

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
                    ModalBottomSheet(
                        onDismissRequest = { showSettings = false },
                        dragHandle = {}  // Custom header in SettingsSheet
                    ) {
                        SettingsSheet(/* ... settings props */)
                    }
                }
            }
        }
    }
}
```

### Status Flow (Technical)

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

---

## Related Docs

- [UI User Interaction](user_interaction.md) - Pages, components, user behaviors
- [UI Style Guide](style.md) - Design system and visual specifications

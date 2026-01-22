# UI Implementation Plan

> **Status**: Ready for Implementation  
> **Date**: 2026-01-21  
> **Target**: Phase 5 — World-Class Chat Experience  
> **Design Reference**: `ui_final_design.md`  
> **Backend Status**: Streaming infrastructure complete (see `current_impl_status.md`)

---

## Executive Summary

This plan transforms the current config-based `AgentScreen` into a chat-first conversational interface. The backend streaming infrastructure (`MessageDelta`, `TaskStarted`, `TaskCompleted`) is already complete. This plan focuses on UI implementation.

**Scope**:
- Replace `AgentScreen.kt` with new `ChatScreen` composable
- Enhance `OverlayManager.kt` to `SmartCapsuleManager.kt` with streaming support
- Update theme with new color palette and shapes
- Add settings bottom sheet for configuration

---

## Prerequisites

### Verified ✅

| Prerequisite | Status | Location |
|--------------|--------|----------|
| `MessageDelta` event | ✅ | `protocol/AgentEvent.kt:127` |
| `TaskStarted` event | ✅ | `protocol/AgentEvent.kt:72` |
| `TaskCompleted` event | ✅ | `protocol/AgentEvent.kt:82` |
| `ActionExecuted` event | ✅ | `protocol/AgentEvent.kt:162` |
| `Op.UserInput` | ✅ | `protocol/Op.kt` |
| `session.events: Flow<AgentEvent>` | ✅ | `session/AgentSession.kt:114` |
| `SessionState.Idle` | ✅ | `protocol/SessionState.kt` |
| Streaming in Agent | ✅ | `agent/Agent.kt` emits `MessageDelta` |

### Current UI Files

| File | Status | Migration Plan |
|------|--------|----------------|
| `ui/screen/AgentScreen.kt` | DEPRECATED | Replace with `ChatScreen.kt` |
| `ui/overlay/OverlayManager.kt` | ENHANCE | Evolve to `SmartCapsuleManager.kt` |
| `ui/theme/Color.kt` | UPDATE | Add new color palette |
| `ui/theme/Theme.kt` | UPDATE | Add dark theme, update scheme |
| `ui/theme/Type.kt` | UPDATE | Add new typography scale |
| `app/MainActivity.kt` | UPDATE | Use new ChatScreen + ViewModel |

---

## Phase 5.1: Core Chat (MVP)

### Step 1: Data Models

**File**: `ui/chat/model/ChatMessage.kt` (NEW)

Create UI data classes that map to AgentEvents:

```kotlin
// ChatMessage.kt
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
    Complete    // Task turn finished
}

data class ActionCardData(
    val id: String,
    val toolName: String,
    val description: String,
    val state: ActionState,
    val resultSummary: String? = null
)

enum class ActionState {
    Proposed, Executing, Success, Failed, Skipped
}
```

**Validation**:
- [ ] Compile check: Data classes serialize correctly
- [ ] Mapping from `AgentEvent.ActionExecuted` to `ActionCardData`

---

### Step 2: ChatViewModel

**File**: `ui/chat/ChatViewModel.kt` (NEW)

Manages chat state and event collection. Key responsibilities:
- Collect from `session.events`
- Maintain message list with `mutableStateListOf<ChatMessage>()`
- Handle streaming text accumulation
- Manage input state (Idle/Working)

**Implementation**:

```kotlin
class ChatViewModel(
    private val session: AgentSession
) : ViewModel() {
    
    // UI State
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    // Messages (Compose observable)
    private val _messages = mutableStateListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages
    
    // Task banner state
    private val _taskBannerState = MutableStateFlow<TaskBannerState>(TaskBannerState.Idle)
    val taskBannerState: StateFlow<TaskBannerState> = _taskBannerState.asStateFlow()
    
    // Streaming accumulator
    private val streamingBuffer = StringBuilder()
    private var currentAgentMessageId: String? = null
    
    init {
        collectEvents()
    }
    
    private fun collectEvents() {
        viewModelScope.launch {
            session.events.collect { event ->
                when (event) {
                    is AgentEvent.TaskStarted -> handleTaskStarted(event)
                    is AgentEvent.MessageDelta -> handleMessageDelta(event)
                    is AgentEvent.ActionExecuted -> handleActionExecuted(event)
                    is AgentEvent.TaskCompleted -> handleTaskCompleted(event)
                    is AgentEvent.TurnPhaseChanged -> handlePhaseChanged(event)
                    is AgentEvent.SessionError -> handleError(event)
                    else -> { /* Ignore other events */ }
                }
            }
        }
    }
    
    fun sendMessage(text: String) {
        viewModelScope.launch {
            session.submit(Op.UserInput(text))
        }
    }
    
    fun stopTask() {
        viewModelScope.launch {
            session.submit(Op.Interrupt)
        }
    }
}
```

**Key Event Handling**:

| Event | Action |
|-------|--------|
| `TaskStarted` | Add user message, create agent placeholder (Thinking), update banner |
| `MessageDelta` | Append to `streamingBuffer`, update agent message (Streaming) |
| `ActionExecuted` | Add action card to current agent message |
| `TaskCompleted` | Mark agent message Complete, enable input, update banner |
| `TurnPhaseChanged` | Update banner subtitle (optional) |
| `SessionError` | Show error in banner |

**Validation**:
- [ ] Unit test: `MessageDelta` accumulation
- [ ] Unit test: State transitions (Idle → Working → Idle)
- [ ] Integration test: Full task flow

---

### Step 3: Theme Updates

**File**: `ui/theme/Color.kt` (UPDATE)

Add new color palette from design:

```kotlin
// ============================================
// Chat UI Colors (NEW - see ui_final_design.md §5.1)
// ============================================

// Primary - Confident blue
val ChatPrimary = Color(0xFF2563EB)
val ChatOnPrimary = Color.White
val ChatPrimaryContainer = Color(0xFFDBEAFE)

// Success - Teal
val ChatSuccess = Color(0xFF0D9488)
val ChatSuccessBg = Color(0xFFCCFBF1)

// Error - Red
val ChatError = Color(0xFFDC2626)
val ChatErrorBg = Color(0xFFFEE2E2)

// Surface - Clean minimal
val ChatSurface = Color(0xFFFAFAFA)
val ChatSurfaceVariant = Color(0xFFF5F5F5)
val ChatOnSurface = Color(0xFF171717)
val ChatOnSurfaceVariant = Color(0xFF525252)

// Outline
val ChatOutline = Color(0xFFD4D4D4)
val ChatOutlineVariant = Color(0xFFE5E5E5)
```

**File**: `ui/theme/Shape.kt` (NEW)

```kotlin
val AgentShapes = Shapes(
    small = RoundedCornerShape(8.dp),    // Chips, small cards
    medium = RoundedCornerShape(12.dp),  // Action cards
    large = RoundedCornerShape(20.dp)    // Bubbles, sheets
)

// Special shapes for chat bubbles
val BubbleShapeUser = RoundedCornerShape(
    topStart = 20.dp, topEnd = 20.dp,
    bottomStart = 20.dp, bottomEnd = 6.dp
)

val BubbleShapeAgent = RoundedCornerShape(
    topStart = 6.dp, topEnd = 20.dp,
    bottomStart = 20.dp, bottomEnd = 20.dp
)
```

**Validation**:
- [ ] Visual test: Color contrast meets WCAG AA (4.5:1)
- [ ] Visual test: Bubble shapes render correctly

---

### Step 4: Chat Components

#### 4.1 ChatHeader

**File**: `ui/chat/components/ChatHeader.kt` (NEW)

Minimal header with brand. Long-press opens settings.

```kotlin
@Composable
fun ChatHeader(
    onSettingsLongPress: () -> Unit,
    modifier: Modifier = Modifier
)
```

| Spec | Value |
|------|-------|
| Height | 56dp |
| Padding | 20dp horizontal |
| Typography | 20sp, SemiBold |
| Interaction | Long-press for settings |

---

#### 4.2 TaskBanner

**File**: `ui/chat/components/TaskBanner.kt` (NEW)

Context strip showing current task status.

**States**:
- `Idle`: Hidden
- `Working`: "Working on: {input}" with pulsing dot
- `Completed`: "✓ Done" → fades out after 2s
- `Error`: "⚠ Something went wrong" with red dot

---

#### 4.3 MessageBubble

**File**: `ui/chat/components/MessageBubble.kt` (NEW)

Handles both User and Agent messages.

**User Bubble**:
| Spec | Value |
|------|-------|
| Background | ChatPrimary (#2563EB) |
| Text Color | White |
| Max Width | 85% of screen |
| Alignment | End (right) |
| Shape | `BubbleShapeUser` |

**Agent Bubble**:
| Spec | Value |
|------|-------|
| Background | ChatSurfaceVariant (#F5F5F5) |
| Text Color | ChatOnSurface (#171717) |
| Max Width | 90% of screen |
| Alignment | Start (left) |
| Shape | `BubbleShapeAgent` |
| Streaming Cursor | Blinking `█` when streaming |

---

#### 4.4 StreamingText

**File**: `ui/chat/components/StreamingText.kt` (NEW)

Text with animated blinking cursor during streaming.

```kotlin
@Composable
fun StreamingText(
    text: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
)
```

Animation: 530ms blink cycle, Linear easing.

---

#### 4.5 ThinkingIndicator

**File**: `ui/chat/components/ThinkingIndicator.kt` (NEW)

Three animated dots showing agent is processing (before first delta).

Animation: 600ms per dot, staggered 200ms.

---

#### 4.6 ActionCard

**File**: `ui/chat/components/ActionCard.kt` (NEW)

Displays tool executions inline with chat.

**States**:
| State | Border | Background | Icon |
|-------|--------|------------|------|
| Proposed | Dashed, muted | Surface | Tool icon |
| Executing | Solid, primary | Primary/5% | Spinner |
| Success | Solid, success | Success/10% | ✓ |
| Failed | Solid, error | Error/10% | ✗ |
| Skipped | Dashed, muted | Surface | — |

---

#### 4.7 InputDock

**File**: `ui/chat/components/InputDock.kt` (NEW)

Always-visible input area at bottom.

**States**:
- `Idle`: Editable text field + Send button
- `Working`: Disabled field ("Agent is working...") + Stop button

```kotlin
@Composable
fun InputDock(
    state: InputState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
)
```

Button morphs between Send (➤) and Stop (■) with `Crossfade`.

---

### Step 5: ChatScreen Composable

**File**: `ui/chat/ChatScreen.kt` (NEW)

Main screen composable orchestrating all components.

```kotlin
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val taskBannerState by viewModel.taskBannerState.collectAsStateWithLifecycle()
    val messages = viewModel.messages
    
    Scaffold(
        topBar = {
            ChatHeader(onSettingsLongPress = onOpenSettings)
        },
        bottomBar = {
            InputDock(
                state = uiState.inputState,
                onSend = viewModel::sendMessage,
                onStop = viewModel::stopTask
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            TaskBanner(state = taskBannerState)
            
            if (messages.isEmpty() && uiState.showEmptyState) {
                EmptyState(onSuggestionClick = viewModel::sendMessage)
            } else {
                MessageList(messages = messages)
            }
        }
    }
}
```

**Conversation Area**: `LazyColumn` with:
- `items(messages)` → `MessageBubble`
- Auto-scroll to bottom on new messages
- `animateItemPlacement()` for smooth entry

---

### Step 6: MainActivity Integration

**File**: `app/MainActivity.kt` (UPDATE)

Replace current UI with ChatScreen and ViewModel.

**Changes**:
1. Replace `AgentUiState` with `ChatViewModel`
2. Move API key to Settings (not on main screen)
3. Store API key in `SharedPreferences` or `DataStore`
4. Initialize `AgentSession` on first message if not already running

```kotlin
class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ChatViewModel
    private var showSettings by mutableStateOf(false)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            AgentTheme {
                ChatScreen(
                    viewModel = viewModel,
                    onOpenSettings = { showSettings = true }
                )
                
                if (showSettings) {
                    SettingsSheet(
                        onDismiss = { showSettings = false }
                    )
                }
            }
        }
    }
}
```

**Session Lifecycle**: The ViewModel should handle session creation. When user sends first message:
1. Check if session exists and is in `Idle` or `Created` state
2. If no session, create one with stored API key
3. Submit `Op.UserInput`

---

### Step 7: Smart Capsule Streaming

**File**: `ui/overlay/SmartCapsuleManager.kt` (RENAMED from OverlayManager.kt)

Enhance overlay to support streaming text.

**Changes from current `OverlayManager`**:

| Current | New |
|---------|-----|
| Status text only | Streaming text support |
| Pause/Stop buttons | Pause/Stop/Open App buttons |
| Static dot color | Pulsing animation |
| Fixed height | Expandable (compact/expanded) |

**New Methods**:

```kotlin
class SmartCapsuleManager(...) {
    private val streamingText = StringBuilder()
    private var currentTurnId: String? = null
    
    // Called by AgentService when collecting AgentEvent.MessageDelta
    fun onMessageDelta(turnId: String, delta: String) {
        if (turnId != currentTurnId) {
            streamingText.clear()
            currentTurnId = turnId
        }
        streamingText.append(delta)
        updateStatusText(streamingText.toString())
    }
    
    fun onTaskStarted(taskId: String, userInput: String) {
        streamingText.clear()
        currentTurnId = null
        show()
        setStatusDot(color = colorPrimary, pulsing = true)
        setStatusText("Working on: ${userInput.take(30)}...")
    }
    
    fun onActionExecuted(toolName: String, success: Boolean) {
        setStatusDot(color = if (success) colorSuccess else colorError)
        setStatusText("$toolName ${if (success) "✓" else "✗"}")
    }
    
    fun onTaskCompleted() {
        setStatusDot(color = colorSuccess, pulsing = false)
        setStatusText("✓ Done")
        handler.postDelayed({ hide() }, 3000)
    }
}
```

**Integration Point**: `AgentService` collects events and forwards to `SmartCapsuleManager`:

```kotlin
// In AgentService
session.events.collect { event ->
    when (event) {
        is AgentEvent.TaskStarted -> capsuleManager.onTaskStarted(event.taskId, event.input)
        is AgentEvent.MessageDelta -> capsuleManager.onMessageDelta(event.turnId, event.delta)
        is AgentEvent.ActionExecuted -> capsuleManager.onActionExecuted(event.toolName, event.success)
        is AgentEvent.TaskCompleted -> capsuleManager.onTaskCompleted()
        // ... forward to statusFlow for any legacy UI
    }
}
```

**"Open App" Button**:

```kotlin
private fun openAgentApp() {
    val intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    startActivity(intent)
}
```

---

## Phase 5.2: Polish

### Step 8: Empty State

**File**: `ui/chat/components/EmptyState.kt` (NEW)

First-launch experience with suggestions.

```kotlin
@Composable
fun EmptyState(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
)
```

**Suggestions** (tappable chips):
- 💡 "Check my unread emails"
- 📱 "Turn on Do Not Disturb"  
- 🔍 "Search for nearby restaurants"

---

### Step 9: Settings Sheet

**File**: `ui/settings/SettingsSheet.kt` (NEW)

Bottom sheet for configuration (accessed via long-press or swipe).

**Sections**:
1. **Model Selection**: Dropdown (GPT-4o, etc.)
2. **Max Turns**: Dropdown (10, 20, 50)
3. **Accessibility Service**: Status + link to settings
4. **Overlay Permission**: Status + link to settings
5. **Clear Conversation**: Destructive action
6. **About & Debug**: Version, debug toggle

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    config: SessionConfig,
    onConfigChange: (SessionConfig) -> Unit,
    onClearConversation: () -> Unit,
    onDismiss: () -> Unit
)
```

---

### Step 10: Dark Theme

**File**: `ui/theme/Theme.kt` (UPDATE)

Add dark color scheme:

```kotlin
private val AgentDarkColorScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),      // Brighter blue
    onPrimary = Color(0xFF1E3A5F),
    secondary = Color(0xFF2DD4BF),    // Bright teal
    surface = Color(0xFF171717),
    onSurface = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFF262626),
    background = Color(0xFF0A0A0A),
    error = Color(0xFFF87171),
    outline = Color(0xFF404040)
)

@Composable
fun AgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) AgentDarkColorScheme else AgentLightColorScheme
    // ...
}
```

---

### Step 11: Animations

Add smooth animations per design spec:

| Animation | Duration | Easing | File |
|-----------|----------|--------|------|
| Message appear | 200ms | FastOutSlowIn | MessageBubble.kt |
| Streaming cursor | 530ms | Linear | StreamingText.kt |
| Thinking dots | 600ms/dot | EaseInOutSine | ThinkingIndicator.kt |
| Button state | 200ms | FastOutSlowIn | InputDock.kt |
| Action card state | 150ms | FastOutSlowIn | ActionCard.kt |
| Task Banner | 250ms | Spring | TaskBanner.kt |

---

## Phase 5.3: Advanced

### Step 12: Smart Capsule Expanded Mode

Add tap-to-expand functionality:
- Compact (48dp): Status dot + truncated text + buttons
- Expanded (280dp): Full streaming text + action cards + larger buttons

Animation: 250ms height transition with `FastOutSlowIn`.

---

### Step 13: Accessibility Audit

| Requirement | Implementation |
|-------------|----------------|
| Color contrast | 4.5:1 minimum |
| Touch targets | 48dp × 48dp minimum |
| Screen reader | `contentDescription` on all interactive elements |
| Focus order | Logical tab order |
| Reduced motion | Check `LocalReducedMotion.current` |
| Font scaling | Support up to 200% scale |

---

## File Structure Summary

```
app/src/main/kotlin/com/moonkey/androidagent/
├── ui/
│   ├── theme/
│   │   ├── Color.kt              # UPDATE: Add chat colors
│   │   ├── Type.kt               # UPDATE: Add new typography
│   │   ├── Shape.kt              # NEW: Bubble and card shapes
│   │   └── Theme.kt              # UPDATE: Add dark theme
│   │
│   ├── chat/                     # NEW DIRECTORY
│   │   ├── ChatScreen.kt         # Main screen composable
│   │   ├── ChatViewModel.kt      # State management
│   │   ├── components/
│   │   │   ├── ChatHeader.kt
│   │   │   ├── TaskBanner.kt
│   │   │   ├── MessageBubble.kt
│   │   │   ├── StreamingText.kt
│   │   │   ├── ActionCard.kt
│   │   │   ├── ThinkingIndicator.kt
│   │   │   ├── InputDock.kt
│   │   │   └── EmptyState.kt
│   │   └── model/
│   │       └── ChatMessage.kt
│   │
│   ├── overlay/
│   │   └── SmartCapsuleManager.kt  # RENAMED & ENHANCED
│   │
│   ├── settings/
│   │   └── SettingsSheet.kt        # NEW
│   │
│   └── screen/
│       └── AgentScreen.kt          # DEPRECATED (remove after migration)
│
├── app/
│   ├── MainActivity.kt             # UPDATE: Use ChatScreen
│   └── AgentService.kt             # UPDATE: Forward events to capsule
```

---

## Implementation Checklist

### Phase 5.1 (MVP) — P0

- [ ] **Data Models** (`ChatMessage.kt`)
- [ ] **ChatViewModel** with event handling
- [ ] **Theme Updates** (colors, shapes)
- [ ] **ChatHeader** component
- [ ] **TaskBanner** component (Working/Complete states)
- [ ] **MessageBubble** (User & Agent)
- [ ] **StreamingText** with cursor
- [ ] **ThinkingIndicator**
- [ ] **InputDock** with send/stop
- [ ] **ChatScreen** composable
- [ ] **MainActivity** integration
- [ ] **SmartCapsuleManager**: streaming text support
- [ ] **SmartCapsuleManager**: "Open App" button

### Phase 5.2 (Polish) — P1

- [ ] **EmptyState** with suggestions
- [ ] **SettingsSheet** bottom sheet
- [ ] **Dark theme**
- [ ] **ActionCard** (all states)
- [ ] **Message animations**
- [ ] **TaskBanner**: phase display
- [ ] **SmartCapsule**: pulsing animation

### Phase 5.3 (Advanced) — P2

- [ ] **SmartCapsule**: expanded mode
- [ ] **Accessibility audit**
- [ ] **Performance optimization** (virtualization)

---

## Testing Strategy

### Unit Tests

| Test | Coverage |
|------|----------|
| ChatViewModel event handling | MessageDelta accumulation, state transitions |
| TaskBannerState transitions | Idle → Working → Complete → Idle |
| ActionCardData mapping | From AgentEvent.ActionExecuted |

### Integration Tests

| Test | Coverage |
|------|----------|
| Full task flow | Send message → receive streaming → complete |
| Multi-round conversation | Multiple tasks in sequence |
| Error handling | SessionError display |

### Manual Testing

| Test | Coverage |
|------|----------|
| Visual review | Match design mockups |
| Dark theme | All components |
| Accessibility | TalkBack navigation |
| Performance | Smooth scrolling with 100+ messages |
| Overlay | Works in other apps |

---

## Migration Notes

### Breaking Changes

1. `AgentScreen.kt` is replaced by `ChatScreen.kt`
2. API key moves from main screen to Settings
3. `OverlayManager.kt` renamed to `SmartCapsuleManager.kt`

### Backward Compatibility

- `AgentService.statusFlow` remains for any external consumers
- `Op.Start` still works (deprecated, maps to `UserInput`)

### Rollback Plan

Keep `AgentScreen.kt` until Phase 5.1 is validated. Switch via feature flag if needed:

```kotlin
// MainActivity.kt
if (FeatureFlags.USE_CHAT_UI) {
    ChatScreen(viewModel, ...)
} else {
    AgentScreen(state, ...)
}
```

---

## Dependencies

No new external dependencies required. All components use existing:
- Jetpack Compose (via BOM 2024.12.01)
- Material 3
- Material Icons Extended
- Kotlin Coroutines/Flow

---

## Open Questions

1. **API Key Storage**: Use `SharedPreferences` or `DataStore`? (Recommend: `EncryptedSharedPreferences`)
2. **Session Persistence**: Should conversation history persist across app restarts? (Recommend: No for MVP)
3. **Typing Indicator on Other Side**: Should we show "User is typing" on capsule? (Recommend: No for MVP)

---

*Ready for implementation. Start with Phase 5.1 Data Models and ChatViewModel.*

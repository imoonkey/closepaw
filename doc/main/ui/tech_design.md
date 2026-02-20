# UI Tech Design

> Technical implementation: tech stack, code structure, state management.
> Last updated: 2026-02-20 (commit: 2493be6)

## Tech Stack

### Dependencies

| Library | Purpose |
|---------|---------|
| **Compose BOM** | Version management (`2024.12.01`) |
| **Material 3** | Modern design system |
| **Activity Compose** | `setContent {}` entry point |
| **Material Icons Extended** | Comprehensive icon set |

---

## File Structure

```
ui/
├── theme/
│   ├── Color.kt                 # Light/Dark color schemes
│   ├── Shape.kt                 # Bubble shapes, card shapes
│   ├── Theme.kt                 # ChatTheme composable
│   ├── Type.kt                  # Typography scale (AgentTypography)
│   └── WindowInsets.kt          # AppWindowInsets singleton
│
├── chat/
│   ├── ChatScreen.kt            # Main screen with drawer + capsule
│   ├── ChatViewModel.kt         # State management (messages, events)
│   ├── ChatEventReducer.kt      # Protocol events → UI mutations
│   ├── ChatSessionHistoryController.kt # Session list/resume/delete
│   ├── components/
│   │   ├── ChatHeader.kt        # Header: [≡] Title [+]
│   │   ├── MessageBubble.kt     # User/Agent bubbles (asymmetric shapes)
│   │   ├── StreamingText.kt     # Text with blinking cursor
│   │   ├── ThinkingIndicator.kt # 3 animated dots
│   │   ├── ActionCard.kt        # Tool execution card (state-colored)
│   │   └── EmptyState.kt        # First launch with suggestion chips
│   └── model/
│       └── ChatMessage.kt       # UI data classes
│
├── capsule/
│   ├── SmartCapsuleCompose.kt   # Compose capsule for main app
│   ├── SmartCapsuleSurface.kt   # 3-row surface layout
│   ├── SmartCapsuleSurfaceParts.kt # Row1/Row2/Row3 components
│   ├── CapsuleRenderSpec.kt     # Stateless: CapsuleMode → visual properties
│   ├── CapsuleNavSpec.kt        # Navigation button visibility spec
│   └── StatusIslandCompose.kt   # Compact status pill (Compose)
│
├── navigation/
│   └── NavigationDrawer.kt      # Side drawer (sessions + settings entry)
│
├── overlay/
│   ├── CapsuleStateHolder.kt    # Single source of truth (StateFlows)
│   ├── CapsuleOverlayHost.kt    # System overlay: capsule in WindowManager
│   ├── IslandOverlayHost.kt     # System overlay: compact island
│   ├── GlowOverlayHost.kt       # System overlay: edge glow (Canvas)
│   ├── VisualizerOverlayHost.kt # System overlay: click/swipe feedback
│   ├── OverlayComposeHost.kt    # Generic ComposeView → WindowManager wrapper
│   ├── ServiceLifecycleOwner.kt # LifecycleOwner for AccessibilityService
│   └── model/
│       ├── CapsuleMode.kt       # Sealed interface (Running, Takeover, etc.)
│       ├── CapsuleContext.kt    # MAIN_APP / SCREEN_VIEWING / BACKGROUND
│       ├── CapsuleColors.kt     # Status dot and glow colors
│       └── GlowState.kt         # State enum with hex colors
│
├── session/
│   ├── TimeUtils.kt             # Relative time formatting
│   └── ToolUi.kt                # Tool name formatting + icons
│
├── viewer/
│   └── VirtualDisplayViewerActivity.kt  # VD live preview (SurfaceView)
│
└── settings/
    ├── SettingsSheet.kt         # Modal bottom sheet
    ├── SettingsModels.kt        # LocalModelOption, ModelLoadingStatus
    ├── SettingsDropdowns.kt     # Backend/model/mode/turns dropdowns
    ├── SettingsDropdown.kt      # Generic reusable dropdown
    ├── SettingsWidgets.kt       # Header, Section, Row, StatusIndicator
    └── ApiKeyFields.kt          # API key input fields (masked)
```

---

## State Management

### ChatViewModel

> See: `ui/chat/ChatViewModel.kt`

```kotlin
class ChatViewModel {
    val uiState: StateFlow<ChatUiState>           // showEmptyState flag
    val messages: List<ChatMessage>                // mutableStateListOf (Compose observable)
    val sessions: StateFlow<List<SessionInfo>>

    private val streamingBuffer: StringBuilder
    private val chatStateLock: Any                 // Synchronized mutations
    private var currentAgentMessageId: String?
    private var eventCollectionJob: Job?
}
```

Key patterns:
- **`mutableStateListOf`** for messages — triggers Compose recomposition on mutation without StateFlow overhead
- **`chatStateLock`** for thread-safe event handling — protocol events arrive on coroutine dispatchers
- **Event reducer delegation** — `ChatEventReducer` handles all protocol → UI mapping

### ChatEventReducer

> See: `ui/chat/ChatEventReducer.kt`

Maps protocol events to UI mutations:

| Event | UI Mutation |
|-------|------------|
| `TaskStarted` | Add User message + Agent message (Thinking state) |
| `TurnStarted` | Clear streaming buffer |
| `MessageDelta` | Append to buffer, update Agent message to Streaming |
| `ActionProposed` | Add Action content block |
| `ActionExecuted` | Update action state + result summary |
| `TaskCompleted` | Append completion text, mark Complete |
| `SessionError` | Mark agent state Complete |
| `SupplementReceived` | Add user message for supplement |

### ChatMessage Model

```kotlin
sealed interface ChatMessage {
    data class User(id, timestamp, text: String)
    data class Agent(id, timestamp,
        contentBlocks: List<ContentBlock>,
        state: AgentMessageState)
}

sealed interface ContentBlock {
    data class Text(text: String)
    data class Action(data: ActionCardData)
}

enum class AgentMessageState { Thinking, Streaming, Complete }

data class ActionCardData(
    id, toolName, toolIcon, description,
    state: ActionState, resultSummary, expandedContent
)

enum class ActionState { Proposed, Executing, Success, Failed, Skipped }
```

### CapsuleStateHolder

Single source of truth for overlay/capsule state. See [overlay.md](overlay.md) for details.

---

## Component Architecture

### ChatScreen

> See: `ui/chat/ChatScreen.kt`

```kotlin
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    sessions: List<SessionInfo>,
    ...
) {
    ModalNavigationDrawer {
        NavigationDrawerContent(...)

        Scaffold(
            topBar = { ChatHeader(...) },
            bottomBar = { SmartCapsuleCompose(...) }
        ) {
            MessageList(...)  // LazyColumn, auto-scrolls to bottom
        }
    }
}
```

### Event → UI Flow

```
AgentService.session.events
    └──► ServiceOverlayController (mode-aware)
            ├── ACCESSIBILITY: EdgeGlow + SmartCapsule + ActionVisualizer
            └── VIRTUAL_DISPLAY: StatusIsland or SmartCapsule (context-dependent)
    └──► ChatViewModel (message list)
            └──► ChatEventReducer
                  └──► ChatScreen recomposition
```

### VirtualDisplayViewerActivity

> See: `ui/viewer/VirtualDisplayViewerActivity.kt`

Full-screen `SurfaceView` for live VD preview:
- `onStart`: `notifyViewerVisible()` → show capsule overlay, hide island, set `SCREEN_VIEWING` context
- `onStop`: `notifyViewerHidden()` → hide capsule, show island, `finish()`
- Touch events delegate to `AgentService`
- Pure container — all UI controls via Smart Capsule overlay

### Material 3 Components

| Component | Usage |
|-----------|-------|
| `OutlinedTextField` | Capsule input field |
| `FilledIconButton` | Send button |
| `Surface` | Bubbles, cards, capsule |
| `LazyColumn` | Message list |
| `ModalNavigationDrawer` | Session history |
| `ModalBottomSheet` | Settings |

---

## Overlay Compose Infrastructure

### OverlayComposeHost

> See: `ui/overlay/OverlayComposeHost.kt`

Wraps a Compose composable in a `ComposeView` for system overlay via `WindowManager`:

```kotlin
class OverlayComposeHost(service: AccessibilityService) {
    fun show()
    fun hide()
    fun updateLayoutParams(block: WindowManager.LayoutParams.() -> Unit)
    fun dispose()
}
```

Uses `ServiceLifecycleOwner` to bridge the AccessibilityService context into Compose lifecycle.

### ServiceLifecycleOwner

> See: `ui/overlay/ServiceLifecycleOwner.kt`

Implements `LifecycleOwner` + `SavedStateRegistryOwner` for services. Manual lifecycle events: `onCreate()` → Resume, `onDestroy()` → Destroy. Enables Compose in non-Activity contexts.

---

## Related Docs

- [User Interaction](user_interaction.md) - Pages, user behaviors
- [Style Guide](style.md) - Design system
- [Overlay](overlay.md) - Overlay implementation
- [History](../app/history.md) - Session persistence

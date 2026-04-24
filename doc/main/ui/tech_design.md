# UI Tech Design

> Technical implementation: tech stack, code structure, state management.
> Last updated: 2026-04-23 (Lucide icons, animation tween fixes)

## Tech Stack

### Dependencies

| Library | Purpose |
|---------|---------|
| **Compose BOM** | Version management (`2024.12.01`) |
| **Material 3** | Modern design system |
| **Activity Compose** | `setContent {}` entry point |
| **Material Icons Extended** | Comprehensive icon set |
| **Lucide Icons (CMP)** | `com.composables:icons-lucide-cmp:2.2.1` — modern line icons for UI glyphs |

---

## File Structure

```
ui/
├── theme/
│   ├── Color.kt                 # D1 palette (Paper / Ink / Claw / Moss / Amber / Rust)
│   ├── Shape.kt                 # ClosePawShapes — three Material radii (8 / 10 / 16dp)
│   ├── Theme.kt                 # ClosePawTheme composable + Material role mapping
│   ├── Tokens.kt                # ClosePawTokens (extras), ClosePawSpacing, Modifier.foldedPaper
│   ├── Motion.kt                # ClosePawMotion (durations, easings, reducedMotion())
│   ├── Type.kt                  # ClosePawTypography — Geist on every Material slot
│   └── WindowInsets.kt          # AppWindowInsets singleton
│
├── chat/
│   ├── ChatScreen.kt            # Main screen with drawer + capsule
│   ├── ChatViewModel.kt         # State management (messages, events)
│   ├── ChatEventReducer.kt      # Protocol events → UI mutations
│   ├── ChatSessionHistoryController.kt # Session list/resume/delete
│   ├── components/
│   │   ├── ChatHeader.kt        # Header: [≡] Title [+]
│   │   ├── MessageBubble.kt     # Dispatcher: User bubble | AgentRow (slim, ~90 lines post-uxfb-3)
│   │   ├── AgentRow.kt          # Agent row shell: [trace · CollapsePill · final region]
│   │   ├── AgentTrace.kt        # ThoughtGroup hierarchy (uxfb-4): one Thought + N Actions per group, drawBehind left rule
│   │   ├── AgentSummary.kt      # outcomeFooter + collapsedSummary helpers
│   │   ├── CollapsePill.kt      # Pill chip: [▸ ✓ N actions · 12s], Lucide icons, tween animation
│   │   ├── StreamingText.kt     # Final-block Text with inlineContent serif `|` cursor
│   │   ├── ThinkingIndicator.kt # 3 animated dots (ClosePawMotion.ThinkingPulse)
│   │   └── EmptyState.kt        # First launch with suggestion chips + serif italic question
│   └── model/
│       ├── ChatMessage.kt       # User + Agent message wrappers
│       └── ContentBlock.kt      # Text / FinalText / Thought / Action variants
│
├── capsule/
│   ├── NavAction.kt             # Capsule nav-cluster action enum
│   ├── CapsuleBinding.kt        # Runtime-bridge value type for chat hosts
│   └── surface/
│       ├── SmartCapsuleSurface.kt   # Orchestrator: status/detail/control/input
│       ├── SmartCapsuleSurfaceParts.kt # Status / Detail composable parts (semantic names)
│       ├── CapsuleControlBar.kt     # Action + nav clusters
│       ├── CapsuleInputBar.kt       # Text field + send (owns draft state)
│       ├── SmartCapsuleHostLayout.kt
│       ├── StatusColors.kt          # Mode → status dot/glow color derivation
│       └── StatusPawGlyph.kt        # Paw glyph (identity surface)
│
├── overlay/model/
│   ├── CapsuleRenderSpec.kt     # Stateless: CapsuleMode → visual properties
│   └── (NavSpec lives here too)
│
├── navigation/
│   └── NavigationDrawer.kt      # Side drawer (sessions + settings entry)
│
├── overlay/
│   ├── CapsuleStateHolder.kt    # Single source of truth (StateFlows)
│   ├── compose/                 # WindowManager-hosted Compose overlays
│   │   ├── CapsuleOverlayHost.kt    # System overlay: capsule
│   │   ├── IslandOverlayHost.kt     # System overlay: compact island
│   │   ├── GlowOverlayHost.kt       # System overlay: edge glow (Canvas)
│   │   ├── VisualizerOverlayHost.kt # System overlay: click/swipe feedback
│   │   ├── ActionVisualizerCompose.kt
│   │   ├── EdgeGlowCompose.kt
│   │   ├── StatusIslandCompose.kt
│   │   ├── OverlayComposeHost.kt    # Generic ComposeView → WindowManager wrapper
│   │   └── ServiceLifecycleOwner.kt # LifecycleOwner for AccessibilityService
│   ├── visualizer/
│   │   └── ActionVisualizerManager.kt
│   └── model/
│       ├── CapsuleMode.kt       # Sealed interface (Running, Takeover, etc.)
│       ├── CapsuleContext.kt    # MAIN_APP / SCREEN_VIEWING / BACKGROUND
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
>
> -> See: [`doc/main/state_machines/ui_chat.md`](../state_machines/ui_chat.md) for the test-locked event → timeline reducer reference (per-message state, action lifecycle, conversation timeline invariants).

Maps protocol events to UI mutations:

| Event | UI Mutation |
|-------|------------|
| `TaskStarted` | Add User message + Agent message (Thinking state) |
| `TurnStarted` | Clear streaming buffer |
| `MessageDelta` | Append to buffer, update Agent message to Streaming |
| `ActionProposed` | Add Action content block |
| `ActionExecuted` | Update action state + result summary |
| `TaskCompleted` | Apply Turn.kt:205-209 rule: complete_task path → append `FinalText(answer)`; tool-less path → promote trailing non-blank `Text` in place to `FinalText`; missing answer → no final block. Mark Complete. |
| `SessionError` | Append inline `⚠ ...` Text block; mark agent state Complete |
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
    data class Text(text: String)        // Mid-stream prose (rendered inside trace)
    data class FinalText(text: String)   // Closing answer — uxfb-3, Turn.kt:205-209 stop criteria.
                                          // Set by reducer when complete_task.answer arrives OR
                                          // when row seals with no tools and a non-blank text.
                                          // AgentRow renders this in the always-visible final
                                          // region below the CollapsePill.
    data class Thought(text: String)     // Opens a ThoughtGroup; bodyLarge header (no italic, no ✱)
    data class Action(data: ActionCardData)  // Indented monoSmall row inside the group, Lucide status icon right
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
            bottomBar = { SmartCapsuleSurface(...) }
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

> See: `ui/overlay/compose/OverlayComposeHost.kt`

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

> See: `ui/overlay/compose/ServiceLifecycleOwner.kt`

Implements `LifecycleOwner` + `SavedStateRegistryOwner` for services. Manual lifecycle events: `onCreate()` → Resume, `onDestroy()` → Destroy. Enables Compose in non-Activity contexts.

---

## Related Docs

- [User Interaction](user_interaction.md) - Pages, user behaviors
- [Style Guide](style.md) - Design system
- [Overlay](overlay.md) - Overlay implementation
- [History](../app/history/overview.md) - Session persistence

# UI Tech Design

> Technical implementation: tech stack, code structure, state management.
> Last updated: 2026-02-04 (commit: da83b53ba4e849e52b45158a3485261d7399facb)

## Tech Stack

### Dependencies

```kotlin
// Compose BOM (Bill of Materials)
val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
implementation(composeBom)

// Compose UI
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.ui:ui-graphics")
implementation("androidx.compose.foundation:foundation")

// Material 3
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.material:material-icons-extended")

// Activity Compose
implementation("androidx.activity:activity-compose:1.9.3")
```

| Library | Purpose |
|---------|---------|
| **Compose BOM** | Version management |
| **Material 3** | Modern design system |
| **Activity Compose** | `setContent {}` entry point |
| **Material Icons** | Comprehensive icon set |

---

## File Structure

```
ui/
├── theme/
│   ├── Color.kt                 # Light/Dark color schemes
│   ├── Shape.kt                 # Bubble shapes, card shapes
│   ├── Theme.kt                 # ChatTheme composable
│   ├── Type.kt                  # Typography scale
│   └── WindowInsets.kt          # AppWindowInsets
│
├── chat/
│   ├── ChatScreen.kt            # Main screen with drawer
│   ├── ChatViewModel.kt         # State management
│   ├── ChatSessionHistoryController.kt # Session orchestration
│   ├── components/
│   │   ├── ChatHeader.kt        # Header: [≡] Title [+]
│   │   ├── TaskBanner.kt        # Task context strip
│   │   ├── MessageBubble.kt     # User/Agent bubbles
│   │   ├── StreamingText.kt     # Text with cursor
│   │   ├── ThinkingIndicator.kt # Animated dots
│   │   ├── ActionCard.kt        # Tool execution card
│   │   ├── InputDock.kt         # Input area
│   │   └── EmptyState.kt        # First launch
│   └── model/
│       └── ChatMessage.kt       # UI data classes
│
├── navigation/
│   └── NavigationDrawer.kt      # Side drawer
│
├── overlay/
│   └── (see overlay.md)
│
├── session/
│   └── TimeUtils.kt             # Relative time formatting
│
└── settings/
    ├── SettingsSheet.kt         # Configuration sheet
    ├── SettingsModels.kt        # Settings data
    ├── SettingsDropdowns.kt     # Backend/model/execution-mode dropdowns
    └── SettingsWidgets.kt       # Shared widgets
```

---

## Component Architecture

### ChatScreen

Main screen composable integrating all chat components.

```kotlin
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
)
```

### ChatViewModel

State management for chat UI:

```kotlin
class ChatViewModel(private val session: AgentSession) : ViewModel() {
    val uiState: StateFlow<ChatUiState>
    val messages: List<ChatMessage>
    val taskBannerState: StateFlow<TaskBannerState>
    
    fun sendMessage(text: String)
    fun stopTask()
    fun clearConversation()
}
```

---

## State Management

### Message Types

```kotlin
sealed interface ChatMessage {
    val id: String
    val timestamp: Long
    
    data class User(id, timestamp, text: String) : ChatMessage
    data class Agent(id, timestamp, content: String, state: AgentMessageState, actions: List<ActionCardData>) : ChatMessage
}

enum class AgentMessageState { Thinking, Streaming, Complete }
```

### TaskBanner States

```kotlin
sealed interface TaskBannerState {
    data object Idle : TaskBannerState
    data class Working(val taskTitle: String, val phase: String?) : TaskBannerState
    data class Completed(val summary: String) : TaskBannerState
    data class Error(val message: String) : TaskBannerState
}
```

### ActionCard States

```kotlin
enum class ActionState { Proposed, Executing, Success, Failed, Skipped }
```

---

## Session History Integration

### ChatViewModel Session Methods

```kotlin
class ChatViewModel(
    private val session: AgentSession,
    private val sessionHistoryManager: SessionHistoryManager?
) : ViewModel() {
    val sessions: StateFlow<List<SessionInfo>>
    
    fun loadSessions()
    fun resumeSession(sessionInfo: SessionInfo)
    fun deleteSession(sessionInfo: SessionInfo)
    fun startNewSession()
}
```

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
    onClose: () -> Unit
)
```

---

## Quick Reference

### MainActivity Setup

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            ChatTheme {
                ChatScreen(
                    viewModel = viewModel,
                    sessions = sessions,
                    onOpenSettings = { showSettings = true },
                    // ...
                )
            }
        }
    }
}
```

### Event → UI Flow

```
AgentService.session.events
    ├──► EdgeGlowManager (ambient glow)
    ├──► SmartCapsuleManager (overlay)
    └──► ChatViewModel (message list)
            └──► ChatScreen recomposition
```

### Material 3 Components

| Component | Usage |
|-----------|-------|
| `OutlinedTextField` | Chat input |
| `FilledIconButton` | Send/Stop button |
| `Surface` | Bubbles, cards, banner |
| `LazyColumn` | Message list |
| `ModalNavigationDrawer` | Session history |
| `ModalBottomSheet` | Settings |

---

## Related Docs

- [User Interaction](user_interaction.md) - Pages, user behaviors
- [Style Guide](style.md) - Design system
- [Overlay](overlay.md) - Overlay implementation
- [History](../app/history.md) - Session persistence

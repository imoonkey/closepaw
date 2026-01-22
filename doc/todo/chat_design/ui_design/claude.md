# Android Agent - Chat UI Design

> **Version**: 1.1  
> **Status**: Design Proposal  
> **Author**: Claude  
> **Target**: Phase 5 - UI Integration

---

## 0. Current Architecture Context

Before diving into the new design, we must understand the existing UI components:

### Existing Components

```
┌─────────────────────────────────────────────────────────────────┐
│                     CURRENT ARCHITECTURE                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                     MainActivity                          │   │
│  │                  (Compose Entry Point)                    │   │
│  │                                                           │   │
│  │   ┌──────────────────────────────────────────────────┐   │   │
│  │   │              AgentScreen.kt                       │   │   │
│  │   │  ┌─────────────────────────────────────────────┐ │   │   │
│  │   │  │ Header: "Android Agent"                     │ │   │   │
│  │   │  ├─────────────────────────────────────────────┤ │   │   │
│  │   │  │ ConfigSection: API Key, Goal input          │ │   │   │
│  │   │  ├─────────────────────────────────────────────┤ │   │   │
│  │   │  │ ActionButtons: Start, Accessibility         │ │   │   │
│  │   │  ├─────────────────────────────────────────────┤ │   │   │
│  │   │  │ StatusLog: Activity feed                    │ │   │   │
│  │   │  └─────────────────────────────────────────────┘ │   │   │
│  │   └──────────────────────────────────────────────────┘   │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                  OverlayManager.kt                        │   │
│  │            (View-based Floating Control Bar)              │   │
│  │                                                           │   │
│  │   ┌──────────────────────────────────────────────────┐   │   │
│  │   │  ● Ready          │  ⏸  │  ⏹  │                  │   │   │
│  │   │  (status dot)     (pause) (stop)                 │   │   │
│  │   └──────────────────────────────────────────────────┘   │   │
│  │                                                           │   │
│  │   • Uses Views (not Compose) - required for WindowManager │   │
│  │   • Visible when agent runs in OTHER apps                 │   │
│  │   • Shows status, pause/resume, stop controls             │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Why the Overlay Matters

The **overlay (悬浮窗)** is essential for this agent because:

1. **Agent Controls Other Apps**: When executing tasks, the agent navigates Gmail, Settings, Chrome, etc.
2. **User Needs Control**: Without the overlay, users would have no way to pause/stop without switching apps
3. **Trust & Transparency**: Users can see what the agent is doing in real-time
4. **Safety**: The stop button is always accessible if something goes wrong

### Design Challenge

The new chat-centric UI must work in **two contexts**:

| Context | What User Sees | Primary Interaction |
|---------|---------------|---------------------|
| **In-App** | Full chat interface | Send messages, view full history |
| **Overlay** | Compact floating bar | Monitor progress, pause/stop |

---

## 1. Design Philosophy

### Vision
Transform the Android Agent into a **premium conversational experience** that feels as natural as talking to a highly capable assistant. The UI should be:

- **Invisible**: The interface disappears, leaving only the conversation
- **Alive**: Every interaction feels responsive and fluid
- **Trustworthy**: Clear feedback builds confidence in the agent's capabilities

### Design Principles

| Principle | Implementation |
|-----------|----------------|
| **Content First** | Chat takes 100% focus. No distracting chrome. |
| **Progressive Disclosure** | Complexity reveals itself only when needed |
| **Responsive Feedback** | Every action has immediate visual response |
| **Calm Technology** | Agent works; UI stays out of the way |

---

## 2. Information Architecture

### Screen Hierarchy

```
┌─────────────────────────────────────────┐
│                                         │
│  ┌─────────────────────────────────┐   │
│  │         CHAT SCREEN             │   │  ← Primary (100% of interaction)
│  │         (Full Screen)           │   │
│  └─────────────────────────────────┘   │
│                                         │
│  ┌─────────────────────────────────┐   │
│  │       SETTINGS SHEET            │   │  ← Secondary (On-demand)
│  │       (Bottom Sheet)            │   │
│  └─────────────────────────────────┘   │
│                                         │
│  ┌─────────────────────────────────┐   │
│  │       HISTORY DRAWER            │   │  ← Tertiary (Power users)
│  │       (Side Drawer)             │   │
│  └─────────────────────────────────┘   │
│                                         │
└─────────────────────────────────────────┘
```

### Navigation Model

- **No navigation bar** - Single screen app
- **Gestures for secondary screens**:
  - Swipe up from bottom → Settings sheet
  - Swipe from left edge → History drawer (optional, Phase 2)
- **Minimal header** - Only essential controls

---

## 3. Main Chat Screen

### 3.1 Layout Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ ░░░░░░░░░░░░░░░░░░░ STATUS BAR ░░░░░░░░░░░░░░░░░░░░░░░░░░░ │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                    HEADER                            │   │
│  │   [≡]              Agent Name              [⚙️]      │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                                                     │   │
│  │                                                     │   │
│  │                                                     │   │
│  │              CONVERSATION AREA                      │   │
│  │              (Scrollable)                           │   │
│  │                                                     │   │
│  │                                                     │   │
│  │                                                     │   │
│  │                                                     │   │
│  │                                                     │   │
│  │                                                     │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                  INPUT AREA                          │   │
│  │   ┌─────────────────────────────────┐  ┌─────┐      │   │
│  │   │ Message...                      │  │  ➤  │      │   │
│  │   └─────────────────────────────────┘  └─────┘      │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│ ░░░░░░░░░░░░░░░░░░ NAVIGATION BAR ░░░░░░░░░░░░░░░░░░░░░░░░ │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Header Bar

**Minimal. Functional. Elegant.**

```kotlin
// Header State
data class HeaderState(
    val title: String = "Agent",
    val showHistoryButton: Boolean = true,
    val showSettingsButton: Boolean = true,
    val connectionStatus: ConnectionStatus = Connected
)

enum class ConnectionStatus {
    Connected,    // Silent - no indicator
    Connecting,   // Subtle pulsing dot
    Disconnected  // Red dot with tooltip
}
```

**Visual Specifications:**

| Element | Specification |
|---------|--------------|
| Height | 56dp |
| Background | Surface color (transparent feel) |
| Title | 18sp, Medium weight, center aligned |
| Icons | 24dp, Material Symbols Rounded |
| Elevation | 0dp (flat, modern) |

**Interactions:**
- History button (`≡`) → Opens left drawer (future)
- Settings button (`⚙️`) → Opens bottom sheet
- Title → Non-interactive (no session selection in MVP)

### 3.3 Conversation Area

The heart of the experience. Where magic happens.

#### Message Types

```kotlin
sealed interface MessageUI {
    val id: String
    val timestamp: Long
    
    // User's input message
    data class UserMessage(
        override val id: String,
        override val timestamp: Long,
        val text: String
    ) : MessageUI
    
    // Agent's streaming response
    data class AgentMessage(
        override val id: String,
        override val timestamp: Long,
        val turnId: String,
        val text: StringBuilder,        // Mutable for streaming
        val state: AgentMessageState,
        val actions: List<ActionCard>   // Inline tool executions
    ) : MessageUI
    
    // Task boundary marker (subtle divider)
    data class TaskMarker(
        override val id: String,
        override val timestamp: Long,
        val taskId: String,
        val type: TaskMarkerType        // Started | Completed
    ) : MessageUI
}

enum class AgentMessageState {
    Streaming,    // Currently receiving deltas
    Thinking,     // After tool call, before next response
    Complete      // Turn finished
}
```

#### Message Bubble Design

**User Message Bubble:**

```
                              ┌────────────────────────────┐
                              │                            │
                              │  Check my email and let    │
                              │  me know if there's        │
                              │  anything urgent           │
                              │                            │
                              └────────────────────────────┘
                                                    12:34 PM
```

| Property | Value |
|----------|-------|
| Background | Primary color (brand accent) |
| Text Color | On-Primary (high contrast) |
| Corner Radius | 20dp (top), 20dp (left), 4dp (bottom-right) |
| Max Width | 85% of screen |
| Padding | 16dp horizontal, 12dp vertical |
| Typography | 16sp, Regular |
| Alignment | End (right) |
| Timestamp | 12sp, Below bubble, muted color |

**Agent Message Bubble:**

```
┌────────────────────────────────────────────────────────┐
│                                                        │
│  I'll check your email now.                            │
│                                                        │
│  ┌──────────────────────────────────────────────────┐ │
│  │  📧  Opening Gmail                         ✓     │ │
│  └──────────────────────────────────────────────────┘ │
│                                                        │
│  You have 3 unread emails. One from your manager      │
│  about the Q4 report deadline - looks urgent.█        │
│                                                        │
└────────────────────────────────────────────────────────┘
12:34 PM
```

| Property | Value |
|----------|-------|
| Background | Surface Variant (subtle contrast) |
| Text Color | On-Surface |
| Corner Radius | 20dp (top), 4dp (top-left), 20dp (right) |
| Max Width | 90% of screen |
| Padding | 16dp horizontal, 12dp vertical |
| Typography | 16sp, Regular |
| Alignment | Start (left) |
| Streaming Cursor | Blinking block (`█`) at end of text |

#### Streaming Animation

**The "Alive" Factor** - Most critical UX element.

```kotlin
@Composable
fun StreamingText(
    text: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge
        )
        
        if (isStreaming) {
            // Blinking cursor
            val alpha by rememberInfiniteTransition().animateFloat(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(530, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
            
            Text(
                text = "█",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.alpha(alpha)
            )
        }
    }
}
```

**Performance Considerations:**
- Use `StringBuilder` for accumulating deltas (avoid string concatenation)
- Update UI at max 60fps; batch rapid deltas
- Auto-scroll to bottom during streaming (with smart interruption if user scrolls up)

#### Action Cards (Inline Tool Execution)

When the agent executes tools, show inline within the message:

```
┌──────────────────────────────────────────────────────────┐
│                                                          │
│  │ 📧  Opening Gmail                                     │
│  │                                                       │
│  │  ┌────────────────────────────────────────────────┐  │
│  │  │  ⚡ tap(Gmail)                          ✓ Done │  │
│  │  └────────────────────────────────────────────────┘  │
│  │                                                       │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

**Action Card States:**

| State | Visual |
|-------|--------|
| Pending | Pulsing border, spinner icon |
| Executing | Solid border, animated icon |
| Success | Green checkmark, subtle success color |
| Failed | Red X, error message expandable |

```kotlin
@Composable
fun ActionCard(
    action: ActionCardData,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when (action.state) {
        ActionState.Pending -> MaterialTheme.colorScheme.surfaceVariant
        ActionState.Executing -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ActionState.Success -> MaterialTheme.colorScheme.secondaryContainer
        ActionState.Failed -> MaterialTheme.colorScheme.errorContainer
    }
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tool icon
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            
            Spacer(Modifier.width(12.dp))
            
            // Tool name and description
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = action.toolName,
                    style = MaterialTheme.typography.labelMedium
                )
                if (action.description.isNotEmpty()) {
                    Text(
                        text = action.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Status indicator
            StatusIcon(state = action.state)
        }
    }
}
```

#### Thinking Indicator

When agent is processing between turns (after tool execution):

```
┌────────────────────────────────────────┐
│                                        │
│  ●  ●  ●                               │
│                                        │
└────────────────────────────────────────┘
```

```kotlin
@Composable
fun ThinkingIndicator() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        repeat(3) { index ->
            val delay = index * 150
            val alpha by rememberInfiniteTransition().animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = delay),
                    repeatMode = RepeatMode.Reverse
                )
            )
            
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                        CircleShape
                    )
            )
        }
    }
}
```

### 3.4 Input Area

**The Command Center** - Always accessible, always ready.

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  ┌───────────────────────────────────────────────┐ ┌─────┐ │
│  │                                               │ │     │ │
│  │  Ask me anything...                           │ │  ➤  │ │
│  │                                               │ │     │ │
│  └───────────────────────────────────────────────┘ └─────┘ │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**When Task is Running:**

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  ┌───────────────────────────────────────────────┐ ┌─────┐ │
│  │                                               │ │     │ │
│  │  Agent is working...                          │ │  ■  │ │
│  │                                               │ │     │ │
│  └───────────────────────────────────────────────┘ └─────┘ │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

```kotlin
@Composable
fun InputArea(
    state: InputState,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.Bottom
        ) {
            // Text Field
            TextField(
                value = if (state == InputState.Busy) "" else text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                enabled = state == InputState.Idle,
                placeholder = {
                    Text(
                        text = if (state == InputState.Busy) 
                            "Agent is working..." 
                        else 
                            "Ask me anything...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(24.dp),
                maxLines = 4
            )
            
            Spacer(Modifier.width(12.dp))
            
            // Send / Stop Button
            FloatingActionButton(
                onClick = {
                    if (state == InputState.Busy) {
                        onInterrupt()
                    } else if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                },
                modifier = Modifier.size(48.dp),
                containerColor = if (state == InputState.Busy)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = if (state == InputState.Busy)
                        Icons.Rounded.Stop
                    else
                        Icons.Rounded.Send,
                    contentDescription = if (state == InputState.Busy) "Stop" else "Send"
                )
            }
        }
    }
}

enum class InputState {
    Idle,   // Ready for input
    Busy    // Task running, show interrupt button
}
```

**Key Behaviors:**
- Multi-line expansion (up to 4 lines, then scroll)
- Keyboard aware (moves up with IME)
- Haptic feedback on send
- Button morphs from Send → Stop when busy
- Long press on Stop shows "Force Stop" option (kills task immediately)

---

## 4. Enhanced Overlay (悬浮窗)

The overlay transforms from a simple control bar into a **mini chat companion** that keeps users connected to the conversation even while the agent controls other apps.

### 4.1 Design Modes

The new overlay supports **two display modes**:

```
┌─────────────────────────────────────────────────────────────────┐
│                      OVERLAY MODES                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  MODE 1: COMPACT (Default)                                       │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  ● Checking your email...         │  ⏸  │  ⏹  │  ↗  │   │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  MODE 2: EXPANDED (Tap to expand)                                │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  ╭────────────────────────────────────────────────────╮  │   │
│  │  │  I'll check your email now. Opening Gmail...       │  │   │
│  │  │                                                     │  │   │
│  │  │  ┌───────────────────────────────────────────────┐ │  │   │
│  │  │  │  📧  Opening Gmail                       ✓    │ │  │   │
│  │  │  └───────────────────────────────────────────────┘ │  │   │
│  │  │                                                     │  │   │
│  │  │  Found 3 unread emails. One from your manager█     │  │   │
│  │  ╰────────────────────────────────────────────────────╯  │   │
│  │                                                           │   │
│  │  ┌────────────────────────────────────────────────────┐  │   │
│  │  │  ⏸ Pause    │    ⏹ Stop    │    ↗ Open App       │  │   │
│  │  └────────────────────────────────────────────────────┘  │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 4.2 Compact Mode (Default)

The minimal floating bar - similar to current implementation but enhanced:

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  ●  Opening Gmail...              │  ⏸  │  ⏹  │  ↗  │   │   │
│  │  ↑   ↑                               ↑      ↑      ↑    │   │
│  │  │   └─ Truncated streaming text     │      │      │    │   │
│  │  │      (tappable to expand)         │      │      │    │   │
│  │  └─ Status dot (animated)            │      │      │    │   │
│  │                                      │      │      │    │   │
│  │                           Pause/Resume  Stop  Open App  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Visual Specifications:**

| Element | Specification |
|---------|---------------|
| Position | Bottom center, 24dp margin from edges |
| Height | 48dp |
| Width | Match parent minus 32dp margins |
| Corner Radius | 14dp |
| Background | White with subtle shadow |
| Status Dot | 8dp, color-coded, pulsing when streaming |
| Text | 14sp, single line, ellipsis |
| Buttons | 40dp circular, icon only |

**New Button: Open App (↗)**

Opens the main app to show full chat history. Critical for:
- Viewing complete conversation
- Sending follow-up messages
- Accessing settings

### 4.3 Expanded Mode

Tap the compact bar to expand into a mini chat view:

```kotlin
// Expanded overlay dimensions
val EXPANDED_HEIGHT = 280.dp   // ~40% of screen
val EXPANDED_WIDTH = MATCH_PARENT - 32.dp

// Layout structure
┌─────────────────────────────────────────────────────────┐
│  ╭─────────────────────────────────────────────────╮   │  ← Drag handle
│  │                                                  │   │
│  │  [Scrollable message content - last ~3 messages] │   │  ← Message area
│  │                                                  │   │
│  │  ┌────────────────────────────────────────────┐ │   │
│  │  │  📧  Opening Gmail               ✓ Done    │ │   │  ← Current action
│  │  └────────────────────────────────────────────┘ │   │
│  │                                                  │   │
│  │  You have 3 unread emails...█                   │   │  ← Streaming text
│  │                                                  │   │
│  ╰─────────────────────────────────────────────────╯   │
│                                                         │
│  ┌─────────┐  ┌─────────┐  ┌─────────────────────┐    │
│  │ ⏸ Pause │  │ ⏹ Stop  │  │    ↗ Open App      │    │  ← Action buttons
│  └─────────┘  └─────────┘  └─────────────────────┘    │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**Features:**
- Shows last ~3 messages (truncated for space)
- Full streaming text with cursor
- Inline action cards
- Larger, labeled buttons
- Drag handle to collapse

### 4.4 Overlay State Machine

```
                    ┌──────────────┐
                    │    Hidden    │
                    └──────┬───────┘
                           │ Task Started
                           ▼
                    ┌──────────────┐
          ┌────────│   Compact    │←──────────┐
          │        └──────┬───────┘           │
          │               │ Tap               │ Tap drag handle
          │               ▼                   │ or outside
          │        ┌──────────────┐           │
          │        │   Expanded   │───────────┘
          │        └──────┬───────┘
          │               │
          └───────────────┴──────────────────────┐
                          │ Task Completed       │
                          │ OR Stop pressed      │
                          ▼                      │
                    ┌──────────────┐             │
                    │   Completed  │─────────────┘
                    │  (auto-hide) │  after 3s
                    └──────────────┘
```

### 4.5 Implementation Notes

**Why Views Instead of Compose:**

The overlay **must** use Views because:
1. `WindowManager.addView()` requires a `View`
2. Compose would require `ComposeView` wrapper with additional complexity
3. Current implementation works well, just needs enhancement

**Streaming Text in Overlay:**

```kotlin
class OverlayManager(...) {
    // Add streaming text accumulator
    private val streamingText = StringBuilder()
    private var currentTurnId: String? = null
    
    fun onMessageDelta(turnId: String, delta: String) {
        if (turnId != currentTurnId) {
            // New turn - reset accumulator
            streamingText.clear()
            currentTurnId = turnId
        }
        streamingText.append(delta)
        
        // Update UI on main thread
        statusText?.post {
            val displayText = streamingText.toString()
                .take(100)  // Truncate for compact mode
                .replace("\n", " ")  // Single line
            statusText?.text = displayText.ifEmpty { "Thinking..." }
        }
    }
}
```

**Open App Intent:**

```kotlin
private fun createOpenAppButton(): View {
    return createIconButton(
        iconResName = "open",
        contentDescription = "Open App"
    ) {
        // Launch MainActivity with FLAG_ACTIVITY_REORDER_TO_FRONT
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
```

### 4.6 Overlay Visual Design

**Color Scheme** (matching app theme):

```kotlin
// Overlay colors (same as app)
private val colorBackground = 0xFFFFFFFF.toInt()
private val colorBorder = 0xFFE9E9E7.toInt()
private val colorPrimary = 0xFF2563EB.toInt()    // Blue for streaming
private val colorSuccess = 0xFF0D9488.toInt()    // Teal for success
private val colorError = 0xFFDC2626.toInt()      // Red for error/stop
private val colorText = 0xFF37352F.toInt()
private val colorTextMuted = 0xFF6B6B6B.toInt()
```

**Status Dot Animation:**

```kotlin
// Pulsing animation while streaming
private fun startPulsingAnimation() {
    val animator = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.4f, 1f)
    animator.duration = 1000
    animator.repeatCount = ObjectAnimator.INFINITE
    animator.interpolator = AccelerateDecelerateInterpolator()
    animator.start()
}
```

---

## 5. Settings Bottom Sheet

**Access**: Swipe up from bottom edge, or tap settings icon in header.

### Sheet Design

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│                         ─────                               │  ← Drag handle
│                                                             │
│   Settings                                                  │
│   ──────────────────────────────────────────────────────   │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐  │
│   │  🤖  Model                                          │  │
│   │      GPT-4o                                    ▼    │  │
│   └─────────────────────────────────────────────────────┘  │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐  │
│   │  📋  Max Turns                                      │  │
│   │      10                                        ▼    │  │
│   └─────────────────────────────────────────────────────┘  │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐  │
│   │  🎯  Auto-Confirm Actions                           │  │
│   │      Enabled                                   🔘   │  │
│   └─────────────────────────────────────────────────────┘  │
│                                                             │
│   ──────────────────────────────────────────────────────   │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐  │
│   │  🗑️  Clear Conversation                             │  │
│   └─────────────────────────────────────────────────────┘  │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐  │
│   │  ℹ️  About                                          │  │
│   └─────────────────────────────────────────────────────┘  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Settings Items

```kotlin
sealed interface SettingItem {
    data class Dropdown(
        val icon: ImageVector,
        val title: String,
        val value: String,
        val options: List<String>,
        val onSelect: (String) -> Unit
    ) : SettingItem
    
    data class Toggle(
        val icon: ImageVector,
        val title: String,
        val subtitle: String?,
        val checked: Boolean,
        val onToggle: (Boolean) -> Unit
    ) : SettingItem
    
    data class Action(
        val icon: ImageVector,
        val title: String,
        val destructive: Boolean = false,
        val onClick: () -> Unit
    ) : SettingItem
}

// Settings for MVP
val mvpSettings = listOf(
    SettingItem.Dropdown(
        icon = Icons.Rounded.SmartToy,
        title = "Model",
        value = "GPT-4o",
        options = listOf("GPT-4o", "GPT-4o-mini", "Claude 3.5 Sonnet"),
        onSelect = { /* Update config */ }
    ),
    SettingItem.Dropdown(
        icon = Icons.Rounded.Repeat,
        title = "Max Turns",
        value = "10",
        options = listOf("5", "10", "15", "20", "Unlimited"),
        onSelect = { /* Update config */ }
    ),
    SettingItem.Toggle(
        icon = Icons.Rounded.TouchApp,
        title = "Auto-Confirm Actions",
        subtitle = "Execute tool calls without asking",
        checked = true,
        onToggle = { /* Update config */ }
    ),
    SettingItem.Divider,
    SettingItem.Action(
        icon = Icons.Rounded.DeleteOutline,
        title = "Clear Conversation",
        destructive = true,
        onClick = { /* Confirm and clear */ }
    ),
    SettingItem.Action(
        icon = Icons.Rounded.Info,
        title = "About",
        onClick = { /* Show about dialog */ }
    )
)
```

---

## 6. Empty State

When app first opens or conversation is cleared:

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│                                                             │
│                                                             │
│                                                             │
│                          🤖                                 │
│                                                             │
│                    Android Agent                            │
│                                                             │
│          Your AI assistant for everything                   │
│                  on your phone                              │
│                                                             │
│                                                             │
│     ┌─────────────────────────────────────────────┐        │
│     │  💡  "Check my unread emails"               │        │
│     └─────────────────────────────────────────────┘        │
│                                                             │
│     ┌─────────────────────────────────────────────┐        │
│     │  📱  "Open Settings and turn on WiFi"       │        │
│     └─────────────────────────────────────────────┘        │
│                                                             │
│     ┌─────────────────────────────────────────────┐        │
│     │  📸  "Take a screenshot of this page"       │        │
│     └─────────────────────────────────────────────┘        │
│                                                             │
│                                                             │
│                                                             │
│  ┌───────────────────────────────────────────────┐ ┌─────┐ │
│  │  Ask me anything...                           │ │  ➤  │ │
│  └───────────────────────────────────────────────┘ └─────┘ │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Suggestion chips are tappable** - fills input box with that text.

```kotlin
@Composable
fun EmptyState(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Animated logo
        AnimatedLogo(modifier = Modifier.size(80.dp))
        
        Spacer(Modifier.height(24.dp))
        
        Text(
            text = "Android Agent",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            text = "Your AI assistant for everything on your phone",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        Spacer(Modifier.height(48.dp))
        
        // Suggestion chips
        suggestions.forEach { suggestion ->
            SuggestionChip(
                text = suggestion,
                onClick = { onSuggestionClick(suggestion) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            )
        }
    }
}
```

---

## 7. Color System

### Light Theme

```kotlin
val LightColorScheme = lightColorScheme(
    // Primary - Vibrant blue, professional yet approachable
    primary = Color(0xFF2563EB),           // Blue 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),  // Blue 100
    onPrimaryContainer = Color(0xFF1E40AF), // Blue 800
    
    // Secondary - Teal accent for success states
    secondary = Color(0xFF0D9488),          // Teal 600
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1), // Teal 100
    onSecondaryContainer = Color(0xFF115E59), // Teal 800
    
    // Surface colors - Clean, minimal
    surface = Color(0xFFFAFAFA),            // Near white
    onSurface = Color(0xFF171717),          // Near black
    surfaceVariant = Color(0xFFF5F5F5),     // Subtle gray
    onSurfaceVariant = Color(0xFF525252),   // Medium gray
    
    // Background
    background = Color.White,
    onBackground = Color(0xFF171717),
    
    // Error
    error = Color(0xFFDC2626),              // Red 600
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),     // Red 100
    
    // Outline
    outline = Color(0xFFD4D4D4),            // Gray 300
    outlineVariant = Color(0xFFE5E5E5)      // Gray 200
)
```

### Dark Theme

```kotlin
val DarkColorScheme = darkColorScheme(
    // Primary - Brighter blue for dark surfaces
    primary = Color(0xFF60A5FA),            // Blue 400
    onPrimary = Color(0xFF1E3A5F),
    primaryContainer = Color(0xFF1E40AF),   // Blue 800
    onPrimaryContainer = Color(0xFFDBEAFE),
    
    // Secondary
    secondary = Color(0xFF2DD4BF),          // Teal 400
    onSecondary = Color(0xFF0F3D38),
    secondaryContainer = Color(0xFF115E59),
    onSecondaryContainer = Color(0xFFCCFBF1),
    
    // Surface - Deep grays, not pure black
    surface = Color(0xFF171717),            // Gray 900
    onSurface = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFF262626),     // Gray 800
    onSurfaceVariant = Color(0xFFA3A3A3),   // Gray 400
    
    // Background
    background = Color(0xFF0A0A0A),         // Nearly black
    onBackground = Color(0xFFFAFAFA),
    
    // Error
    error = Color(0xFFF87171),              // Red 400
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    
    // Outline
    outline = Color(0xFF404040),            // Gray 700
    outlineVariant = Color(0xFF262626)
)
```

---

## 8. Typography

Using **Inter** font family (or system default if unavailable):

```kotlin
val Typography = Typography(
    // Headlines
    headlineLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.25).sp
    ),
    
    // Body - Chat messages
    bodyLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    
    // Labels - Action cards, timestamps
    labelLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
```

---

## 9. Motion & Animation

### Principles

1. **Quick but not jarring** - 200-300ms for most transitions
2. **Physics-based** - Spring animations feel natural
3. **Purposeful** - Animation communicates state, not decorates
4. **Consistent** - Same elements animate the same way everywhere

### Specifications

| Animation | Duration | Easing | Notes |
|-----------|----------|--------|-------|
| Message appear | 200ms | EaseOutCubic | Slide up + fade in |
| Streaming cursor | 530ms | Linear | Blink cycle |
| Thinking dots | 600ms/dot | EaseInOutSine | Staggered 150ms |
| Button morph | 250ms | EaseInOutQuart | Send ↔ Stop |
| Sheet expand | 350ms | Spring (stiffness=400) | Bottom sheet |
| Action card state | 150ms | EaseOut | Color/icon change |

### Message Entry Animation

```kotlin
@Composable
fun AnimatedMessage(
    message: MessageUI,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        visible = true
    }
    
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { it / 4 },
            animationSpec = tween(200, easing = EaseOutCubic)
        ) + fadeIn(
            animationSpec = tween(200)
        )
    ) {
        MessageBubble(message = message, modifier = modifier)
    }
}
```

---

## 10. State Management

### ViewModel Architecture

```kotlin
class ChatViewModel(
    private val session: AgentSession
) : ViewModel() {
    
    // UI State
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    // Messages with streaming support
    private val _messages = mutableStateListOf<MessageUI>()
    val messages: List<MessageUI> get() = _messages
    
    init {
        // Collect agent events
        viewModelScope.launch {
            session.events.collect { event ->
                handleEvent(event)
            }
        }
    }
    
    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TaskStarted -> {
                _uiState.update { it.copy(inputState = InputState.Busy) }
                // Add user message
                _messages.add(
                    MessageUI.UserMessage(
                        id = UUID.randomUUID().toString(),
                        timestamp = event.timestamp,
                        text = event.input
                    )
                )
                // Prepare agent message placeholder
                _messages.add(
                    MessageUI.AgentMessage(
                        id = event.taskId,
                        timestamp = event.timestamp,
                        turnId = "",
                        text = StringBuilder(),
                        state = AgentMessageState.Thinking,
                        actions = emptyList()
                    )
                )
            }
            
            is AgentEvent.MessageDelta -> {
                // Find current agent message and append delta
                val lastAgentMessage = _messages.filterIsInstance<MessageUI.AgentMessage>().lastOrNull()
                lastAgentMessage?.let { msg ->
                    msg.text.append(event.delta)
                    msg.state = AgentMessageState.Streaming
                    // Trigger recomposition
                    _messages[_messages.indexOf(msg)] = msg.copy()
                }
            }
            
            is AgentEvent.ActionProposed -> {
                // Add pending action card to current message
                updateLastAgentMessageWithAction(
                    ActionCard(
                        id = event.actionId,
                        toolName = event.action.toolName,
                        description = event.action.description,
                        state = ActionState.Pending
                    )
                )
            }
            
            is AgentEvent.ActionExecuted -> {
                // Update action card state
                updateActionState(
                    actionId = event.actionId,
                    state = if (event.success) ActionState.Success else ActionState.Failed
                )
            }
            
            is AgentEvent.TaskCompleted -> {
                _uiState.update { it.copy(inputState = InputState.Idle) }
                // Mark last agent message as complete
                val lastAgentMessage = _messages.filterIsInstance<MessageUI.AgentMessage>().lastOrNull()
                lastAgentMessage?.let { msg ->
                    msg.state = AgentMessageState.Complete
                    _messages[_messages.indexOf(msg)] = msg.copy()
                }
            }
            
            // ... handle other events
        }
    }
    
    fun sendMessage(text: String) {
        viewModelScope.launch {
            session.handleOperation(Op.UserInput(text))
        }
    }
    
    fun interrupt() {
        viewModelScope.launch {
            session.handleOperation(Op.Interrupt)
        }
    }
}

data class ChatUiState(
    val inputState: InputState = InputState.Idle,
    val showEmptyState: Boolean = true,
    val settings: SettingsState = SettingsState()
)
```

---

## 11. App ↔ Overlay Communication

A critical architectural consideration: how do the main app and overlay stay in sync?

### Current Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        AgentService                              │
│                  (AccessibilityService)                          │
│                                                                  │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                   OverlayManager                         │   │
│   │   • Receives status updates directly                     │   │
│   │   • Callbacks: onStop, onPause, onResume                 │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                  │
│   statusFlow ──────────────────────────────────────────────────►│
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
                              │
                              │ Flow<String>
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        MainActivity                              │
│                                                                  │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    AgentScreen                           │   │
│   │   • Collects statusFlow                                  │   │
│   │   • Displays status log                                  │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### New Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        AgentService                              │
│                  (AccessibilityService)                          │
│                                                                  │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                   OverlayManager                         │   │
│   │   • Receives AgentEvents directly                        │   │
│   │   • Handles MessageDelta for streaming preview           │   │
│   │   • Callbacks: onStop, onPause, onResume, onOpenApp      │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                  │
│   agentEventFlow ──────────────────────────────────────────────►│
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
                              │
                              │ Flow<AgentEvent>
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        MainActivity                              │
│                                                                  │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                   ChatViewModel                          │   │
│   │   • Collects agentEventFlow                              │   │
│   │   • Manages message list                                 │   │
│   │   • Handles streaming accumulation                       │   │
│   └─────────────────────────────────────────────────────────┘   │
│              │                                                   │
│              ▼                                                   │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │                    ChatScreen                            │   │
│   │   • Displays full conversation                           │   │
│   │   • Input for sending messages                           │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Key Changes

| Component | Current | New |
|-----------|---------|-----|
| **Event Type** | `Flow<String>` (status text) | `Flow<AgentEvent>` (rich events) |
| **Overlay Updates** | `updateStatus(status: String)` | `onEvent(event: AgentEvent)` |
| **Streaming** | Not supported | Forward `MessageDelta` to overlay |
| **Open App** | Not available | New button + Intent |

### Implementation in AgentService

```kotlin
// In AgentService.kt

private fun setupOverlayManager() {
    overlayManager = OverlayManager(
        context = this,
        onStop = { handleOperation(Op.Interrupt) },
        onPause = { handleOperation(Op.Pause) },
        onResume = { handleOperation(Op.Resume) },
        onOpenApp = { openMainActivity() }  // NEW
    )
}

// Forward events to overlay
private fun emitEvent(event: AgentEvent) {
    // Emit to flow (for MainActivity)
    _agentEventFlow.tryEmit(event)
    
    // Also forward to overlay for real-time updates
    when (event) {
        is AgentEvent.MessageDelta -> {
            overlayManager?.onMessageDelta(event.turnId, event.delta)
        }
        is AgentEvent.TaskStarted -> {
            overlayManager?.onTaskStarted(event.taskId)
        }
        is AgentEvent.TaskCompleted -> {
            overlayManager?.onTaskCompleted()
        }
        is AgentEvent.ActionExecuted -> {
            overlayManager?.onActionExecuted(event.toolName, event.success)
        }
        // ... handle other events
    }
}

private fun openMainActivity() {
    val intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_NEW_TASK
    }
    startActivity(intent)
}
```

### Overlay Enhanced API

```kotlin
// Enhanced OverlayManager API
class OverlayManager(
    private val context: AccessibilityService,
    private val onStop: () -> Unit,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onOpenApp: () -> Unit  // NEW
) {
    // NEW: Streaming text support
    private val streamingText = StringBuilder()
    private var currentTurnId: String? = null
    
    fun onTaskStarted(taskId: String) {
        streamingText.clear()
        currentTurnId = null
        updateStatusDot(color = colorPrimary, pulsing = true)
        show()
    }
    
    fun onMessageDelta(turnId: String, delta: String) {
        if (turnId != currentTurnId) {
            streamingText.clear()
            currentTurnId = turnId
        }
        streamingText.append(delta)
        updateStatusText(streamingText.toString())
    }
    
    fun onActionExecuted(toolName: String, success: Boolean) {
        // Brief flash of action name
        updateStatusText("$toolName ${if (success) "✓" else "✗"}")
        updateStatusDot(color = if (success) colorSuccess else colorError)
    }
    
    fun onTaskCompleted() {
        updateStatusDot(color = colorSuccess, pulsing = false)
        // Auto-hide after delay
        handler.postDelayed({ hide() }, 3000)
    }
}
```

---

## 12. Accessibility

### Requirements

| Feature | Implementation |
|---------|----------------|
| Screen reader | All interactive elements have `contentDescription` |
| Touch targets | Minimum 48dp x 48dp |
| Color contrast | 4.5:1 minimum for text (WCAG AA) |
| Motion | Respect `prefers-reduced-motion` system setting |
| Font scaling | Support up to 200% font scale |

### Implementation

```kotlin
@Composable
fun AccessibleActionCard(
    action: ActionCardData,
    modifier: Modifier = Modifier
) {
    val statusDescription = when (action.state) {
        ActionState.Pending -> "Pending"
        ActionState.Executing -> "In progress"
        ActionState.Success -> "Completed successfully"
        ActionState.Failed -> "Failed"
    }
    
    Surface(
        modifier = modifier
            .semantics {
                contentDescription = "${action.toolName}: $statusDescription"
                stateDescription = statusDescription
            }
            .clearAndSetSemantics {
                // Announce state changes
                liveRegion = LiveRegionMode.Polite
            }
    ) {
        // ... card content
    }
}
```

---

## 13. Implementation Roadmap

### Phase 5.1: Core Chat UI (MVP)

| Task | Priority | Effort |
|------|----------|--------|
| ChatScreen composable with message list | P0 | M |
| User message bubble | P0 | S |
| Agent message bubble with streaming | P0 | M |
| Input area with send/stop toggle | P0 | M |
| Event handling in ViewModel | P0 | M |
| Basic theming (light mode) | P0 | S |
| **Overlay: Add "Open App" button** | P0 | S |
| **Overlay: Forward MessageDelta to overlay** | P0 | M |

### Phase 5.2: Polish

| Task | Priority | Effort |
|------|----------|--------|
| Action cards inline | P1 | M |
| Thinking indicator | P1 | S |
| Empty state with suggestions | P1 | S |
| Settings bottom sheet | P1 | M |
| Dark theme | P1 | S |
| Message animations | P2 | M |
| **Overlay: Pulsing status dot animation** | P1 | S |
| **Overlay: Update visual design to match new theme** | P1 | S |

### Phase 5.3: Enhanced

| Task | Priority | Effort |
|------|----------|--------|
| **Overlay: Expanded mode with mini chat** | P2 | L |
| History drawer | P2 | L |
| Session management | P2 | L |
| Haptic feedback | P2 | S |
| Accessibility audit | P1 | M |

---

## 14. File Structure

```
app/src/main/kotlin/com/moonkey/androidagent/
└── ui/
    ├── theme/
    │   ├── Color.kt                # Color definitions (update for new palette)
    │   ├── Typography.kt           # (new) Inter font family
    │   ├── Theme.kt                # AgentTheme composable
    │   └── Shape.kt                # (new) Shared shape definitions
    │
    ├── chat/                       # NEW: Chat-centric UI
    │   ├── ChatScreen.kt           # Main screen composable
    │   ├── ChatViewModel.kt        # State management, event handling
    │   ├── components/
    │   │   ├── MessageBubble.kt    # User & Agent bubbles
    │   │   ├── StreamingText.kt    # Text with blinking cursor
    │   │   ├── ActionCard.kt       # Tool execution card
    │   │   ├── ThinkingIndicator.kt# Animated dots
    │   │   ├── InputArea.kt        # Text field + send/stop button
    │   │   └── EmptyState.kt       # Welcome screen with suggestions
    │   └── model/
    │       └── MessageUI.kt        # UI data classes
    │
    ├── overlay/                    # ENHANCED: Floating control
    │   └── OverlayManager.kt       # View-based (keep Views, enhance features)
    │                               # Changes:
    │                               #   - Add "Open App" button
    │                               #   - Add streaming text support
    │                               #   - Add pulsing animation
    │                               #   - (Phase 5.3) Add expanded mode
    │
    ├── settings/                   # NEW: Settings bottom sheet
    │   ├── SettingsSheet.kt        # Bottom sheet composable
    │   └── SettingsViewModel.kt    # Settings state
    │
    └── screen/
        └── AgentScreen.kt          # DEPRECATED: Keep for reference
                                    # Will be replaced by ChatScreen
```

### Migration Strategy

| Current | New | Action |
|---------|-----|--------|
| `AgentScreen.kt` | `ChatScreen.kt` | Replace (new file) |
| `OverlayManager.kt` | `OverlayManager.kt` | Enhance (modify existing) |
| `Color.kt` | `Color.kt` | Update (modify existing) |
| `Type.kt` | `Typography.kt` | Rename + enhance |
| - | `ChatViewModel.kt` | Create (new file) |
| - | `SettingsSheet.kt` | Create (new file) |

---

## 15. Design Assets

### Required Resources

| Asset | Format | Sizes |
|-------|--------|-------|
| App icon | Adaptive icon | 48, 72, 96, 144, 192dp |
| Logo (empty state) | Vector (SVG/XML) | Scalable |
| Tool icons | Material Symbols | 20dp, 24dp |

### Icon Mapping (Material Symbols)

| Tool | Icon |
|------|------|
| tap | `touch_app` |
| swipe | `swipe` |
| type_text | `keyboard` |
| open_app | `launch` |
| screenshot | `screenshot` |
| complete_task | `check_circle` |
| ask_user | `help` |

---

## Summary

This design creates a **chat-first experience** that works seamlessly across two contexts:

### In-App Experience
1. **Removes friction** - No setup screens, just conversation
2. **Shows progress** - Streaming text and action cards build trust
3. **Stays responsive** - Immediate feedback for all interactions
4. **Settings hidden** - Accessible via bottom sheet, not cluttering the main UI

### Overlay Experience (悬浮窗)
1. **Always accessible** - Control the agent while it works in other apps
2. **Streaming preview** - See what the agent is thinking in real-time
3. **Quick actions** - Pause, Stop, or Open App with single tap
4. **Expandable** - (Phase 5.3) Full mini-chat for power users

### Key Design Principles

| Principle | In-App | Overlay |
|-----------|--------|---------|
| **Focus** | Full conversation | Quick status |
| **Interaction** | Send messages | Monitor & control |
| **Depth** | Complete history | Last message preview |
| **Controls** | Settings, history | Pause, stop, open app |

### Architecture Summary

```
┌─────────────────────────────────────────────────────────────┐
│                       USER CONTEXTS                          │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│   ┌──────────────────────┐    ┌──────────────────────────┐  │
│   │      IN-APP          │    │       OVERLAY            │  │
│   │   (ChatScreen)       │    │   (OverlayManager)       │  │
│   │                      │    │                          │  │
│   │  • Full chat history │    │  • Compact status bar    │  │
│   │  • Input for new msg │    │  • Streaming preview     │  │
│   │  • Settings access   │    │  • Pause/Stop/Open       │  │
│   │  • Empty state       │    │  • (Expanded mode P2)    │  │
│   │                      │    │                          │  │
│   └──────────┬───────────┘    └────────────┬─────────────┘  │
│              │                              │                │
│              └──────────────┬───────────────┘                │
│                             │                                │
│                             ▼                                │
│              ┌──────────────────────────────┐                │
│              │       ChatViewModel          │                │
│              │   (Shared state & events)    │                │
│              │                              │                │
│              │  • Collect AgentEvents       │                │
│              │  • Manage message list       │                │
│              │  • Forward to overlay        │                │
│              └──────────────────────────────┘                │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

The UI is designed to feel premium and polished while remaining highly functional. Whether the user is in the app or watching the agent work in another app, they always have visibility and control.

---

*Ready for implementation in Phase 5.*

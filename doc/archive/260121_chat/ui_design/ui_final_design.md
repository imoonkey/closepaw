# Android Agent — Chat UI Final Design

> **Version**: 2.1 (Revised)  
> **Status**: Ready for Implementation  
> **Date**: 2026-01-21  
> **Target**: Phase 5 — World-Class Chat Experience  
> **Dependencies**: Backend streaming complete (Phase 1-4). See `chat_final_design.md` and `doc/main/agent_protocol.md`.

---

## 0. Design Vision: Invisible Intelligence

> *The best interface is no interface. The agent should feel like a capable companion that just works.*

### Philosophy

Android Agent is not a developer tool. It is a **consumer product** that happens to be incredibly powerful. The UI must:

| Principle | Meaning |
|-----------|---------|
| **Invisible** | The interface disappears; only the conversation remains |
| **Intelligent** | Every pixel communicates the agent's competence |
| **Immediate** | Streaming, real-time feedback makes the agent feel alive |
| **Trustworthy** | Users see what the agent does, building confidence |
| **Ubiquitous** | The Smart Capsule follows users across all apps |

### Design Language

- **Aesthetic**: Modern minimalist with premium touches
- **Mood**: Calm, confident, professional
- **Inspiration**: ChatGPT's simplicity + Linear's polish + Arc's innovation

---

## 1. Information Architecture

### 1.1 Screen Hierarchy

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │               CHAT SCREEN                            │   │  ← Primary
│  │           (Full conversation)                        │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              SETTINGS SHEET                          │   │  ← On-demand (rare)
│  │         (Modal bottom sheet)                         │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              SMART CAPSULE                           │   │  ← System overlay (comm as the system operates apps)
│  │        (Floating when in other apps)                 │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 Navigation Model

| Action | Result |
|--------|--------|
| App launch | Chat Screen (always) |
| Swipe up from bottom edge | Settings Sheet |
| Long-press brand in header | Settings Sheet (power user) |
| Tap Smart Capsule | Return to Chat Screen |
| System back | Minimize app (no back stack) |

**Key Decision**: No settings icon in header. The main screen is 100% conversation.

---

## 2. Chat Screen

### 2.1 Layout Structure

```
┌─────────────────────────────────────────────────────────────┐
│ ░░░░░░░░░░░░░░░░░░░ STATUS BAR ░░░░░░░░░░░░░░░░░░░░░░░░░░░ │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                     HEADER                           │   │
│  │   Android Agent                                      │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                   TASK BANNER                        │   │
│  │   ● Working on: "Check my email for urgent items"    │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                                                     │   │
│  │              CONVERSATION AREA                      │   │
│  │              (Scrollable LazyColumn)                │   │
│  │                                                     │   │
│  │   • User messages (right)                           │   │
│  │   • Agent messages with streaming (left)            │   │
│  │   • Action cards inline                             │   │
│  │   • Thinking indicator                              │   │
│  │                                                     │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                   INPUT DOCK                         │   │
│  │   ┌─────────────────────────────────┐  ┌─────┐      │   │
│  │   │ What can I help with?           │  │  ➤  │      │   │
│  │   └─────────────────────────────────┘  └─────┘      │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│ ░░░░░░░░░░░░░░░░░░ NAVIGATION BAR ░░░░░░░░░░░░░░░░░░░░░░░░ │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Header

**Minimal. Clean. Confident.**

```kotlin
@Composable
fun ChatHeader(
    onSettingsLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 20.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { onSettingsLongPress() }
                    )
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "Android Agent",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
```

> **Note**: Long-press on the header opens the Settings Sheet (see Section 1.2 Navigation Model).

| Property | Value |
|----------|-------|
| Height | 56dp |
| Padding | 20dp horizontal |
| Typography | 20sp, SemiBold |
| Icons | None (settings via gesture) |

### 2.3 Task Banner

**The user's compass.** Shows current context without clutter.

```
┌─────────────────────────────────────────────────────────────┐
│  ●  Working on: "Check my email for urgent items"           │
│  ↑                                                          │
│  Pulsing dot (animated)                                     │
└─────────────────────────────────────────────────────────────┘
```

**States:**

| State | Visual |
|-------|--------|
| **Idle** | Hidden (no banner) |
| **Starting** | "Starting..." with pulsing dot |
| **Working** | "Working on: {user_input}" (truncated) |
| **Phase** | "Perceiving / Planning / Executing" subtitle |
| **Completed** | "✓ Done" → fades out after 2s |
| **Error** | "⚠ Something went wrong" with red dot |

```kotlin
@Composable
fun TaskBanner(
    state: TaskBannerState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state !is TaskBannerState.Idle,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut()
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Animated status dot
                PulsingDot(
                    color = state.dotColor,
                    isPulsing = state is TaskBannerState.Working
                )
                
                Spacer(Modifier.width(12.dp))
                
                Column {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.subtitle != null) {
                        Text(
                            text = state.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

sealed interface TaskBannerState {
    val dotColor: Color
    val title: String
    val subtitle: String?
    
    data object Idle : TaskBannerState {
        override val dotColor = Color.Transparent
        override val title = ""
        override val subtitle: String? = null
    }
    
    data class Working(
        val taskTitle: String,
        val phase: String? = null  // e.g., "Perceiving", "Planning", "Executing"
    ) : TaskBannerState {
        override val dotColor = Color(0xFF2563EB) // Primary blue
        override val title = "Working on: $taskTitle"
        override val subtitle = phase
    }
    
    data class Completed(
        val summary: String
    ) : TaskBannerState {
        override val dotColor = Color(0xFF0D9488) // Success teal
        override val title = "✓ $summary"
        override val subtitle: String? = null
    }
    
    data class Error(
        val message: String
    ) : TaskBannerState {
        override val dotColor = Color(0xFFDC2626) // Error red
        override val title = "⚠ $message"
        override val subtitle: String? = null
    }
}
```

### 2.4 Conversation Area

#### Message Types

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
        val content: String,              // Immutable; ViewModel manages StringBuilder internally
        val state: AgentMessageState,
        val actions: List<ActionCardData>
    ) : ChatMessage
}

enum class AgentMessageState {
    Thinking,   // Before first delta
    Streaming,  // Receiving deltas
    Complete    // Task turn finished
}
```

#### User Message Bubble

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
| Background | Primary (`#2563EB`) |
| Text Color | White |
| Corner Radius | 20dp (top-left, top-right, bottom-left), 6dp (bottom-right) |
| Max Width | 85% of screen |
| Padding | 16dp horizontal, 12dp vertical |
| Typography | 16sp, Regular |
| Alignment | End (right) |

#### Agent Message Bubble

```
┌────────────────────────────────────────────────────────────┐
│                                                            │
│  I'll check your email now.                                │
│                                                            │
│  ┌────────────────────────────────────────────────────┐   │
│  │  📧  Opening Gmail                           ✓      │   │
│  │      Launched successfully                          │   │
│  └────────────────────────────────────────────────────┘   │
│                                                            │
│  You have 3 unread emails. One from your manager about     │
│  the Q4 report — marked urgent.█                           │
│                                                            │
└────────────────────────────────────────────────────────────┘
12:34 PM
```

| Property | Value |
|----------|-------|
| Background | Surface Variant (`#F5F5F5`) |
| Text Color | On-Surface (`#171717`) |
| Corner Radius | 6dp (top-left), 20dp (others) |
| Max Width | 90% of screen |
| Padding | 16dp horizontal, 12dp vertical |
| Streaming Cursor | Blinking block (`█`) at end |

#### Streaming Text Component

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
            val alpha by rememberInfiniteTransition(label = "cursor").animateFloat(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(530, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "cursorAlpha"
            )
            
            Text(
                text = "█",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
            )
        }
    }
}
```

#### Thinking Indicator

Appears when agent is processing before first delta:

```kotlin
@Composable
fun ThinkingIndicator(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(3) { index ->
                val delay = index * 200
                val infiniteTransition = rememberInfiniteTransition(label = "dot$index")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, delayMillis = delay),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "dotAlpha$index"
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
}
```

### 2.5 Action Cards

**The "Wow Factor"** — Action cards tell the story of what the agent is doing.

```
┌────────────────────────────────────────────────────────────┐
│                                                            │
│  📧  Opening Gmail                                    ✓    │
│      Launched successfully                                 │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

**States with visual progression:**

| State | Icon | Border | Background |
|-------|------|--------|------------|
| **Proposed** | Tool icon | Dashed, muted | Surface |
| **Executing** | Spinner | Solid, primary | Primary/5% |
| **Success** | ✓ Checkmark | Solid, success | Success/10% |
| **Failed** | ✗ X mark | Solid, error | Error/10% |
| **Skipped** | — Dash | Dashed, muted | Surface |

```kotlin
data class ActionCardData(
    val id: String,
    val toolName: String,
    val toolIcon: ImageVector,
    val description: String,
    val state: ActionState,
    val resultSummary: String? = null,
    val expandedContent: String? = null
)

enum class ActionState {
    Proposed, Executing, Success, Failed, Skipped
}

@Composable
fun ActionCard(
    data: ActionCardData,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    val backgroundColor = when (data.state) {
        ActionState.Proposed -> MaterialTheme.colorScheme.surface
        ActionState.Executing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
        ActionState.Success -> Color(0xFF0D9488).copy(alpha = 0.10f)
        ActionState.Failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
        ActionState.Skipped -> MaterialTheme.colorScheme.surface
    }
    
    val borderColor = when (data.state) {
        ActionState.Proposed -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ActionState.Executing -> MaterialTheme.colorScheme.primary
        ActionState.Success -> Color(0xFF0D9488)
        ActionState.Failed -> MaterialTheme.colorScheme.error
        ActionState.Skipped -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (data.expandedContent != null) {
                    Modifier.clickable { expanded = !expanded }
                } else Modifier
            ),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Tool icon
                Icon(
                    imageVector = data.toolIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(Modifier.width(12.dp))
                
                // Content
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = data.toolName,
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (data.resultSummary != null) {
                        Text(
                            text = data.resultSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Status indicator
                ActionStatusIcon(state = data.state)
            }
            
            // Expandable content
            AnimatedVisibility(visible = expanded && data.expandedContent != null) {
                Text(
                    text = data.expandedContent ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                )
            }
        }
    }
}
```

### 2.6 Input Dock

**Always ready. Contextually adaptive.**

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  ┌───────────────────────────────────────────────┐ ┌─────┐ │
│  │                                               │ │     │ │
│  │  What can I help with?                        │ │  ➤  │ │
│  │                                               │ │     │ │
│  └───────────────────────────────────────────────┘ └─────┘ │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**When task is running:**

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
fun InputDock(
    state: InputState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    val isWorking = state == InputState.Working
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.Bottom
        ) {
            // Text field
            OutlinedTextField(
                value = if (isWorking) "" else text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                enabled = !isWorking,
                placeholder = {
                    Text(
                        text = if (isWorking) "Agent is working..." else "What can I help with?",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                maxLines = 4
            )
            
            Spacer(Modifier.width(12.dp))
            
            // Send/Stop button
            FilledIconButton(
                onClick = {
                    if (isWorking) {
                        onStop()
                    } else if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                },
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isWorking)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Crossfade(targetState = isWorking, label = "buttonIcon") { working ->
                    Icon(
                        imageVector = if (working) Icons.Rounded.Stop else Icons.Rounded.Send,
                        contentDescription = if (working) "Stop" else "Send"
                    )
                }
            }
        }
    }
}

enum class InputState {
    Idle,    // Ready for input
    Working  // Task running
}
```

### 2.7 Empty State

First launch experience:

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│                                                             │
│                          🤖                                 │
│                                                             │
│                    Android Agent                            │
│                                                             │
│         Your AI assistant for everything on Android         │
│                                                             │
│                                                             │
│     ┌─────────────────────────────────────────────┐        │
│     │  💡  "Check my unread emails"               │        │
│     └─────────────────────────────────────────────┘        │
│                                                             │
│     ┌─────────────────────────────────────────────┐        │
│     │  📱  "Turn on Do Not Disturb"               │        │
│     └─────────────────────────────────────────────┘        │
│                                                             │
│     ┌─────────────────────────────────────────────┐        │
│     │  🔍  "Search for nearby restaurants"        │        │
│     └─────────────────────────────────────────────┘        │
│                                                             │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

Tapping a suggestion fills the input field.

---

## 3. Smart Capsule (Overlay / 悬浮窗)

### 3.1 Design Philosophy

The Smart Capsule is not just a control bar — it is the **embodied presence** of the agent when running in other apps. It should feel alive, responsive, and confident.

### 3.2 Form Factor

**Shape**: Capsule (fully rounded pill)  
**Position**: Bottom center, 24dp above navigation bar  
**Interaction**: Tap to open app, long-press for controls

```
┌───────────────────────────────────────────────────────────────┐
│                                                               │
│                     TARGET APP (Gmail, etc.)                  │
│                                                               │
│                                                               │
│                                                               │
│                                                               │
│                                                               │
│                                                               │
│      ┌─────────────────────────────────────────────────┐     │
│      │  ●  Opening Gmail...              ⏸   ⏹   ↗   │     │
│      └─────────────────────────────────────────────────┘     │
│                                                               │
│ ░░░░░░░░░░░░░░░░░░ NAVIGATION BAR ░░░░░░░░░░░░░░░░░░░░░░░░░░ │
└───────────────────────────────────────────────────────────────┘
```

### 3.3 Capsule States

The capsule **morphs** based on agent state:

| State | Visual | Behavior |
|-------|--------|----------|
| **Idle** | Small pill, translucent | Minimized presence |
| **Thinking** | Pulsing glow, "Thinking..." | Agent processing |
| **Acting** | Expanded, action text | Shows current tool |
| **Streaming** | Expanded, scrolling text | Live agent response |
| **Success** | Brief green flash | Task complete |
| **Error** | Red tint, shake | Something went wrong |
| **Paused** | Amber tint, "Paused" | User paused execution |

### 3.4 Layout Structure

```kotlin
// Compact state (default)
┌───────────────────────────────────────────────────────┐
│  ●  Opening Gmail...                │  ⏸  │  ⏹  │ ↗  │
│  ↑   ↑                                 ↑     ↑    ↑  │
│  │   └─ Truncated status text          │     │    │  │
│  └─ Animated status dot                │     │    │  │
│                               Pause  Stop  Open App  │
└───────────────────────────────────────────────────────┘

// Expanded state (tap to expand, shows streaming)
┌───────────────────────────────────────────────────────┐
│  ╭─────────────────────────────────────────────────╮  │
│  │  I'm checking your email. Found 3 unread...█    │  │
│  │                                                  │  │
│  │  ┌──────────────────────────────────────────┐   │  │
│  │  │  📧  Opening Gmail                  ✓    │   │  │
│  │  └──────────────────────────────────────────┘   │  │
│  ╰─────────────────────────────────────────────────╯  │
│                                                       │
│   ┌─────────┐    ┌─────────┐    ┌─────────────────┐  │
│   │ ⏸ Pause │    │ ⏹ Stop  │    │   ↗ Open App   │  │
│   └─────────┘    └─────────┘    └─────────────────┘  │
└───────────────────────────────────────────────────────┘
```

### 3.5 Streaming in Overlay

The capsule shows **live streaming text** from the agent, truncated for space.

**Integration**: The `SmartCapsuleManager` is called from `AgentService` (which collects the event stream), not directly from the ViewModel. This keeps overlay management in the service layer where it has `AccessibilityService` context.

```kotlin
class SmartCapsuleManager(
    private val context: AccessibilityService,
    private val onStop: () -> Unit,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onOpenApp: () -> Unit
) {
    private val streamingText = StringBuilder()
    private var currentTurnId: String? = null
    private var isExpanded = false
    private val handler = Handler(Looper.getMainLooper())
    
    // Called by AgentService when collecting AgentEvent.MessageDelta
    fun onMessageDelta(turnId: String, delta: String) {
        if (turnId != currentTurnId) {
            streamingText.clear()
            currentTurnId = turnId
        }
        streamingText.append(delta)
        updateStatusText(streamingText.toString())
    }
    
    private fun updateStatusText(text: String) {
        val displayText = if (isExpanded) {
            text.take(200)  // More text in expanded mode
        } else {
            text.take(50).replace("\n", " ")  // Single line in compact
        }
        statusTextView?.post {
            statusTextView?.text = displayText.ifEmpty { "Thinking..." }
        }
    }
    
    // Called by AgentService when collecting AgentEvent.TaskStarted
    fun onTaskStarted(taskId: String, userInput: String) {
        streamingText.clear()
        currentTurnId = null
        show()
        setStatusDot(color = colorPrimary, pulsing = true)
        setStatusText("Working on: ${userInput.take(30)}...")
    }
    
    // Called by AgentService when collecting AgentEvent.ActionExecuted
    fun onActionExecuted(toolName: String, success: Boolean) {
        setStatusDot(color = if (success) colorSuccess else colorError)
        setStatusText("$toolName ${if (success) "✓" else "✗"}")
    }
    
    // Called by AgentService when collecting AgentEvent.TaskCompleted
    fun onTaskCompleted() {
        setStatusDot(color = colorSuccess, pulsing = false)
        setStatusText("✓ Done")
        handler.postDelayed({ hide() }, 3000)
    }
}
```

### 3.6 "Open App" Button

The "Open App" button (↗) brings the user back to the Android Agent app's Chat Screen. Implementation:

```kotlin
// In AgentService - when setting up SmartCapsuleManager
private fun openAgentApp() {
    val intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    startActivity(intent)
}
```

### 3.7 Visual Specifications

| Property | Value |
|----------|-------|
| Height (compact) | 48dp |
| Height (expanded) | 280dp |
| Width | Screen width - 32dp margins |
| Corner Radius | 24dp (capsule) |
| Background | White with subtle shadow |
| Border | 1dp, `#E5E5E5` |
| Status Dot | 8dp, color-coded |
| Typography | 14sp, Medium weight |
| Button Size | 40dp circular |

### 3.8 Colors

```kotlin
private val colorBackground = 0xFFFFFFFF.toInt()
private val colorBorder = 0xFFE5E5E5.toInt()
private val colorPrimary = 0xFF2563EB.toInt()     // Blue - working
private val colorSuccess = 0xFF0D9488.toInt()     // Teal - success
private val colorError = 0xFFDC2626.toInt()       // Red - error
private val colorWarning = 0xFFF59E0B.toInt()     // Amber - paused
private val colorText = 0xFF171717.toInt()
private val colorTextMuted = 0xFF6B7280.toInt()
```

### 3.9 Animations

```kotlin
// Pulsing status dot while streaming
private fun startPulsingAnimation() {
    val animator = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.4f, 1f).apply {
        duration = 1000
        repeatCount = ObjectAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
    }
    animator.start()
}

// Shake animation for errors
private fun shakeAnimation() {
    val animator = ObjectAnimator.ofFloat(capsuleView, "translationX", 0f, 10f, -10f, 10f, -10f, 0f).apply {
        duration = 300
        interpolator = LinearInterpolator()
    }
    animator.start()
}

// Width expansion animation
private fun expandCapsule() {
    val animator = ValueAnimator.ofInt(compactHeight, expandedHeight).apply {
        duration = 250
        interpolator = FastOutSlowInInterpolator()
        addUpdateListener { animation ->
            val params = capsuleView.layoutParams
            params.height = animation.animatedValue as Int
            capsuleView.layoutParams = params
        }
    }
    animator.start()
}
```

---

## 4. Settings Sheet

**Access**: Long-press brand in header, or swipe up from bottom edge.

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
│   │  🔄  Max Turns                                      │  │
│   │      10                                        ▼    │  │
│   └─────────────────────────────────────────────────────┘  │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐  │
│   │  ✋  Accessibility Service                          │  │
│   │      Enabled                                   ●    │  │
│   └─────────────────────────────────────────────────────┘  │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐  │
│   │  🪟  Overlay Permission                             │  │
│   │      Enabled                                   ●    │  │
│   └─────────────────────────────────────────────────────┘  │
│                                                             │
│   ──────────────────────────────────────────────────────   │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐  │
│   │  🗑️  Clear Conversation                             │  │
│   └─────────────────────────────────────────────────────┘  │
│                                                             │
│   ┌─────────────────────────────────────────────────────┐  │
│   │  ℹ️  About & Debug                                  │  │
│   └─────────────────────────────────────────────────────┘  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 5. Visual System

### 5.1 Color Palette

#### Light Theme

```kotlin
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
    errorContainer = Color(0xFFFEE2E2),
    
    // Outline
    outline = Color(0xFFD4D4D4),
    outlineVariant = Color(0xFFE5E5E5)
)
```

#### Dark Theme

```kotlin
val DarkColorScheme = darkColorScheme(
    // Primary - Brighter blue
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF1E3A5F),
    primaryContainer = Color(0xFF1E40AF),
    onPrimaryContainer = Color(0xFFDBEAFE),
    
    // Secondary
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF0F3D38),
    secondaryContainer = Color(0xFF115E59),
    onSecondaryContainer = Color(0xFFCCFBF1),
    
    // Surface - Deep grays
    surface = Color(0xFF171717),
    onSurface = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFA3A3A3),
    
    // Background
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFFAFAFA),
    
    // Error
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    
    // Outline
    outline = Color(0xFF404040),
    outlineVariant = Color(0xFF262626)
)
```

### 5.2 Typography

```kotlin
val AgentTypography = Typography(
    // Display - Empty state title
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp
    ),
    
    // Title - Header
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    
    // Body - Chat messages
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    
    // Labels - Action cards, timestamps
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
```

### 5.3 Spacing System

Based on 4dp grid:

| Token | Value | Usage |
|-------|-------|-------|
| `xs` | 4dp | Tight gaps |
| `sm` | 8dp | Component internal |
| `md` | 12dp | Between related items |
| `lg` | 16dp | Section padding |
| `xl` | 24dp | Major sections |
| `xxl` | 32dp | Screen margins |

### 5.4 Shapes

```kotlin
val AgentShapes = Shapes(
    small = RoundedCornerShape(8.dp),    // Chips, small cards
    medium = RoundedCornerShape(12.dp),  // Action cards
    large = RoundedCornerShape(20.dp)    // Bubbles, sheets
)

// Special shapes
val BubbleShapeUser = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomStart = 20.dp,
    bottomEnd = 6.dp
)

val BubbleShapeAgent = RoundedCornerShape(
    topStart = 6.dp,
    topEnd = 20.dp,
    bottomStart = 20.dp,
    bottomEnd = 20.dp
)

val CapsuleShape = RoundedCornerShape(24.dp)
val PillShape = RoundedCornerShape(50)
```

---

## 6. Motion Design

### 6.1 Principles

1. **Swift**: 150-250ms for UI transitions
2. **Smooth**: Spring physics, not linear
3. **Subtle**: Enhance, don't distract
4. **Semantic**: Animation conveys meaning

### 6.2 Animation Specs

| Animation | Duration | Easing | Notes |
|-----------|----------|--------|-------|
| Message appear | 200ms | `FastOutSlowIn` | Slide up + fade |
| Streaming cursor | 530ms | Linear | Blink cycle |
| Thinking dots | 600ms/dot | `EaseInOutSine` | Staggered 200ms |
| Button state | 200ms | `FastOutSlowIn` | Send ↔ Stop morph |
| Action card state | 150ms | `FastOutSlowIn` | Color transition |
| Task Banner | 250ms | Spring | Slide in/out |
| Settings sheet | 350ms | Spring (stiffness=400) | Bottom sheet |
| Capsule expand | 250ms | `FastOutSlowIn` | Height change |

### 6.3 Reduced Motion

Respect system preference:

```kotlin
@Composable
fun conditionalAnimation(): FiniteAnimationSpec<*> {
    val reducedMotion = LocalReducedMotion.current
    return if (reducedMotion) {
        snap()
    } else {
        tween(durationMillis = 200)
    }
}
```

---

## 7. State Management

### 7.1 ViewModel

```kotlin
class ChatViewModel(
    private val session: AgentSession
) : ViewModel() {
    
    // UI State
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    // Messages (observable list for Compose)
    private val _messages = mutableStateListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages
    
    // Task banner state
    private val _taskBannerState = MutableStateFlow<TaskBannerState>(TaskBannerState.Idle)
    val taskBannerState: StateFlow<TaskBannerState> = _taskBannerState.asStateFlow()
    
    // Internal: accumulates streaming text for current agent message
    private val streamingBuffer = StringBuilder()
    private var currentAgentMessageId: String? = null
    
    init {
        viewModelScope.launch {
            session.events.collect { event ->
                handleEvent(event)
            }
        }
    }
    
    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TaskStarted -> {
                _uiState.update { it.copy(inputState = InputState.Working, showEmptyState = false) }
                _taskBannerState.value = TaskBannerState.Working(
                    taskTitle = event.input.take(50)
                )
                
                // Add user message
                _messages.add(ChatMessage.User(
                    id = UUID.randomUUID().toString(),
                    timestamp = event.timestamp,
                    text = event.input
                ))
                
                // Prepare agent message placeholder
                streamingBuffer.clear()
                currentAgentMessageId = event.taskId
                _messages.add(ChatMessage.Agent(
                    id = event.taskId,
                    timestamp = event.timestamp,
                    content = "",
                    state = AgentMessageState.Thinking,
                    actions = emptyList()
                ))
            }
            
            is AgentEvent.TurnPhaseChanged -> {
                val current = _taskBannerState.value
                if (current is TaskBannerState.Working) {
                    _taskBannerState.value = current.copy(phase = event.phase.name)
                }
            }
            
            is AgentEvent.MessageDelta -> {
                streamingBuffer.append(event.delta)
                updateLastAgentMessage { msg ->
                    msg.copy(
                        content = streamingBuffer.toString(),
                        state = AgentMessageState.Streaming
                    )
                }
            }
            
            is AgentEvent.ActionExecuted -> {
                val newAction = ActionCardData(
                    id = event.actionId,
                    toolName = event.toolName,
                    toolIcon = getToolIcon(event.toolName),
                    description = event.result ?: event.toolName,
                    state = if (event.success) ActionState.Success else ActionState.Failed,
                    resultSummary = event.result
                )
                updateLastAgentMessage { msg ->
                    msg.copy(actions = msg.actions + newAction)
                }
            }
            
            is AgentEvent.TaskCompleted -> {
                _uiState.update { it.copy(inputState = InputState.Idle) }
                _taskBannerState.value = TaskBannerState.Completed(
                    summary = event.result ?: "Task complete"
                )
                
                updateLastAgentMessage { msg ->
                    msg.copy(state = AgentMessageState.Complete)
                }
                
                // Reset streaming state
                streamingBuffer.clear()
                currentAgentMessageId = null
                
                // Auto-hide banner after delay
                viewModelScope.launch {
                    delay(2000)
                    _taskBannerState.value = TaskBannerState.Idle
                }
            }
            
            is AgentEvent.SessionError -> {
                _uiState.update { it.copy(inputState = InputState.Idle) }
                _taskBannerState.value = TaskBannerState.Error(event.error.message)
            }
            
            else -> { /* Ignore other events */ }
        }
    }
    
    // Helper: update the last agent message in the list
    private inline fun updateLastAgentMessage(transform: (ChatMessage.Agent) -> ChatMessage.Agent) {
        val index = _messages.indexOfLast { it is ChatMessage.Agent }
        if (index >= 0) {
            val current = _messages[index] as ChatMessage.Agent
            _messages[index] = transform(current)
        }
    }
    
    // Helper: map tool names to icons
    // Note: Uses standard Material Icons. Some may need alternatives based on availability.
    private fun getToolIcon(toolName: String): ImageVector = when (toolName) {
        "click" -> Icons.Rounded.TouchApp
        "type" -> Icons.Rounded.Keyboard
        "scroll" -> Icons.Rounded.UnfoldMore      // Vertical scroll indicator
        "swipe" -> Icons.Rounded.Swipe            // Or use SwipeRight if unavailable
        "back" -> Icons.Rounded.ArrowBack
        "home" -> Icons.Rounded.Home
        "wait" -> Icons.Rounded.HourglassEmpty
        "complete_task" -> Icons.Rounded.CheckCircle
        else -> Icons.Rounded.Build
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
    
    fun clearConversation() {
        _messages.clear()
        streamingBuffer.clear()
        currentAgentMessageId = null
        _uiState.update { it.copy(showEmptyState = true) }
    }
}

data class ChatUiState(
    val inputState: InputState = InputState.Idle,
    val showEmptyState: Boolean = true
)
```

---

## 8. Event → UI Mapping

| AgentEvent | UI Update |
|------------|-----------|
| `TaskStarted` | Add user message, prepare agent bubble, show Task Banner, disable input |
| `TurnPhaseChanged` | Update Task Banner subtitle (Perceiving/Planning/Executing) |
| `MessageDelta` | Append to agent bubble, show streaming cursor |
| `ActionExecuted` | Add action card (Success/Failed state) to agent bubble |
| `ApprovalRequired` | Show approval card in bubble (future) |
| `TaskCompleted` | Mark bubble complete, enable input, show "Done" in Task Banner |
| `SessionError` | Show error in Task Banner, enable input |
| `SessionCompleted` | Final cleanup, show completion reason if needed |

> **Note**: The UI primarily reacts to Task-level events (`TaskStarted`, `TaskCompleted`) and streaming events (`MessageDelta`). Turn-level events (`TurnStarted`, `TurnCompleted`) are optional for advanced displays.

---

## 9. Accessibility

### 9.1 Requirements (WCAG AA)

| Requirement | Implementation |
|-------------|----------------|
| Color contrast | 4.5:1 minimum for all text |
| Touch targets | 48dp × 48dp minimum |
| Screen reader | `contentDescription` on all interactive elements |
| Focus order | Logical tab order through message list |
| Motion | Respect `prefers-reduced-motion` |
| Font scaling | Support up to 200% scale |

### 9.2 Semantic Structure

```kotlin
@Composable
fun AccessibleChatMessage(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val semanticsLabel = when (message) {
        is ChatMessage.User -> "You said: ${message.text}"
        is ChatMessage.Agent -> "Agent replied: ${message.content}"
    }
    
    Box(
        modifier = modifier.semantics {
            contentDescription = semanticsLabel
            if (message is ChatMessage.Agent && message.state == AgentMessageState.Streaming) {
                liveRegion = LiveRegionMode.Polite
            }
        }
    ) {
        // Bubble content
    }
}
```

---

## 10. Implementation Roadmap

> **Backend API**: The ViewModel uses `session.submit(Op.UserInput(text))` to send user input and collects from `session.events: Flow<AgentEvent>` for real-time updates. See `doc/main/agent_protocol.md` for the complete event reference.

### Phase 5.1: Core Chat (MVP)

| Task | Priority |
|------|----------|
| ChatScreen composable with LazyColumn | P0 |
| User message bubble | P0 |
| Agent message bubble with streaming cursor | P0 |
| Thinking indicator | P0 |
| Input dock with send/stop toggle | P0 |
| ChatViewModel with event handling | P0 |
| Task Banner (Working/Complete states) | P0 |
| Light theme implementation | P0 |
| Smart Capsule: streaming text support | P0 |
| Smart Capsule: "Open App" button | P0 |

### Phase 5.2: Polish

| Task | Priority |
|------|----------|
| Action cards (all states) | P1 |
| Empty state with suggestions | P1 |
| Settings bottom sheet | P1 |
| Dark theme | P1 |
| Message animations | P1 |
| Smart Capsule: pulsing/morphing animations | P1 |
| Task Banner: phase display | P1 |

### Phase 5.3: Advanced

| Task | Priority |
|------|----------|
| Smart Capsule: expanded mode | P2 |
| Approval cards | P2 |
| Accessibility audit | P1 |
| Performance optimization (virtualization) | P2 |

---

## 11. File Structure

> **Migration Note**: This structure replaces the current `ui/screen/AgentScreen.kt` with a new chat-based design. The existing theme files (`Color.kt`, `Type.kt`, `Theme.kt`) will be updated with the new color palette and typography. The existing `OverlayManager.kt` will be enhanced to become `SmartCapsuleManager.kt`.

```
app/src/main/kotlin/com/moonkey/androidagent/
├── ui/
│   ├── theme/
│   │   ├── Color.kt              # Light/Dark color schemes (UPDATED)
│   │   ├── Type.kt               # Typography definitions (UPDATED)
│   │   ├── Shape.kt              # Shapes including bubble shapes (NEW)
│   │   └── Theme.kt              # AgentTheme composable (UPDATED)
│   │
│   ├── chat/                     # NEW: Chat-based UI
│   │   ├── ChatScreen.kt         # Main screen composable
│   │   ├── ChatViewModel.kt      # State management
│   │   ├── components/
│   │   │   ├── ChatHeader.kt     # Minimal header
│   │   │   ├── TaskBanner.kt     # Context strip
│   │   │   ├── MessageBubble.kt  # User & Agent bubbles
│   │   │   ├── StreamingText.kt  # Text with cursor
│   │   │   ├── ActionCard.kt     # Tool execution card
│   │   │   ├── ThinkingIndicator.kt
│   │   │   ├── InputDock.kt      # Input area
│   │   │   └── EmptyState.kt     # First launch
│   │   └── model/
│   │       └── ChatMessage.kt    # UI data classes
│   │
│   ├── overlay/
│   │   └── SmartCapsuleManager.kt  # Enhanced overlay (replaces OverlayManager.kt)
│   │
│   ├── settings/
│   │   └── SettingsSheet.kt      # Bottom sheet (NEW)
│   │
│   └── screen/
│       └── AgentScreen.kt        # DEPRECATED - will be removed after migration
```

---

## 12. Summary

This design creates a **world-class conversational experience** that embodies **Invisible Intelligence**:

### In-App Experience
- **Zero friction**: No setup screens, just conversation
- **Always oriented**: Task Banner shows context without clutter
- **Live feedback**: Streaming text and action cards build trust
- **Hidden complexity**: Settings accessible via gesture, not button

### Smart Capsule (Overlay)
- **Ubiquitous presence**: Agent follows user across all apps
- **Morphing states**: Visual feedback through shape and color
- **Streaming preview**: See agent thoughts in real-time
- **Instant control**: Pause, stop, or return to app with single tap

### Visual Identity
- **Premium aesthetic**: Clean, confident, modern
- **Cohesive system**: Colors, typography, motion all aligned
- **Accessible**: WCAG AA compliant

The result is an app that feels like it came from a well-funded startup with world-class design talent — because the agent deserves nothing less.

---

*Ready for implementation. Let's build something extraordinary.*

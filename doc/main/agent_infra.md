# Android Agent Infrastructure

> This document describes the architecture and components of the Android Agent system.

## Table of Contents

1. [Design Principles](#design-principles)
2. [Architecture Overview](#architecture-overview)
3. [Package Structure](#package-structure)
4. [Core Components](#core-components)
5. [Data Flow](#data-flow)
6. [Tool System](#tool-system)
7. [Quick Reference](#quick-reference)

---

## Design Principles

| Principle | Description |
|-----------|-------------|
| **Task-Based Model** | Session > Task > Turn hierarchy. Multi-round interaction via `Idle` state. |
| **Single ReAct Agent** | One agent running Perceive → Think → Act → Observe loop. No multi-agent complexity. |
| **Streaming Responses** | Native OpenAI streaming with `MessageDelta` events for real-time UI updates. |
| **Thin Session Layer** | Session manages lifecycle only. All intelligence lives in the Agent. |
| **Op/Event Protocol** | Clean separation between UI intent (Op) and agent state (Event). |
| **Tools with Observation** | Every tool execution captures post-action screen state. |
| **Service-Oriented DI** | `SessionServices` provides all dependencies to the agent. |

---

## Architecture Overview

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android Application                       │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐                                               │
│  │ MainActivity │ ◄──────────────────────────────────────────┐  │
│  └──────┬───────┘                                            │  │
│         │ runAgent(goal, apiKey)                             │  │
│         ▼                                                    │  │
│  ┌──────────────────┐                                        │  │
│  │  AgentService    │ ◄─ AccessibilityService                │  │
│  │  (Entry Point)   │                                        │  │
│  └──────┬───────────┘                                        │  │
│         │ creates                                            │  │
│         ▼                                                    │  │
│  ┌──────────────────┐        ┌──────────────────┐           │  │
│  │  AgentSession    │───────►│  SessionServices │           │  │
│  │  (Lifecycle)     │        │  (Dependencies)  │           │  │
│  └──────┬───────────┘        └────────┬─────────┘           │  │
│         │ starts                      │                      │  │
│         ▼                             │ provides             │  │
│  ┌──────────────────┐                 │                      │  │
│  │     Agent        │ ◄───────────────┘                      │  │
│  │  (ReAct Loop)    │                                        │  │
│  └──────┬───────────┘                                        │  │
│         │ executes tools via                                 │  │
│         ▼                                                    │  │
│  ┌──────────────────┐        ┌──────────────────┐           │  │
│  │   ToolRouter     │───────►│  AndroidPlatform │           │  │
│  │  (State Machine) │        │  (Accessibility) │           │  │
│  └──────────────────┘        └──────────────────┘           │  │
│                                                              │  │
│  Events (AgentEvent) ────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### ReAct Loop

The agent executes a classic ReAct (Reasoning + Acting) loop within each **Turn**:

```
┌─────────────────────────────────────────────────────────────────┐
│                      ReAct Loop (Per Turn)                       │
│                                                                  │
│   ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌────────┐│
│   │ PERCEIVE │────►│  THINK   │────►│   ACT    │────►│OBSERVE ││
│   │ (Screen) │     │ (LLM +   │     │  (Tool)  │     │(Screen)││
│   │          │     │ Streaming)│     │          │     │        ││
│   └──────────┘     └──────────┘     └──────────┘     └────┬───┘│
│        ▲                │                                 │    │
│        │                │ MessageDelta events             │    │
│        │                ▼                                 │    │
│        │            (UI updates)                          │    │
│        │                                                  │    │
│        └──────────────────────────────────────────────────┘    │
│          (Loop until complete_task or text-only response)        │
└─────────────────────────────────────────────────────────────────┘
```

### Task Lifecycle

```
┌─────────────────────────────────────────────────────────────────┐
│                          Task Lifecycle                          │
│                                                                  │
│   Op.UserInput("Check email")                                    │
│         │                                                        │
│         ▼                                                        │
│   ┌───────────┐                                                  │
│   │TaskStarted│ ◄─── Emit event with taskId                      │
│   └─────┬─────┘                                                  │
│         │                                                        │
│         ▼                                                        │
│   ┌───────────────────────────────────────────┐                  │
│   │  Turn 1: Perceive → Think → Act → Observe │──► MessageDelta  │
│   │  Turn 2: Perceive → Think → Act → Observe │──► MessageDelta  │
│   │  Turn N: ... (complete_task or text-only) │                  │
│   └─────┬─────────────────────────────────────┘                  │
│         │                                                        │
│         ▼                                                        │
│   ┌─────────────┐                                                │
│   │TaskCompleted│ ◄─── Emit event, transition to Idle            │
│   └─────────────┘                                                │
└─────────────────────────────────────────────────────────────────┘
```

---

## Package Structure

```
com.moonkey.androidagent/
│
├── app/                          # Application entry points
│   ├── MainActivity.kt           # UI entry point
│   └── AgentService.kt           # AccessibilityService entry point
│
├── agent/                        # Core agent logic
│   ├── Agent.kt                  # ReAct loop executor
│   ├── AgentConfig.kt            # Agent configuration
│   └── Turn.kt                   # Single LLM turn (OpenAI Responses API)
│
├── session/                      # Session management
│   ├── AgentSession.kt           # Lifecycle manager
│   └── SessionServices.kt        # Dependency injection
│
├── tool/                         # Consolidated tool system
│   │
│   │  # Core abstractions
│   ├── ToolSpec.kt               # Tool interface + types
│   ├── ToolCallState.kt          # State definitions
│   ├── ToolCallResult.kt         # Result types
│   │
│   │  # Infrastructure
│   ├── ToolRegistry.kt           # Discovery/registration
│   ├── ToolRouter.kt             # Execution state machine
│   ├── PolicyEngine.kt           # Approval logic
│   │
│   │  # Implementations
│   ├── BaseTool.kt               # Abstract base class
│   └── impl/                     # Concrete tools
│       ├── ClickTool.kt
│       ├── TypeTool.kt
│       ├── ScrollTool.kt
│       ├── SwipeTool.kt
│       ├── NavigationTools.kt    # BackTool + HomeTool
│       ├── WaitTool.kt
│       └── CompleteTaskTool.kt
│
├── protocol/                     # Communication contracts
│   ├── Op.kt                     # Operations (UI → Agent)
│   ├── AgentEvent.kt             # Events (Agent → UI)
│   ├── SessionState.kt           # State machine
│   ├── SessionId.kt              # ID value class
│   ├── AgentError.kt             # Error types
│   └── ApprovalTypes.kt          # Approval enums
│
├── platform/                     # Android platform abstraction
│   ├── AndroidPlatform.kt        # Interface
│   ├── AccessibilityPlatform.kt  # Implementation
│   ├── UIAction.kt               # Action types
│   └── ActionResult.kt           # Result types
│
├── perception/                   # Screen perception
│   └── Perceptor.kt              # Accessibility tree → ScreenSnapshot
│
├── llm/                          # LLM integration
│   └── LLMClient.kt              # OpenAI Responses API (with streaming)
│
├── history/                      # Conversation history
│   └── HistoryManager.kt         # Token management, truncation
│
├── model/                        # Domain models
│   └── Models.kt                 # ScreenSnapshot, PerceptionElement, etc.
│
├── ui/                           # UI layer
│   ├── theme/
│   │   ├── Color.kt              # Light/Dark color schemes
│   │   ├── Shape.kt              # Bubble shapes, card shapes
│   │   ├── Theme.kt              # ChatTheme composable
│   │   └── Type.kt               # Typography scale
│   ├── chat/                     # Chat-based UI (Phase 5)
│   │   ├── ChatScreen.kt         # Main screen composable
│   │   ├── ChatViewModel.kt      # State management
│   │   ├── components/           # ChatHeader, TaskBanner, MessageBubble, etc.
│   │   └── model/
│   │       └── ChatMessage.kt    # UI data classes
│   ├── overlay/
│   │   └── SmartCapsuleManager.kt # Streaming overlay (enhanced)
│   ├── settings/
│   │   └── SettingsSheet.kt      # Configuration bottom sheet
│   └── screen/
│       └── AgentScreen.kt        # DEPRECATED (kept for reference)
│
└── util/
    └── StatusUtils.kt
```

---

## Core Components

### 1. Agent (`agent/Agent.kt`)

The brain of the system. Executes the ReAct loop until goal achieved or stopped.

**Responsibilities:**
- Run the Perceive → Think → Act → Observe cycle
- Manage turn count and stop conditions
- Emit events for UI updates
- Handle pause/resume/stop lifecycle

**Key Methods:**
- `run()` - Main loop, returns `AgentStopReason`
- `executeTurn()` - Single turn execution
- `pause()`, `resume()`, `stop()` - Lifecycle control

### 2. Turn (`agent/Turn.kt`)

Encapsulates a single LLM call using the OpenAI Responses API with native tool calling and **streaming support**.

**Responsibilities:**
- Build input items from history + current context
- Generate tool schemas dynamically via `ToolRegistry.generateResponsesApiTools()`
- **Stream** text and tool calls via `runStreaming()` method
- Process structured tool calls (using `call_id` for linkage) and text outputs
- Detect completion (via `complete_task` tool or text-only response)

**Streaming Method:**
```kotlin
fun runStreaming(...): Flow<TurnStreamEvent>
```

**Stream Events (`TurnStreamEvent`):**
```kotlin
sealed interface TurnStreamEvent {
    data class TextDelta(val text: String) : TurnStreamEvent      // Streaming text chunk
    data class ToolCallReceived(val toolCall: ToolCallRequest)    // Tool call ready
    data class Complete(val result: TurnResult) : TurnStreamEvent // Turn finished
    data class Error(val error: Throwable) : TurnStreamEvent      // Error occurred
}
```

**Final Result (`TurnResult`):**
```kotlin
data class TurnResult(
    val content: String?,           // Accumulated text from LLM
    val toolCalls: List<ToolCallRequest>,  // Tool calls to execute
    val isComplete: Boolean,        // Whether task is done
    val parseErrors: List<String>?  // Parsing issues (rare with Responses API)
)
```

### 3. AgentSession (`session/AgentSession.kt`)

Thin lifecycle manager. Does NOT contain agent logic.

**Responsibilities:**
- Process Operations (Op) from UI
- Emit Events (AgentEvent) to UI
- Manage session state transitions (including `Idle` for multi-round)
- Manage Task lifecycle via `handleUserInput()`
- Create and start Agent

**Key Methods:**
- `submit(op: Op)` - Submit an operation
- `handleUserInput(text: String)` - Primary entry point for starting Tasks
- `events: Flow<AgentEvent>` - Event stream for UI
- `state: StateFlow<SessionState>` - Current session state

**State Transitions:**
```
Created ──(UserInput)──► Running ──(TaskCompleted)──► Idle ──(UserInput)──► Running
                                                        │
                                                        ▼
                                                    Shutdown
```

### 4. SessionServices (`session/SessionServices.kt`)

Dependency injection container for all session-scoped services.

| Service | Purpose |
|---------|---------|
| `toolRegistry` | Tool discovery and schema generation |
| `toolRouter` | Tool execution with state machine |
| `historyManager` | Conversation history management |
| `policyEngine` | Tool approval decisions |
| `platform` | Android operations |
| `config` | Session configuration |
| `llmClient` | OpenAI Responses API client |

**Creation:**
```kotlin
val services = SessionServices.create(config, platform, apiKey)
```

### 5. LLMClient (`llm/LLMClient.kt`)

OpenAI Responses API client with **native streaming** support.

**Key Method:**
```kotlin
fun chatWithToolsStreaming(
    systemPrompt: String,
    inputItems: List<ResponseInputItem>,
    tools: List<FunctionTool>,
    model: ChatModel = ChatModel.GPT_4O
): Flow<ResponseStreamEvent>
```

**Stream Event Types (from OpenAI Java SDK):**

| Event | Description |
|-------|-------------|
| `response.created` | Response initiated (includes response ID) |
| `response.output_text.delta` | Text chunk via `asOutputTextDelta().delta()` |
| `response.output_item.done` | Output item completed (text or tool call) |
| `response.completed` | Stream finished successfully |
| `response.failed` | Error occurred |

**Implementation Details:**
- Uses `callbackFlow` for coroutine compatibility with blocking SDK stream
- Manual accumulation of text and tool calls (no `ResponseAccumulator`)
- Retry logic with exponential backoff
- Requires OpenAI Java SDK v4.14.0+

### 6. ToolRouter (`tool/ToolRouter.kt`)

Executes tool calls with a state machine lifecycle:

```
VALIDATING → POLICY_CHECK → [AWAITING_APPROVAL] → EXECUTING → SUCCESS/ERROR/CANCELLED
```

**Responsibilities:**
- Validate tool exists and parameters are correct
- Check policy for approval requirements
- Wait for user approval if needed (with 60s timeout)
- Execute tool and return result

### 7. Perceptor (`perception/Perceptor.kt`)

Converts raw AccessibilityNodeInfo tree into semantic ScreenSnapshot.

**Responsibilities:**
- Traverse accessibility tree with proper node recycling
- Extract element data (bounds, text, class) without storing raw nodes
- Limit to MAX_ELEMENTS (80) for token budget
- Generate JSON for LLM prompts via `toPromptJson()`

**Output Element Example:**
```json
{
  "index": 0,
  "text": "Settings",
  "id": "com.android.settings:id/title",
  "class": "TextView",
  "desc": "",
  "clickable": true,
  "editable": false,
  "scrollable": false,
  "center": [540, 120]
}
```

### 8. AndroidPlatform (`platform/AndroidPlatform.kt`)

Abstraction for Android-specific operations.

**Operations:**
- `captureScreen()` - Get current UI state as ScreenSnapshot
- `performAction(action, snapshot)` - Execute UI actions
- `hasRequiredPermissions()` - Check accessibility permission
- `getCurrentPackageName()` - Get foreground app
- `getDisplayInfo()` - Screen dimensions

---

## Data Flow

### Complete Request Flow (with Streaming)

```
User                 UI                AgentSession          Agent             LLM
  │                   │                     │                  │                │
  │ "Check my email"  │                     │                  │                │
  │──────────────────►│                     │                  │                │
  │                   │ Op.UserInput(text)  │                  │                │
  │                   │────────────────────►│                  │                │
  │                   │                     │ TaskStarted      │                │
  │                   │◄────────────────────│                  │                │
  │                   │                     │ create & run()   │                │
  │                   │                     │─────────────────►│                │
  │                   │                     │                  │                │
  │                   │                     │                  │ captureScreen()│
  │                   │                     │                  │◄───────────────│
  │                   │                     │                  │                │
  │                   │                     │                  │ runStreaming() │
  │                   │                     │                  │───────────────►│
  │                   │                     │                  │                │
  │                   │                     │                  │ TextDelta      │
  │                   │◄────── MessageDelta ◄─────────────────│◄───────────────│
  │ "I'll open..."    │                     │                  │ TextDelta      │
  │◄──────────────────│◄────── MessageDelta ◄─────────────────│◄───────────────│
  │                   │                     │                  │                │
  │                   │                     │                  │ Complete       │
  │                   │                     │                  │◄───────────────│
  │                   │                     │                  │                │
  │                   │                     │                  │ ToolRouter.execute()
  │                   │ AgentEvent.ActionExecuted              │ (action + observe)
  │                   │◄────────────────────│◄─────────────────│                │
  │                   │                     │                  │                │
  │                   │   ... loop ...      │                  │                │
  │                   │                     │                  │                │
  │                   │ AgentEvent.TaskCompleted               │                │
  │                   │◄────────────────────│◄─────────────────│                │
  │                   │                     │ State → Idle     │                │
  │ Ready for input   │                     │                  │                │
  │◄──────────────────│                     │                  │                │
```

### Streaming Event Flow

```
Turn.runStreaming()          Agent                AgentSession           UI
       │                       │                       │                  │
       │ TurnStreamEvent.TextDelta("I'll")             │                  │
       │─────────────────────►│                        │                  │
       │                       │ MessageDelta(delta)   │                  │
       │                       │──────────────────────►│                  │
       │                       │                       │─────────────────►│
       │                       │                       │                  │ Append text
       │                       │                       │                  │
       │ TurnStreamEvent.TextDelta(" open...")         │                  │
       │─────────────────────►│                        │                  │
       │                       │ MessageDelta(delta)   │                  │
       │                       │──────────────────────►│                  │
       │                       │                       │─────────────────►│
       │                       │                       │                  │ Append text
       │                       │                       │                  │
       │ TurnStreamEvent.Complete(result)              │                  │
       │─────────────────────►│                        │                  │
       │                       │ Execute tools from result               │
       │                       │────────────────────────────────────────►│
```

---

## Tool System

### Tool Execution Lifecycle

```
┌──────────────┐
│  VALIDATING  │ ─── Invalid ──► ERROR
└──────┬───────┘
       │ Valid
       ▼
┌──────────────┐
│ POLICY_CHECK │ ─── Deny ────► ERROR
└──────┬───────┘
       │ Allow or AskUser
       ▼
┌──────────────┐
│   AWAITING   │ ─── Denied/Timeout ──► CANCELLED
│   APPROVAL   │
└──────┬───────┘
       │ Approved
       ▼
┌──────────────┐
│  EXECUTING   │ ─── Exception ──► ERROR
└──────┬───────┘
       │ Success
       ▼
┌──────────────┐
│   SUCCESS    │
└──────────────┘
```

### Built-in Tools

| Tool | Description | Parameters |
|------|-------------|------------|
| `click` | Click UI element | `element_index: int` |
| `type` | Type text into element | `element_index: int`, `text: string` |
| `scroll` | Scroll screen | `direction: up/down/left/right` |
| `swipe` | Swipe gesture | `start_x`, `start_y`, `end_x`, `end_y` |
| `back` | Press back button | (none) |
| `home` | Press home button | (none) |
| `wait` | Wait for UI | `duration_ms: int` (optional) |
| `complete_task` | Signal goal completion | `summary: string` |

### Adding New Tools

1. Create class extending `BaseTool` in `tool/impl/`
2. Implement required members:
   - `name`, `description`, `parameterSchema`
   - `validate(params)`, `createUIAction(params)`, `getActionDescription(params)`
3. Register in `SessionServices.registerBuiltInTools()`

Example implementation:

```kotlin
class ClickTool : BaseTool() {
    override val name = "click"
    override val description = "Click on a UI element by its index"
    
    override val parameterSchema = createSchema(
        properties = mapOf(
            "element_index" to ("integer" to "The index of the element to click")
        ),
        required = listOf("element_index")
    )
    
    override fun validate(params: JSONObject): ValidationResult {
        val errors = mutableListOf<String>()
        val index = validateRequiredInt(params, "element_index", errors)
        if (index != null && index < 0) errors.add("element_index must be non-negative")
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }
    
    override fun createUIAction(params: JSONObject): UIAction? {
        val index = params.optInt("element_index", -1)
        return if (index >= 0) UIAction.Click(index) else null
    }
    
    override fun getActionDescription(params: JSONObject): String {
        return "Click on element at index ${params.optInt("element_index", -1)}"
    }
}
```

### Tool Observation

Every successful tool execution captures post-action screen state:

```kotlin
// In BaseTool.kt
private suspend fun capturePostActionObservation(context: ToolExecutionContext): ToolObservation? {
    delay(UI_SETTLE_DELAY_MS)  // 300ms
    val snapshot = context.platform.captureScreen()
    val tree = Perceptor.toPromptJson(snapshot)
    return ToolObservation.ScreenState(tree, snapshot.elements.size, snapshot)
}
```

The observation is propagated through `ToolCallResult.Success.observation` and used by the Agent for subsequent tool calls in the same turn.

---

## Quick Reference

### Starting the Agent

```kotlin
// In AgentService
val session = AgentSession.create(config, accessibilityService, scope, apiKey)

// Primary entry point (recommended)
session.submit(Op.UserInput("Open Settings"))

// Deprecated (still works, maps to UserInput)
session.submit(Op.Start(goal = "Open Settings"))
```

### Submitting Operations

```kotlin
// Start a task
session.submit(Op.UserInput("Check my email"))

// Lifecycle ops
session.submit(Op.Pause)
session.submit(Op.Resume)
session.submit(Op.Interrupt)  // Stops task, session stays in Idle
session.submit(Op.Shutdown)   // Terminates session

// User interaction
session.submit(Op.Approve(actionId, ApprovalDecision.APPROVED))
```

### Observing Events (with Streaming)

```kotlin
session.events.collect { event ->
    when (event) {
        // Task lifecycle
        is AgentEvent.TaskStarted -> showThinkingUI()
        is AgentEvent.TaskCompleted -> enableInputField()
        
        // Streaming text
        is AgentEvent.MessageDelta -> appendText(event.delta)
        
        // Actions
        is AgentEvent.ActionExecuted -> showActionResult(event)
        
        // Status
        is AgentEvent.StatusUpdate -> updateUI(event.status)
        
        // Approval
        is AgentEvent.ApprovalRequired -> showApprovalDialog(event.details)
        
        // Session end
        is AgentEvent.SessionCompleted -> handleComplete(event.reason)
    }
}
```

### Session Configuration

```kotlin
val config = SessionConfig(
    maxTurns = 50,           // Max iterations before auto-stop
    actionDelayMs = 2000,    // Delay after actions for UI settle
    approvalMode = ApprovalMode.SMART,  // ALWAYS_ASK, AUTO_APPROVE, or SMART
    model = "gpt-4o",        // LLM model
    debugMode = false        // Verbose logging
)
```

---

*For protocol details, see [agent_protocol.md](./agent_protocol.md). For questions, refer to the source code—it is the ultimate source of truth.*

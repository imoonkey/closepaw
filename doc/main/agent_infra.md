# Android Agent Infrastructure

> This document describes the architecture and components of the Android Agent system.
> Last updated: 2026-02-04

## Table of Contents

1. [Design Principles](#design-principles)
2. [Architecture Overview](#architecture-overview)
3. [Package Structure](#package-structure)
4. [Core Components](#core-components)
5. [Sub-Agent System](#sub-agent-system)
6. [Planning State System](#planning-state-system)
7. [Context Hygiene](#context-hygiene-history-compression)
8. [Session History System](#session-history-system)
9. [Data Flow](#data-flow)
10. [Tool System](#tool-system)
11. [Quick Reference](#quick-reference)

---

## Design Principles

| Principle | Description |
|-----------|-------------|
| **Task-Based Model** | Session > Task > Turn hierarchy. Multi-round interaction via `Idle` state. |
| **Planner-Executor Pattern** | Main agent plans and delegates atomic UI actions to executor sub-agents. |
| **Streaming Responses** | Native OpenAI streaming with `MessageDelta` events for real-time UI updates. |
| **Thin Session Layer** | Session manages lifecycle only. All intelligence lives in the Agent. |
| **Op/Event Protocol** | Clean separation between UI intent (Op) and agent state (Event). |
| **Tools with Observation** | Every tool execution captures post-action screen state. |
| **Service-Oriented DI** | `SessionServices` provides all dependencies to the agent. |
| **Session History Persistence** | Automatic session recording with resume capability. Real-time event-to-file persistence. |
| **Visual Feedback** | Edge glow and action visualization provide ambient feedback during agent execution. |
| **Context Hygiene** | Text-only history (no screenshots/a11y trees). Latest screen injected per turn. |
| **Planning State Tools** | `write_todos` and `scratchpad` for stateful planning and cross-agent data handoff. |

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
│   ├── AgentRuntime.kt           # ReAct loop executor
│   ├── AgentTurnRunner.kt        # Single turn execution
│   ├── AgentConfig.kt            # Agent configuration
│   ├── AgentEventDispatcher.kt   # AgentEvent emission helpers
│   ├── AgentObservation.kt       # Observation types + conversions
│   ├── AgentPromptBuilder.kt     # System prompt + context builder
│   ├── ActionDescriptionFormatter.kt # Tool action descriptions
│   ├── Turn.kt                   # LLM call wrapper (OpenAI Responses API)
│   ├── TurnInputBuilder.kt       # ResponseInputItem assembly
│   └── subagent/                 # Sub-agent delegation
│       ├── AgentDefinition.kt    # Sub-agent definition + request/result
│       ├── AgentRegistry.kt      # Sub-agent discovery
│       ├── SubAgentRunner.kt     # Sub-agent execution with isolation
│       └── ExecutorAgent.kt      # UI grounding executor agent
│
├── session/                      # Session management
│   ├── AgentSession.kt           # Lifecycle manager
│   ├── SessionAgentRunner.kt     # Agent lifecycle runner
│   ├── SessionServices.kt        # Dependency injection
│   ├── AgentSessionState.kt      # Shared state container
│   ├── TodoState.kt              # Todo list state (planning)
│   └── ScratchpadState.kt        # Key-value memory state
│
├── tool/                         # Consolidated tool system
│   │
│   │  # Core abstractions
│   ├── ToolSpec.kt               # Tool interface + types
│   ├── ToolCallState.kt          # State definitions
│   ├── ToolCallResult.kt         # Result types
│   ├── BaseTool.kt               # Single-action UI tools
│   ├── MultiActionTool.kt        # Action dispatch for consolidated tools
│   │
│   │  # Infrastructure
│   ├── ToolRegistry.kt           # Discovery/registration
│   ├── ToolRouter.kt             # Execution state machine
│   ├── PolicyEngine.kt           # Approval logic
│   │
│   │  # Handlers + invocations
│   ├── handlers/
│   │   ├── ActionHandler.kt       # Per-action validation + invocation
│   │   ├── ClickTargetInvocation.kt # Click with multi-selector fallback
│   │   ├── SwipeTargetInvocation.kt # Direction-based swipe with targeting
│   │   ├── UIActionInvocation.kt  # UIAction-backed tool invocation
│   │   └── DataQueryInvocation.kt # Data-only tool invocation
│   │
│   │  # Implementations
│   └── impl/                     # Concrete tools
│       ├── MobileActionTool.kt   # UI interactions (click/type/swipe/system_button)
│       ├── AppControlTool.kt     # list_apps / open_app
│       ├── CompleteTaskTool.kt   # Task completion
│       ├── WriteTodosTool.kt     # Todo list management (planning)
│       ├── ScratchpadTool.kt     # Key-value memory
│       └── DelegateTaskTool.kt   # Sub-agent delegation
│
├── protocol/                     # Communication contracts
│   ├── Op.kt                     # Operations (UI → Agent)
│   ├── AgentEvent.kt             # Events (Agent → UI, includes sub-agent events)
│   ├── SessionState.kt           # State machine
│   ├── SessionId.kt              # ID value class
│   ├── AgentError.kt             # Error types
│   ├── ApprovalTypes.kt          # Approval enums
│   ├── TodoModels.kt             # Todo/TodoStatus models
│   └── ScreenStatePhase.kt       # Screen capture phase enum
│
├── platform/                     # Android platform abstraction
│   ├── AndroidPlatform.kt        # Interface
│   ├── AccessibilityPlatform.kt  # Implementation
│   ├── AccessibilityNodeFinder.kt # Node search helpers
│   ├── UIAction.kt               # Action types
│   └── ActionResult.kt           # Result types
│
├── perception/                   # Screen perception
│   ├── Perceptor.kt              # Accessibility tree → ScreenSnapshot
│   └── ScreenSummary.kt          # Text summary for history compression
│
├── llm/                          # LLM integration
│   ├── LLMClient.kt              # Unified LLM interface + stream events
│   ├── OpenAILLMClient.kt        # OpenAI Responses API client
│   ├── LFMLLMClient.kt           # Local LFM client (Leap SDK)
│   └── LeapFunctionInterop.kt    # Tool schema + argument adapters
│
├── history/                      # Conversation & session history
│   ├── HistoryManager.kt         # Token management, truncation
│   ├── SessionHistoryManager.kt  # High-level session management API
│   ├── SessionRecordingService.kt # Real-time event → persistence bridge
│   ├── AgentMessageBuffer.kt     # Streaming agent message buffer
│   ├── model/
│   │   ├── SessionRecord.kt      # Complete session data (persisted)
│   │   ├── MessageRecord.kt      # Message types (User/Agent)
│   │   ├── SessionInfo.kt        # Lightweight session summary
│   │   └── MessageConverter.kt   # ChatMessage ↔ MessageRecord conversion
│   └── storage/
│       └── SessionStorage.kt     # Low-level file I/O operations
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
│   │   ├── ChatSessionHistoryController.kt # Session history orchestration
│   │   ├── components/           # ChatHeader, TaskBanner, MessageBubble, etc.
│   │   └── model/
│   │       └── ChatMessage.kt    # UI data classes
│   ├── overlay/
│   │   ├── SmartCapsuleManager.kt  # Streaming overlay (enhanced)
│   │   ├── SmartCapsuleLayoutBuilder.kt # Capsule view construction
│   │   ├── EdgeGlowManager.kt      # Edge glow effect during execution
│   │   ├── EdgeGlowView.kt         # Custom glow rendering view
│   │   ├── model/
│   │   │   └── GlowState.kt        # Glow state definitions
│   │   └── visualizer/
│   │       ├── ActionVisualizerManager.kt  # Touch action visualization
│   │       ├── ClickRippleView.kt          # Ripple effect for clicks
│   │       └── SwipeTrailView.kt           # Trail effect for swipes
│   ├── session/                  # Session history UI
│   │   └── TimeUtils.kt          # Relative time formatting
│   ├── settings/
│   │   └── SettingsSheet.kt      # Configuration bottom sheet
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

**Supporting helpers:**
- `AgentPromptBuilder` builds system prompt + user context
- `ActionDescriptionFormatter` formats tool action descriptions
- `AgentEventDispatcher` emits `AgentEvent` with timestamps
- `AgentObservation` converts tool observations into agent observations

**Key Methods:**
- `run()` - Main loop, returns `AgentStopReason`
- `executeTurn()` - Single turn execution
- `pause()`, `resume()`, `stop()` - Lifecycle control

### 2. Turn (`agent/Turn.kt`)

Encapsulates a single LLM call using the OpenAI Responses API with native tool calling and **streaming support**.

**Responsibilities:**
- Build input items from history + current context (via `TurnInputBuilder`)
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
- Delegate agent lifecycle to `SessionAgentRunner`

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
| `llmClient` | LLM client (OpenAI or local LFM) |

**Creation:**
```kotlin
val services = SessionServices.create(config, platform, apiKey)
```

### 5. LLMClient (`llm/LLMClient.kt`)

Unified LLM interface with **native streaming** support for both OpenAI and local LFM.

**Key Method:**
```kotlin
fun chatWithToolsStreaming(
    systemPrompt: String,
    inputItems: List<ResponseInputItem>,
    tools: List<FunctionTool>,
    model: ChatModel = ChatModel.GPT_5_2
): Flow<LLMStreamEvent>
```

**Stream Event Types:**

| Event | Description |
|-------|-------------|
| `Created` | Response initiated (includes response ID) |
| `TextDelta` | Text chunk |
| `ToolCallDone` | Tool call completed |
| `Completed` | Stream finished successfully |
| `Failed` | Error occurred |

**Implementation Details:**
- Uses `callbackFlow` for coroutine compatibility with the OpenAI stream
- Manual accumulation of text and tool calls
- Retry logic with exponential backoff for OpenAI only
- Local backend uses Leap SDK function calling and model download

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
- Filter off-screen elements and elements below minimum size (5px)
- Filter keyboard/IME nodes (Gboard, Samsung, SwiftKey) to reduce noise
- Clip element bounds to screen dimensions
- Limit to MAX_ELEMENTS (80) for token budget
- Generate JSON for LLM prompts via `toPromptJson()` with occurrence indices for disambiguation
- When enabled, allow `AccessibilityPlatform` to attach a compressed screenshot to `ScreenSnapshot.image`

**Output Element Example:**
```json
{
  "index": 0,
  "text": "Settings",
  "resource_id": "com.android.settings:id/title",
  "resource_id_index": 0,
  "text_index": 0,
  "class": "TextView",
  "desc": "",
  "clickable": true,
  "editable": false,
  "scrollable": false,
  "enabled": true,
  "focused": false,
  "long_clickable": false,
  "bounds": [0, 100, 1080, 150],
  "center": [540, 125]
}
```

**Occurrence Indices:**
- `resource_id_index`, `text_index`, `desc_index` - 0-based occurrence count for disambiguation when multiple elements share the same identifier

### 8. AndroidPlatform (`platform/AndroidPlatform.kt`)

Abstraction for Android-specific operations.

**Operations:**
- `captureScreen()` - Get current UI state as ScreenSnapshot
- `performAction(action, snapshot)` - Execute UI actions
- `hasRequiredPermissions()` - Check accessibility permission
- `getCurrentPackageName()` - Get foreground app
- `getDisplayInfo()` - Screen dimensions

**Action Visualization:**

The `AccessibilityPlatform` implementation can integrate with `ActionVisualizerManager` to show visual feedback before executing gestures:

```kotlin
class AccessibilityPlatform(
    private val service: AccessibilityService,
    private val visualizer: ActionVisualizerManager? = null
) {
    private suspend fun performTap(x: Float, y: Float): ActionResult {
        visualizer?.showClick(x, y)  // Show ripple before action
        // ... dispatch gesture
    }
    
    private suspend fun performSwipe(...): ActionResult {
        visualizer?.showSwipe(...)   // Show trail during swipe
        // ... dispatch gesture
    }
}
```

---

## Sub-Agent System

The Android Agent supports a **Planner-Executor** delegation pattern where the main agent acts as a planner and delegates atomic UI actions to specialized sub-agents.

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                     Planner-Executor Pattern                     │
│                                                                  │
│   ┌────────────────────────────────────────────────────┐        │
│   │              Main Agent (Planner)                   │        │
│   │  - Plans multi-step tasks                          │        │
│   │  - Manages todos and scratchpad                    │        │
│   │  - Delegates atomic actions via delegate_task      │        │
│   └───────────────────────┬────────────────────────────┘        │
│                           │                                      │
│                           │ delegate_task(query)                 │
│                           ▼                                      │
│   ┌────────────────────────────────────────────────────┐        │
│   │              Executor Agent (Sub-Agent)             │        │
│   │  - Receives semantic intent                        │        │
│   │  - Grounds to actual UI elements                   │        │
│   │  - Executes ONE atomic action                      │        │
│   │  - Returns result to planner                       │        │
│   └────────────────────────────────────────────────────┘        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Core Components

#### AgentDefinition (`agent/subagent/AgentDefinition.kt`)

Defines a sub-agent that can be invoked via `delegate_task`:

```kotlin
data class AgentDefinition(
    val name: String,           // e.g., "executor"
    val description: String,    // For agent directory prompt
    val systemPrompt: String,   // Sub-agent system prompt
    val toolNames: List<String>, // Tools available to this agent
    val maxTurns: Int = 10,     // Max turns before timeout
    val timeoutMs: Long = 60_000
)
```

#### AgentRegistry (`agent/subagent/AgentRegistry.kt`)

Registry for available sub-agents:

```kotlin
class AgentRegistry {
    fun register(definition: AgentDefinition)
    fun get(name: String): AgentDefinition?
    fun getAll(): List<AgentDefinition>
    fun getDirectoryPrompt(): String  // For delegate_task description
}
```

#### SubAgentRunner (`agent/subagent/SubAgentRunner.kt`)

Executes a sub-agent with isolated context:

- Creates a fresh `AgentRuntime` with filtered tools
- Injects sub-agent system prompt
- Bridges events to parent session
- Returns `SubAgentResult` on completion

**Key isolation properties:**
- Child has its own history (no parent history access)
- Child reads fresh screen state on each turn
- `scratchpad` is intentionally shared for data handoff

#### DelegateTaskTool (`tool/impl/DelegateTaskTool.kt`)

Tool for delegating tasks to sub-agents:

```kotlin
// Usage from main agent
delegate_task(
    agent_name = "executor",
    query = "Tap on the 'Send' button",
    current_subgoal = "Send the email",        // optional
    important_notes = ["Recipient: john@example.com"]  // optional
)
```

### Context Passing

When delegating, pass only:
- `query` — Self-contained instruction (required)
- `current_subgoal` — What we're trying to achieve (optional)
- `important_notes` — Short list of key facts (optional)

Do NOT pass: full history, prior screenshots, prior a11y trees.

The executor reads the current screen in its own turn loop. For structured data handoff, use the shared `scratchpad`.

### Built-in Sub-Agents

| Agent | Description | Tools |
|-------|-------------|-------|
| `executor` | UI grounding and atomic action execution | `mobile_action`, `app_control`, `complete_task` |

---

## Planning State System

The agent uses **planning state tools** to track progress on complex tasks and share data between planner and executor.

### TodoState (`session/TodoState.kt`)

Thread-safe todo list for tracking subgoals:

```kotlin
class TodoState {
    fun update(todos: List<Todo>)  // Replace entire list
    fun get(): List<Todo>          // Get current list
    fun clear()                    // Clear all todos
}
```

**Constraints:**
- Only ONE todo can be `IN_PROGRESS` at a time
- Full list replacement (no incremental updates)

### ScratchpadState (`session/ScratchpadState.kt`)

Thread-safe key-value store for intermediate data:

```kotlin
class ScratchpadState {
    fun write(key: String, value: String)
    fun read(key: String): String?
    fun delete(key: String): Boolean
    fun list(): List<String>
    fun clear()
    
    companion object {
        const val MAX_ENTRIES = 20
        const val MAX_KEY_LENGTH = 100
        const val MAX_VALUE_LENGTH = 2000
    }
}
```

**Use cases:**
- Store extracted info from one screen to use in another
- Remember values across navigation
- Pass structured data from planner to executor

---

## Context Hygiene (History Compression)

To optimize token usage and improve LLM performance, the agent uses text-only history with latest screen injection.

### Key Design Decisions

| Aspect | Approach |
|--------|----------|
| **History** | Text-only (no screenshots, no a11y trees) |
| **Screen State** | Latest screen injected per turn (not stored in history) |
| **Observations** | `ScreenSummary` text instead of raw JSON |

### ScreenSummary (`perception/ScreenSummary.kt`)

Generates a concise text summary for history:

```kotlin
object ScreenSummary {
    fun generate(snapshot: ScreenSnapshot): String
    // Example: "Screen: com.google.gmail (MainActivity) - 42 elements"
}
```

### Data Flow

```
Turn N                                  Turn N+1
  │                                       │
  ├─ Perceive: capture screen            ├─ Perceive: capture screen
  │                                       │
  ├─ Think: LLM with                     ├─ Think: LLM with
  │   - History (text-only)              │   - History (text-only)
  │   - Latest screen (full JSON)        │   - Latest screen (full JSON)
  │   - Todos, scratchpad                │   - Todos, scratchpad
  │                                       │
  ├─ Act: execute tool                   ├─ Act: execute tool
  │                                       │
  └─ Observe: add text summary           └─ Observe: add text summary
      to history (NOT raw screen)            to history (NOT raw screen)
```

---

## Session History System

The session history system enables **automatic persistence** of chat sessions, allowing users to browse past conversations and resume them later.

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    Session History Architecture                  │
│                                                                  │
│  ┌──────────────────┐       ┌──────────────────────────────┐   │
│  │   MainActivity   │──────►│   SessionHistoryManager      │   │
│  │  (UI entry)      │       │   (High-level API)           │   │
│  └────────┬─────────┘       └──────────────┬───────────────┘   │
│           │                                │                    │
│           │                                │ coordinates        │
│           ▼                                ▼                    │
│  ┌──────────────────┐       ┌──────────────────────────────┐   │
│  │  ChatViewModel   │◄─────►│  SessionRecordingService     │   │
│  │  (State mgmt)    │       │  (Real-time event bridge)    │   │
│  └────────┬─────────┘       └──────────────┬───────────────┘   │
│           │                                │                    │
│           │ events                         │ debounced writes   │
│           ▼                                ▼                    │
│  ┌──────────────────┐       ┌──────────────────────────────┐   │
│  │  AgentSession    │──────►│     SessionStorage           │   │
│  │  (Events)        │       │     (File I/O)               │   │
│  └──────────────────┘       └──────────────────────────────┘   │
│                                            │                    │
│                                            ▼                    │
│                             ┌──────────────────────────────┐   │
│                             │  /files/sessions/*.json      │   │
│                             │  (Persisted session files)   │   │
│                             └──────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### Core Components

#### 1. SessionHistoryManager (`history/SessionHistoryManager.kt`)

High-level API for session management operations.

**Responsibilities:**
- List all sessions (lightweight `SessionInfo` for UI)
- Load a session for resuming
- Delete sessions
- Create new sessions
- Coordinate between `SessionStorage` and `SessionRecordingService`

**Key Methods:**
```kotlin
class SessionHistoryManager(storage, recordingService, scope) {
    // List all sessions (sorted by last updated, newest first)
    suspend fun listSessions(): List<SessionInfo>
    
    // Load a session for resuming
    suspend fun loadSession(sessionId: String): Result<ResumedSessionData>
    
    // Delete a session
    suspend fun deleteSession(sessionId: String): Result<Unit>
    
    // Start a new session
    fun startNewSession(model: String?, appVersion: String?): String
    
    // Resume an existing session
    fun resumeSession(data: ResumedSessionData)
    
    // Get recording service for event recording
    fun getRecordingService(): SessionRecordingService
}
```

#### 2. SessionRecordingService (`history/SessionRecordingService.kt`)

Real-time bridge between `AgentEvent` stream and persisted `SessionRecord`.

**Responsibilities:**
- Record user messages and agent responses in real-time
- Build agent messages incrementally (text deltas + actions) using `AgentMessageBuffer`
- Debounce writes to avoid excessive I/O (500ms delay)
- Handle session resume and completion

**Key Methods:**
```kotlin
class SessionRecordingService(storage, scope) {
    // Initialize a new session
    fun initializeNewSession(model: String?, appVersion: String?): String
    
    // Resume an existing session
    fun resumeSession(data: ResumedSessionData)
    
    // Record a user message
    fun recordUserMessage(id: String, timestamp: Long, text: String)
    
    // Start recording an agent message
    fun startAgentMessage(id: String, timestamp: Long)
    
    // Append streaming text delta
    fun appendTextDelta(delta: String)
    
    // Record an action (tool execution)
    fun recordAction(actionId: String, toolName: String, description: String, state: String)
    
    // Update action state (executing → success/failed)
    fun updateActionState(actionId: String, state: String, result: String?)
    
    // Mark agent message as complete
    fun completeAgentMessage()
    
    // Mark session as completed
    fun completeSession()
}
```

**Recording Flow:**
```
AgentEvent                     SessionRecordingService              File
    │                                    │                            │
    │ TaskStarted                        │                            │
    │───────────────────────────────────►│ startAgentMessage()        │
    │                                    │                            │
    │ MessageDelta("I'll...")            │                            │
    │───────────────────────────────────►│ appendTextDelta()          │
    │                                    │ (buffer, no save)          │
    │                                    │                            │
    │ ActionExecuted(click)              │                            │
    │───────────────────────────────────►│ recordAction()             │
    │                                    │───────────────────────────►│
    │                                    │ (debounced save)           │
    │                                    │                            │
    │ TaskCompleted                      │                            │
    │───────────────────────────────────►│ completeAgentMessage()     │
    │                                    │ completeSession()          │
    │                                    │───────────────────────────►│
    │                                    │ (immediate save)           │
```

#### 3. SessionStorage (`history/storage/SessionStorage.kt`)

Low-level file I/O operations for session persistence.

**Storage Location:**
```
/data/data/{package}/files/sessions/
```

**File Naming:**
```
session-{timestamp}-{uuid}.json
Example: session-2024-01-21T14-30-45-a1b2c3d4-e5f6-7890-abcd-ef1234567890.json
```

**Key Methods:**
```kotlin
class SessionStorage(context) {
    // Write a session record to disk
    suspend fun writeSession(fileName: String, record: SessionRecord): Result<Unit>
    
    // Read a session record from disk
    suspend fun readSession(fileName: String): Result<SessionRecord>
    
    // List all session files (sorted by modification time, newest first)
    suspend fun listSessionFiles(): List<File>
    
    // Delete a session file
    suspend fun deleteSession(fileName: String): Result<Unit>
    
    // Generate a filename for a new session
    fun generateFileName(sessionId: String): String
}
```

### Data Models

#### SessionRecord (`history/model/SessionRecord.kt`)

Complete session data stored on disk:

```kotlin
@Serializable
data class SessionRecord(
    val sessionId: String,           // UUID
    val startTime: Long,             // Epoch millis
    val lastUpdated: Long,           // Epoch millis
    val messages: List<MessageRecord>,
    val summary: String? = null,     // AI-generated or extracted summary
    val metadata: SessionMetadata = SessionMetadata()
)

@Serializable
data class SessionMetadata(
    val appVersion: String? = null,
    val model: String? = null,       // e.g., "gpt-5.2"
    val turnCount: Int = 0,
    val completedNormally: Boolean = false
)
```

#### MessageRecord (`history/model/MessageRecord.kt`)

Persisted message representation:

```kotlin
@Serializable
sealed interface MessageRecord {
    val id: String
    val timestamp: Long
    
    @Serializable @SerialName("user")
    data class User(
        override val id: String,
        override val timestamp: Long,
        val text: String
    ) : MessageRecord
    
    @Serializable @SerialName("agent")
    data class Agent(
        override val id: String,
        override val timestamp: Long,
        val contentBlocks: List<ContentBlockRecord>,
        val isComplete: Boolean
    ) : MessageRecord
}

@Serializable
sealed interface ContentBlockRecord {
    @Serializable @SerialName("text")
    data class Text(val text: String) : ContentBlockRecord
    
    @Serializable @SerialName("action")
    data class Action(
        val id: String,
        val toolName: String,
        val description: String,
        val state: String,           // "proposed", "executing", "success", "failed", "skipped"
        val resultSummary: String? = null
    ) : ContentBlockRecord
}
```

#### SessionInfo (`history/model/SessionInfo.kt`)

Lightweight summary for session list UI:

```kotlin
data class SessionInfo(
    val id: String,                  // Session ID (UUID)
    val fileName: String,            // File path (relative)
    val startTime: Long,             // When session started
    val lastUpdated: Long,           // When session was last updated
    val messageCount: Int,           // Number of messages
    val displayTitle: String,        // Summary or first user message (truncated)
    val firstUserMessage: String,    // First user message text
    val isActive: Boolean = false    // Whether this is the current session
)
```

### Session Lifecycle

```
┌─────────────────────────────────────────────────────────────────┐
│                     Session History Lifecycle                    │
│                                                                  │
│   ┌─────────────┐                                               │
│   │  No Session │                                               │
│   └──────┬──────┘                                               │
│          │                                                       │
│          │ startNewSession() or resumeSession()                  │
│          ▼                                                       │
│   ┌─────────────┐                                               │
│   │   Active    │◄──────── recordUserMessage()                   │
│   │   Session   │◄──────── appendTextDelta()                     │
│   │             │◄──────── recordAction()                        │
│   └──────┬──────┘          (debounced auto-save)                │
│          │                                                       │
│          │ completeSession()                                     │
│          ▼                                                       │
│   ┌─────────────┐                                               │
│   │  Completed  │ ──► Saved to /files/sessions/*.json           │
│   │   Session   │                                               │
│   └──────┬──────┘                                               │
│          │                                                       │
│          │ listSessions() + user selects                         │
│          ▼                                                       │
│   ┌─────────────┐                                               │
│   │   Resumed   │ ──► loadSession() ──► resumeSession()         │
│   │   Session   │                                               │
│   └─────────────┘                                               │
└─────────────────────────────────────────────────────────────────┘
```

### Usage Example

```kotlin
// In MainActivity - Initialize history manager
val storage = SessionStorage(context)
val sessionHistoryManager = SessionHistoryManager.create(storage, lifecycleScope)

// Start a new session
val sessionId = sessionHistoryManager.startNewSession(
    model = "gpt-5.2",
    appVersion = BuildConfig.VERSION_NAME
)

// Record events as they occur (in ViewModel or event collector)
val recordingService = sessionHistoryManager.getRecordingService()

session.events.collect { event ->
    when (event) {
        is AgentEvent.TaskStarted -> {
            recordingService.recordUserMessage(event.taskId, event.timestamp, event.input)
            recordingService.startAgentMessage(event.taskId, event.timestamp)
        }
        is AgentEvent.MessageDelta -> {
            recordingService.appendTextDelta(event.delta)
        }
        is AgentEvent.ActionExecuted -> {
            recordingService.updateActionState(
                actionId = event.actionId,
                state = if (event.success) "success" else "failed",
                result = event.result
            )
        }
        is AgentEvent.TaskCompleted -> {
            recordingService.completeAgentMessage()
        }
        is AgentEvent.SessionCompleted -> {
            sessionHistoryManager.endSession()
        }
    }
}

// List and resume sessions (in UI)
val sessions = sessionHistoryManager.listSessions()
val selected = sessions.first()
sessionHistoryManager.loadSession(selected.id).onSuccess { data ->
    sessionHistoryManager.resumeSession(data)
    // Restore messages to ChatViewModel
}
```

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
| `mobile_action` | Consolidated UI actions (`click`, `long_press`, `type`, `swipe`, `system_button`, `wait`) | `action` + per-action fields (`element_index`, `resource_id`, `resource_id_index`, `text`, `text_index`, `target_text`, `target_text_index`, `x`, `y`, `x1`, `y1`, `x2`, `y2`, `start`, `end`, `direction`, `distance`, `button`, `duration_ms`, `clear`, `agent_thought`) |
| `app_control` | App discovery and launch (`list_apps`, `open_app`) | `action` + `filter`, `package_name`, `app_name`, `agent_thought` |
| `complete_task` | Signal goal completion | `status`, `answer`, `reason` (optional) |
| `write_todos` | Todo list for tracking progress on multi-step tasks | `todos` (array of `{description, status}`), `agent_thought` (optional) |
| `scratchpad` | Key-value memory for intermediate data | `action` (`write`/`read`/`delete`/`list`), `key`, `value`, `agent_thought` (optional) |
| `delegate_task` | Delegate atomic action to a sub-agent | `agent_name`, `query`, `current_subgoal` (optional), `important_notes` (optional), `agent_thought` (optional) |

**Notes:**
- Scrolling is modeled as `mobile_action` with `action: "swipe"`. Two modes are supported:
  - **Direction mode (recommended):** Use `direction` (`up`/`down`/`left`/`right`) with optional `distance` (`short`/`medium`/`long`). Supports multi-selector targeting to swipe within a specific element.
  - **Coordinate mode:** Use explicit `start: [x, y]` and `end: [x, y]` arrays for precise gestures.
- Swipe actions include scroll boundary detection—a warning is returned if the screen content is unchanged after swipe (indicating the list boundary was reached).
- System buttons are invoked via `mobile_action` with `action: "system_button"` and `button: back|home|enter|recents`.
- `click` and `long_press` support multi-selector targeting with fallback order: bounds → x/y → resource_id → text → element_index.
- `type` supports multi-selector targeting for focusing a field before input (fallback order: bounds → x/y → resource_id → target_text → element_index). If no targeting selector is provided, it types into the focused field. Text-based targeting uses `target_text` (not `text`, which is the input payload).

### Adding New Tools

1. Implement `ToolSpec` in `tool/impl/`
   - For single UI actions, extend `BaseTool`
   - For grouped actions, extend `MultiActionTool` and provide `ActionHandler`s
2. Implement required members:
   - `name`, `description`, `parameterSchema`
   - `validate(params)`, `createInvocation(params)`
3. Register in `SessionServices.registerBuiltInTools()` (or use `SessionServicesBuilder`)

Example implementation:

```kotlin
class PingTool : ToolSpec {
    override val name = "ping"
    override val description = "Return a health-check response"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
        put("required", JSONArray())
        put("additionalProperties", false)
    }

    override fun validate(params: JSONObject) = ValidationResult.Valid

    override fun createInvocation(params: JSONObject) = object : ToolInvocation {
        override val toolName = name
        override val params = params
        override fun getDescription() = "Health check"
        override suspend fun execute(context: ToolExecutionContext) =
            ToolExecutionResult.Success(output = "pong")
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
    model = "gpt-5.2",       // LLM model (cloud only)
    llmBackend = LLMBackendType.OPENAI, // OPENAI or LOCAL
    localLLMConfig = null,   // Set when llmBackend == LOCAL
    enableScreenshotInput = false,      // Attach screenshots when supported
    screenshotMaxDimension = 1024,      // Long edge max
    screenshotJpegQuality = 70,         // 0-100 JPEG quality
    debugMode = false                   // Verbose logging
)
```

---

*For protocol details, see [agent_protocol.md](./agent_protocol.md). For questions, refer to the source code—it is the ultimate source of truth.*

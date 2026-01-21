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
| **Single ReAct Agent** | One agent running Perceive → Think → Act → Observe loop. No multi-agent complexity. |
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

The agent executes a classic ReAct (Reasoning + Acting) loop:

```
┌─────────────────────────────────────────────────────────────────┐
│                         ReAct Loop                               │
│                                                                  │
│   ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌────────┐│
│   │ PERCEIVE │────►│  THINK   │────►│   ACT    │────►│OBSERVE ││
│   │ (Screen) │     │  (LLM)   │     │  (Tool)  │     │(Screen)││
│   └──────────┘     └──────────┘     └──────────┘     └────┬───┘│
│        ▲                                                  │    │
│        └──────────────────────────────────────────────────┘    │
│          (Loop until complete_task or text-only response)        │
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
│   └── LLMClient.kt              # OpenAI Responses API
│
├── history/                      # Conversation history
│   └── HistoryManager.kt         # Token management, truncation
│
├── model/                        # Domain models
│   └── Models.kt                 # ScreenSnapshot, PerceptionElement, etc.
│
├── ui/                           # UI layer
│   ├── screen/
│   │   └── AgentScreen.kt
│   ├── theme/
│   │   ├── Color.kt
│   │   ├── Theme.kt
│   │   └── Type.kt
│   └── overlay/
│       └── OverlayManager.kt
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

Encapsulates a single LLM call using the OpenAI Responses API with native tool calling.

**Responsibilities:**
- Build input items from history + current context
- Generate tool schemas dynamically via `ToolRegistry.generateResponsesApiTools()`
- Process structured tool calls (using `call_id` for linkage) and text outputs
- Detect completion (via `complete_task` tool or text-only response)

**Key Output:**
```kotlin
data class TurnResult(
    val content: String?,           // Text from LLM
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
- Manage session state transitions
- Create and start Agent

**Key Methods:**
- `submit(op: Op)` - Submit an operation
- `events: Flow<AgentEvent>` - Event stream for UI
- `state: StateFlow<SessionState>` - Current session state

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

### 5. ToolRouter (`tool/ToolRouter.kt`)

Executes tool calls with a state machine lifecycle:

```
VALIDATING → POLICY_CHECK → [AWAITING_APPROVAL] → EXECUTING → SUCCESS/ERROR/CANCELLED
```

**Responsibilities:**
- Validate tool exists and parameters are correct
- Check policy for approval requirements
- Wait for user approval if needed (with 60s timeout)
- Execute tool and return result

### 6. Perceptor (`perception/Perceptor.kt`)

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

### 7. AndroidPlatform (`platform/AndroidPlatform.kt`)

Abstraction for Android-specific operations.

**Operations:**
- `captureScreen()` - Get current UI state as ScreenSnapshot
- `performAction(action, snapshot)` - Execute UI actions
- `hasRequiredPermissions()` - Check accessibility permission
- `getCurrentPackageName()` - Get foreground app
- `getDisplayInfo()` - Screen dimensions

---

## Data Flow

### Complete Request Flow

```
User                 UI                AgentSession          Agent             LLM
  │                   │                     │                  │                │
  │ "Open Settings"   │                     │                  │                │
  │──────────────────►│                     │                  │                │
  │                   │ Op.Start(goal)      │                  │                │
  │                   │────────────────────►│                  │                │
  │                   │                     │ create & run()   │                │
  │                   │                     │─────────────────►│                │
  │                   │                     │                  │                │
  │                   │                     │                  │ captureScreen()│
  │                   │                     │                  │◄───────────────│
  │                   │                     │                  │                │
  │                   │                     │                  │ Turn.run()     │
  │                   │                     │                  │───────────────►│
  │                   │                     │                  │                │
  │                   │                     │                  │ TurnResult     │
  │                   │                     │                  │◄───────────────│
  │                   │                     │                  │                │
  │                   │                     │                  │ ToolRouter.execute()
  │                   │                     │                  │ (action + observe)
  │                   │                     │                  │                │
  │                   │ AgentEvent.ActionExecuted              │                │
  │                   │◄────────────────────│◄─────────────────│                │
  │                   │                     │                  │                │
  │                   │   ... loop ...      │                  │                │
  │                   │                     │                  │                │
  │                   │ AgentEvent.SessionCompleted            │                │
  │                   │◄────────────────────│◄─────────────────│                │
  │ "Goal achieved!"  │                     │                  │                │
  │◄──────────────────│                     │                  │                │
```

### Event Flow

```
Agent                    AgentSession              UI
  │                           │                     │
  │ emitStatus("🧠 Thinking") │                     │
  │──────────────────────────►│                     │
  │                           │ AgentEvent.StatusUpdate
  │                           │────────────────────►│
  │                           │                     │ Update UI
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
session.submit(Op.Start(goal = "Open Settings"))
```

### Submitting Operations

```kotlin
// Lifecycle ops
session.submit(Op.Start(goal = "Open Chrome"))
session.submit(Op.Pause)
session.submit(Op.Resume)
session.submit(Op.Shutdown)

// User interaction
session.submit(Op.Approve(actionId, ApprovalDecision.APPROVED))
```

### Observing Events

```kotlin
session.events.collect { event ->
    when (event) {
        is AgentEvent.StatusUpdate -> updateUI(event.status)
        is AgentEvent.SessionCompleted -> handleComplete(event.reason)
        is AgentEvent.ApprovalRequired -> showApprovalDialog(event.details)
        // ...
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

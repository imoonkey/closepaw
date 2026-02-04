# Android Agent Protocol Reference

> This document describes the Op/Event communication protocol between the UI layer and the agent.
> Last updated: 2026-02-04

## Overview

The Android Agent uses a unidirectional data flow pattern with a **Task-based model**:

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                  │
│   ┌────────────┐         Op           ┌──────────────┐          │
│   │            │ ─────────────────►   │              │          │
│   │     UI     │                      │ AgentSession │          │
│   │            │ ◄─────────────────   │              │          │
│   └────────────┘      AgentEvent      └──────────────┘          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

- **Operations (Op)**: User intents sent TO the agent via `session.submit(op)`
- **Events (AgentEvent)**: State changes emitted FROM the agent via `session.events` Flow

### Task Model

The agent follows a **Session > Task > Turn** hierarchy:

- **Session**: Long-lived configuration and state (History, Services)
- **Task**: Work executed in response to a `Op.UserInput`. A Session runs one Task at a time.
- **Turn**: One cycle of `Perceive → Think (LLM) → Act (Tool) → Observe`

---

## Operations (Op)

**Source:** `protocol/Op.kt`

Operations are immutable, thread-safe commands submitted to the session.

### State Transitions

```
                              ┌─────────┐
                              │ Created │
                              └────┬────┘
                                   │ Op.UserInput
                                   ▼
                    ┌─────────────────────────────┐
            ┌──────►│           Running           │◄──────┐
            │       └──────┬───────────────┬──────┘       │
            │              │               │              │
            │     Op.Pause │               │ TaskCompleted│
            │              ▼               │              │
            │       ┌──────────┐           │              │
   Op.Resume│       │  Paused  │           │              │
            │       └──────────┘           │              │
            │                              │              │
            │                              ▼              │
            │    Op.UserInput        ┌──────────┐        │
            │    (start new task)    │   Idle   │        │
            │◄───────────────────────└────┬─────┘        │
            │                             │              │
            │         Op.Shutdown (from any state)       │
            │                       │                    │
            │                       ▼                    │
            │                ┌───────────┐               │
            └────────────────│  Shutdown │───────────────┘
                             └───────────┘
```

### Operation Types

| Operation | Valid States | Effect |
|-----------|--------------|--------|
| `Op.UserInput(text)` | Created, Idle | **Primary entry point.** Starts a new Task |
| `Op.Start(goal)` | Created | **Deprecated.** Maps to `Op.UserInput(goal)` |
| `Op.Pause` | Running | Cooperative pause after current action |
| `Op.Resume` | Paused | Resume execution |
| `Op.Interrupt` | Running | Cooperative stop after current action (Task ends, Session stays in Idle) |
| `Op.Shutdown` | Any | Graceful shutdown |
| `Op.Approve(actionId, decision)` | Running | Respond to approval request |

### Op.UserInput

The primary way to interact with the agent. Starts a new Task.

```kotlin
data class UserInput(val text: String) : Op
```

When a `UserInput` is submitted:
1. A new `taskId` is generated
2. `TaskStarted` event is emitted
3. The agent executes turns until task completion
4. `TaskCompleted` event is emitted
5. Session transitions to `Idle` state (ready for next input)

### Op.Start (Deprecated)

> **Note**: Use `Op.UserInput` instead. `Op.Start` is maintained for backward compatibility.

```kotlin
@Deprecated("Use Op.UserInput instead", ReplaceWith("UserInput(goal)"))
data class Start(val goal: String) : Op
```

### Op.Approve

Responds to an approval request for a pending tool call.

```kotlin
data class Approve(
    val actionId: String,
    val decision: ApprovalDecision
) : Op
```

**ApprovalDecision Values:**

| Decision | Effect |
|----------|--------|
| `APPROVED` | Execute the tool |
| `DENIED` | Skip this tool, continue session |
| `ABORT` | Stop the entire session |

### Notes

- **Interrupt vs Shutdown**: `Op.Interrupt` stops the current Task but keeps the Session alive (in `Idle` state). `Op.Shutdown` terminates the entire session.
- **Multi-Round Interaction**: After a Task completes, the session enters `Idle` state and accepts new `UserInput` for follow-up tasks.

---

## Events (AgentEvent)

**Source:** `protocol/AgentEvent.kt`

Events are immutable notifications emitted by the agent to the UI layer. All events include:

```kotlin
interface AgentEvent {
    val sessionId: SessionId
    val timestamp: Long
}
```

### Event Categories

```
AgentEvent
├── Session Lifecycle
│   ├── SessionStarted
│   ├── SessionCompleted
│   ├── SessionError
│   ├── SessionPaused
│   └── SessionResumed
│
├── Task Events
│   ├── TaskStarted
│   ├── TaskCompleted
│   └── MessageDelta (streaming)
│
├── Planning State Events
│   ├── TodosUpdated
│   └── ScratchpadUpdated
│
├── Sub-Agent Events
│   ├── SubAgentStarted
│   ├── SubAgentActivity
│   └── SubAgentCompleted
│
├── Turn Events
│   ├── TurnStarted
│   ├── TurnCompleted
│   └── TurnPhaseChanged
│
├── Action Events
│   ├── ActionProposed
│   └── ActionExecuted
│
├── Perception Events
│   └── ScreenCaptured
│
├── Approval Events
│   ├── ApprovalRequired
│   └── ApprovalResolved
│
└── Status Events
    └── StatusUpdate
```

### Session Lifecycle Events

#### SessionStarted

Emitted when the session first transitions from `Created` to `Running` (first `Op.UserInput` or deprecated `Op.Start`). Subsequent tasks from `Idle` do **not** re-emit this event.

```kotlin
data class SessionStarted(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val goal: String
) : AgentEvent
```

#### SessionCompleted

Emitted when session ends (success, stopped, or max turns).

```kotlin
data class SessionCompleted(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val result: String?,
    val reason: CompletionReason
) : AgentEvent
```

**CompletionReason Values:**

| Reason | Description |
|--------|-------------|
| `GOAL_ACHIEVED` | Agent completed the goal |
| `USER_STOPPED` | User requested shutdown |
| `MAX_TURNS` | Turn limit reached |
| `TASK_IMPOSSIBLE` | Agent determined task cannot be done |
| `ERROR` | An error occurred |
| `INTERRUPTED` | Session was interrupted |

#### SessionError

Emitted when a non-fatal error occurs.

```kotlin
data class SessionError(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val error: AgentError
) : AgentEvent
```

### Task Events

Task events track the lifecycle of individual Tasks within a Session.

#### TaskStarted

Emitted when a new Task begins (in response to `Op.UserInput`).

```kotlin
data class TaskStarted(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val taskId: String,
    val input: String
) : AgentEvent
```

#### TaskCompleted

Emitted when a Task ends (success, stopped, or agent determined completion).

```kotlin
data class TaskCompleted(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val taskId: String,
    val result: String?
) : AgentEvent
```

#### MessageDelta

Emitted for each streaming text chunk during LLM response. Used by UI to display real-time streaming text.

```kotlin
data class MessageDelta(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val turnId: String,
    val delta: String  // The text chunk to append
) : AgentEvent
```

**Usage in UI:**
```kotlin
// Append streaming deltas to build the complete message
var currentMessage by remember { mutableStateOf("") }

session.events
    .filterIsInstance<AgentEvent.MessageDelta>()
    .collect { event ->
        currentMessage += event.delta  // Append each chunk
    }
```

### Planning State Events

Events for tracking agent planning state (todos, scratchpad).

#### TodosUpdated

Emitted when the agent updates its todo list via `write_todos` tool.

```kotlin
data class TodosUpdated(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val todos: List<Todo>
) : AgentEvent
```

**Todo Structure:**
```kotlin
data class Todo(
    val description: String,
    val status: TodoStatus  // PENDING, IN_PROGRESS, COMPLETED, CANCELLED
)
```

#### ScratchpadUpdated

Emitted when the agent writes or deletes from scratchpad.

```kotlin
data class ScratchpadUpdated(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val key: String,
    val action: String  // "write" or "delete"
) : AgentEvent
```

### Sub-Agent Events

Events for tracking sub-agent delegation and execution.

#### SubAgentStarted

Emitted when the main agent delegates a task to a sub-agent via `delegate_task`.

```kotlin
data class SubAgentStarted(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val agentName: String,  // e.g., "executor"
    val query: String       // Delegated instruction
) : AgentEvent
```

#### SubAgentActivity

Emitted for bridged activity from a running sub-agent (e.g., status updates).

```kotlin
data class SubAgentActivity(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val agentName: String,
    val activity: String
) : AgentEvent
```

#### SubAgentCompleted

Emitted when a sub-agent finishes execution.

```kotlin
data class SubAgentCompleted(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val agentName: String,
    val success: Boolean,
    val message: String
) : AgentEvent
```

### Turn Events

#### TurnStarted

Emitted at the beginning of each ReAct iteration.

```kotlin
data class TurnStarted(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val turnId: String,
    val turnNumber: Int,
    val phase: TurnPhase
) : AgentEvent
```

**TurnPhase Values:**

| Phase | Description |
|-------|-------------|
| `PERCEPTION` | Capturing/analyzing screen |
| `PLANNING` | Deciding what to do (LLM reasoning) |
| `EXECUTION` | Executing an action (tool call) |

#### TurnPhaseChanged

Emitted when the turn phase changes.

```kotlin
data class TurnPhaseChanged(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val turnId: String,
    val phase: TurnPhase
) : AgentEvent
```

### Action Events

#### ActionExecuted

Emitted after an action completes.

```kotlin
data class ActionExecuted(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val actionId: String,
    val toolName: String,
    val success: Boolean,
    val result: String?
) : AgentEvent
```

### Approval Events

#### ApprovalRequired

Emitted when user approval is needed before executing a tool.

```kotlin
data class ApprovalRequired(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val actionId: String,
    val description: String,
    val details: ApprovalDetails
) : AgentEvent
```

**ApprovalDetails:**

```kotlin
data class ApprovalDetails(
    val callId: String,        // Use this ID when submitting Op.Approve
    val toolName: String,
    val args: JSONObject,
    val description: String,
    val riskLevel: RiskLevel   // LOW, MEDIUM, HIGH
)
```

#### ApprovalResolved

Emitted when an approval request is resolved.

```kotlin
data class ApprovalResolved(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val actionId: String,
    val decision: ApprovalDecision
) : AgentEvent
```

### Status Events

#### StatusUpdate

General-purpose status for simple UI display.

```kotlin
data class StatusUpdate(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val status: String,
    val emoji: String? = null
) : AgentEvent
```

**Common Status Messages:**

| Emoji | Status | Meaning |
|-------|--------|---------|
| 🚀 | Starting agent... | Session beginning |
| 👀 | Scanning screen... | Perception phase |
| 🧠 | Thinking... | LLM call in progress |
| 💡 | Executing actions... | Tool execution |
| ✓ | {tool} executed | Tool completed |
| ✅ | Goal achieved! | Success |
| ⏸️ | Paused | Session paused |
| ⚠️ | Error (retrying)... | Recoverable error |
| ❌ | Error: {message} | Fatal error |
| 🛑 | Cancelled | User stopped |

---

## Session State Machine

**Source:** `protocol/SessionState.kt`

### State Definitions

| State | Description |
|-------|-------------|
| `Created` | Session initialized, not started |
| `Running` | Agent actively executing a Task |
| `Paused` | Execution paused, can resume |
| `Idle` | Session active, waiting for user input (between Tasks) |
| `Completed` | Session finished (see `CompletionReason`) |
| `Shutdown` | User requested stop via `Op.Shutdown` |

### Multi-Round Interaction Flow

```
Created ──(UserInput)──► Running ──(TaskCompleted)──► Idle ──(UserInput)──► Running
                            │                          │
                            │                          │
                            ▼                          ▼
                        Shutdown ◄────────────────── Shutdown
```

**Key States:**
- `Idle`: The session remains active after a Task completes, ready for follow-up `UserInput`
- `Running`: A Task is in progress; rejects new `UserInput` until Task completes
- `Shutdown`: Terminal state; user explicitly ended the session

---

## Session Configuration

**Source:** `protocol/Op.kt`

Configuration is set at session creation time via `SessionConfig`:

```kotlin
data class SessionConfig(
    val maxTurns: Int = 50,              // Max iterations before auto-stop
    val actionDelayMs: Long = 2000,      // Delay after actions for UI settle
    val approvalMode: ApprovalMode = ApprovalMode.SMART,
    val model: String = "gpt-5.2",       // LLM model (cloud)
    val llmBackend: LLMBackendType = LLMBackendType.OPENAI,
    val localLLMConfig: LocalLLMConfig? = null,
    val debugMode: Boolean = false,      // Verbose logging
    val enableScreenshotInput: Boolean = false,
    val screenshotMaxDimension: Int = 1024,
    val screenshotJpegQuality: Int = 70
)
```

**LLMBackendType Values:**

| Backend | Description |
|---------|-------------|
| `OPENAI` | Use OpenAI cloud API |
| `LOCAL` | Use on-device LFM backend |

**ApprovalMode Values:**

| Mode | Behavior |
|------|----------|
| `ALWAYS_ASK` | Prompt user before every tool |
| `AUTO_APPROVE` | Never ask, auto-approve all |
| `SMART` | Auto-approve low-risk, ask for high-risk |

---

## Error Handling

**Source:** `protocol/AgentError.kt`

Errors are categorized for appropriate handling:

| Error Type | Description | Recoverable |
|------------|-------------|-------------|
| `LLMError` | LLM API failure (rate limit, timeout) | Yes (with backoff) |
| `LLMParseError` | Response parsing failed | Yes |
| `PlatformError` | Android operation failed | No |
| `PermissionError` | Missing permission | No |
| `ValidationError` | Tool parameters invalid | Yes |
| `UnknownToolError` | Tool doesn't exist | Yes |
| `InvalidStateError` | Invalid operation for state | No |
| `ApprovalDeniedError` | User denied action | Yes |
| `PolicyDeniedError` | Policy forbids action | No |

---

## Implementing Event Handling

### Kotlin Flow Collection

```kotlin
scope.launch {
    session.events.collect { event ->
        when (event) {
            // Task lifecycle
            is AgentEvent.TaskStarted -> {
                Log.i(TAG, "Task started: ${event.taskId}")
                showThinkingIndicator()
            }
            
            is AgentEvent.MessageDelta -> {
                // Append streaming text to current message bubble
                appendToCurrentMessage(event.delta)
            }
            
            is AgentEvent.TaskCompleted -> {
                hideThinkingIndicator()
                enableInputField()  // Ready for next input
            }
            
            // Action events
            is AgentEvent.ActionExecuted -> {
                showActionCard(event.toolName, event.success, event.result)
            }
            
            // Status updates
            is AgentEvent.StatusUpdate -> {
                val display = event.emoji?.let { "$it ${event.status}" } ?: event.status
                updateUI(display)
            }
            
            // Approval flow
            is AgentEvent.ApprovalRequired -> {
                showApprovalDialog(
                    actionId = event.details.callId,
                    description = event.description,
                    onApprove = { 
                        session.submit(Op.Approve(event.details.callId, ApprovalDecision.APPROVED)) 
                    },
                    onDeny = { 
                        session.submit(Op.Approve(event.details.callId, ApprovalDecision.DENIED)) 
                    }
                )
            }
            
            else -> Log.d(TAG, "Event: ${event::class.simpleName}")
        }
    }
}
```

### Event Filtering

```kotlin
// Only streaming text deltas (for chat UI)
session.events
    .filterIsInstance<AgentEvent.MessageDelta>()
    .collect { appendText(it.delta) }

// Only task lifecycle events
session.events
    .filter { it is AgentEvent.TaskStarted || it is AgentEvent.TaskCompleted }
    .collect { handleTaskLifecycle(it) }

// Only terminal events
session.events
    .filter { it is AgentEvent.SessionCompleted || it is AgentEvent.SessionError }
    .collect { handleSessionEnd(it) }
```

---

## Best Practices

### For UI Developers

1. **Handle `MessageDelta` efficiently** - Append text, don't rebuild entire string on each delta
2. **Track Task lifecycle** - Use `TaskStarted`/`TaskCompleted` for input enable/disable
3. **Always handle SessionCompleted** - Clean up resources
4. **Show StatusUpdate immediately** - Provides real-time feedback
5. **Handle ApprovalRequired with timeout** - Don't block forever (ToolRouter uses 60s timeout)
6. **Ignore unknown events** - Forward compatibility
7. **Use taskId/turnId consistently** - Match deltas to correct message bubbles

### For Agent Developers

1. **Emit `MessageDelta` during streaming** - User sees real-time response
2. **Emit StatusUpdate frequently** - User needs feedback
3. **Use appropriate CompletionReason** - Helps UI respond correctly
4. **Include actionId in action events** - Enables tracking
5. **Emit events before state changes** - Order matters
6. **Transition to `Idle` after Task** - Enables multi-round interaction

---

*For architecture details, see [agent_infra.md](./agent_infra.md). For the complete implementation, see the `protocol/` package in the source code.*

# Android Agent Protocol Reference

> This document describes the Op/Event communication protocol between the UI layer and the agent.

## Overview

The Android Agent uses a unidirectional data flow pattern:

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

---

## Operations (Op)

**Source:** `protocol/Op.kt`

Operations are immutable, thread-safe commands submitted to the session.

### State Transitions

```
                              ┌─────────┐
                              │ Created │
                              └────┬────┘
                                   │ Op.Start
                                   ▼
                    ┌─────────────────────────────┐
            ┌──────►│           Running           │◄──────┐
            │       └──────┬───────────────┬──────┘       │
            │              │               │              │
            │     Op.Pause │               │ Complete     │
            │              ▼               │              │
            │       ┌──────────┐           │              │
   Op.Resume│       │  Paused  │           │              │
            │       └──────────┘           │              │
            │                              │              │
            │                              ▼              │
            │                       ┌───────────┐        │
            │                       │ Completed │        │
            │                       └───────────┘        │
            │                                            │
            │           Op.Shutdown (from any state)     │
            │                       │                    │
            │                       ▼                    │
            │                ┌───────────┐               │
            └────────────────│  Shutdown │───────────────┘
                             └───────────┘
```

### Operation Types

| Operation | Valid States | Effect |
|-----------|--------------|--------|
| `Op.Start(goal)` | Created | Start agent execution |
| `Op.Pause` | Running | Cooperative pause after current action |
| `Op.Resume` | Paused | Resume execution |
| `Op.Interrupt` | Running | Cooperative stop after current action |
| `Op.Shutdown` | Any | Graceful shutdown |
| `Op.UserInput(text)` | Running | *(Planned)* Provide additional context |
| `Op.Approve(actionId, decision)` | Running | Respond to approval request |

### Op.Start

Starts the agent with a goal. Session configuration is set at `AgentSession.create()` time.

```kotlin
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

- **Interrupt vs Shutdown**: `Op.Interrupt` is cooperative—the agent completes its current action before stopping. For immediate termination, use `Op.Shutdown`.
- **UserInput**: Currently accepted but not implemented. Planned for conversational mode.

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
├── Turn Events
│   ├── TurnStarted
│   ├── TurnCompleted
│   └── TurnPhaseChanged
│
├── Action Events
│   ├── ActionProposed
│   ├── ActionExecuted
│   └── ActionSkipped
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

Emitted when `Op.Start` is processed.

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
| `REFLECTION` | *(Planned)* Verifying previous action outcome |
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
| `Running` | Agent actively executing |
| `Paused` | Execution paused, can resume |
| `Completed` | Agent finished (see `CompletionReason`) |
| `Shutdown` | User requested stop via `Op.Shutdown` |

Both `Completed` and `Shutdown` are terminal states. The difference:
- `Completed`: Agent finished its work naturally
- `Shutdown`: User explicitly stopped the session

---

## Session Configuration

**Source:** `protocol/Op.kt`

Configuration is set at session creation time via `SessionConfig`:

```kotlin
data class SessionConfig(
    val maxTurns: Int = 50,              // Max iterations before auto-stop
    val actionDelayMs: Long = 2000,      // Delay after actions for UI settle
    val approvalMode: ApprovalMode = ApprovalMode.SMART,
    val model: String = "gpt-4o",        // LLM model
    val debugMode: Boolean = false       // Verbose logging
)
```

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
            is AgentEvent.StatusUpdate -> {
                val display = event.emoji?.let { "$it ${event.status}" } ?: event.status
                updateUI(display)
            }
            
            is AgentEvent.SessionStarted -> {
                Log.i(TAG, "Session started: ${event.sessionId}")
            }
            
            is AgentEvent.SessionCompleted -> {
                when (event.reason) {
                    CompletionReason.GOAL_ACHIEVED -> showSuccess(event.result)
                    CompletionReason.USER_STOPPED -> showStopped()
                    CompletionReason.ERROR -> showError(event.result)
                    else -> showGenericComplete()
                }
            }
            
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
// Only status updates
session.events
    .filterIsInstance<AgentEvent.StatusUpdate>()
    .collect { updateStatusBar(it.status) }

// Only terminal events
session.events
    .filter { it is AgentEvent.SessionCompleted || it is AgentEvent.SessionError }
    .collect { handleSessionEnd(it) }
```

---

## Best Practices

### For UI Developers

1. **Always handle SessionCompleted** - Clean up resources
2. **Show StatusUpdate immediately** - Provides real-time feedback
3. **Handle ApprovalRequired with timeout** - Don't block forever (ToolRouter uses 60s timeout)
4. **Ignore unknown events** - Forward compatibility
5. **Use actionId/callId consistently** - Match approval requests to responses

### For Agent Developers

1. **Emit StatusUpdate frequently** - User needs feedback
2. **Use appropriate CompletionReason** - Helps UI respond correctly
3. **Include actionId in action events** - Enables tracking
4. **Emit events before state changes** - Order matters

---

*For architecture details, see [agent_infra.md](./agent_infra.md). For the complete implementation, see the `protocol/` package in the source code.*

# Agent Protocol Reference

> Op/Event communication protocol, state machine, errors, and configuration.
> Last updated: 2026-02-17 (commit: c57e349)

## Overview

The Android Agent uses unidirectional flow with a task-based model:

```
┌────────────┐         Op           ┌──────────────┐
│            │ ─────────────────►   │              │
│     UI     │                      │ AgentSession │
│            │ ◄─────────────────   │              │
└────────────┘      AgentEvent      └──────────────┘
```

- **Operations (Op)**: user intents sent to the session via `session.submit(op)`
- **Events (AgentEvent)**: state/progress emitted from the session via `session.events`

### Task Model

- **Session**: long-lived runtime and services
- **Task**: work initiated by `Op.UserInput`
- **Turn**: one cycle of `Perceive → Think (LLM) → Act (Tool) → Observe`

---

## Operations (Op)

> See: `protocol/Op.kt`

`sealed interface Op` — commands sent from UI layer to agent session.

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
            │  Op.Takeover │               │ TaskCompleted│
            │              ▼               │              │
   Op.Resume│       ┌──────────┐           │              │
            │       │  Paused  │           │              │
            │       └──────────┘           ▼              │
            │                        ┌──────────┐         │
            │    Op.UserInput        │   Idle   │─────────┘
            │◄───────────────────────└────┬─────┘
                                          │ Op.Shutdown
                                          ▼
                                   ┌───────────┐
                                   │  Shutdown │
                                   └───────────┘
```

### Operation Types

| Operation | Valid States | Effect |
|-----------|--------------|--------|
| `Op.UserInput(text)` | Created, Idle | Start a new task |
| `Op.Takeover` | Running | Pause cooperatively (user takes control) |
| `Op.Resume` | Paused | Resume execution |
| `Op.Interrupt` | Running | Stop current task, session returns to Idle |
| `Op.Shutdown` | Any | Graceful shutdown |
| `Op.Approve(id, decision)` | Running | Resolve pending approval |
| `Op.Supplement(text)` | Running | Inject additional context mid-task |
| `Op.UserResponse(callId, response)` | Running | Respond to an `ask_user` tool request |

### ApprovalDecision

> See: `protocol/ApprovalTypes.kt`

| Decision | Effect |
|----------|--------|
| `APPROVED` | Execute the tool |
| `DENIED` | Skip this tool and continue |
| `ABORT` | Stop the current session/task |

### RiskLevel

| Level | Description |
|-------|-------------|
| `LOW` | Read-only, reversible operations. Typically auto-approved. |
| `MEDIUM` | May require user approval. |
| `HIGH` | Destructive operations. Typically requires explicit approval. |

### ApprovalRequirement

```kotlin
sealed interface ApprovalRequirement {
    object None : ApprovalRequirement
    data class Required(val reason: String, val riskLevel: RiskLevel) : ApprovalRequirement
    data class Forbidden(val reason: String) : ApprovalRequirement
}
```

---

## Events (AgentEvent)

> See: `protocol/AgentEvent.kt`, `protocol/AgentEventDomains.kt`

All events implement `AgentEvent` with `sessionId: SessionId` and `timestamp: Long`. Events are organized into **12 domain marker interfaces** for type-safe filtering.

### Event Domain Hierarchy

```
AgentEvent (sealed interface)
├── SessionLifecycleEvent
│   ├── SessionStarted(goal)
│   ├── SessionCompleted(result?, reason: CompletionReason)
│   ├── SessionError(error: AgentError)
│   ├── SessionTakeover
│   ├── SessionResumed
│   └── SupplementReceived(text)
│
├── TaskLifecycleEvent
│   ├── TaskStarted(taskId, input)
│   └── TaskCompleted(taskId, result?, reason: CompletionReason)
│
├── TurnDomainEvent
│   ├── TurnStarted(turnId, turnNumber, phase: TurnPhase)
│   ├── TurnCompleted(turnId, turnNumber)
│   └── TurnPhaseChanged(turnId, phase: TurnPhase)
│
├── StreamingDomainEvent
│   └── MessageDelta(turnId, delta)
│
├── ActionDomainEvent
│   ├── ActionProposed(actionId, toolName, description)
│   └── ActionExecuted(actionId, toolName, success, result?)
│
├── ApprovalDomainEvent
│   ├── ApprovalRequired(actionId, description, details: ApprovalDetails)
│   └── ApprovalResolved(actionId, decision: ApprovalDecision)
│
├── AskUserDomainEvent
│   └── AskUser(type: AskUserType, message, callId)
│
├── ThoughtDomainEvent
│   └── ThoughtUpdate(thought)
│
├── PlanningStateEvent
│   ├── TodosUpdated(todos: List<Todo>)
│   └── ScratchpadUpdated(key, action)
│
├── SubAgentDomainEvent
│   ├── SubAgentStarted(agentName, query)
│   ├── SubAgentActivity(agentName, activity)
│   └── SubAgentCompleted(agentName, success, message)
│
├── PerceptionDomainEvent
│   └── ScreenCaptured(elementCount, packageName?, activityName?, turnId,
│                      turnNumber, phase: ScreenStatePhase, trace paths...)
│
└── StatusDomainEvent
    └── StatusUpdate(status, emoji?)
```

### Key Events

| Event | When Emitted | Key Fields |
|-------|--------------|------------|
| `SessionStarted` | First Created → Running | `goal` |
| `TaskStarted` | New task begins | `taskId`, `input` |
| `MessageDelta` | Streaming text chunk | `turnId`, `delta` |
| `ThoughtUpdate` | Agent selects tool call with `agent_thought` | `thought` |
| `ActionExecuted` | Tool completes | `actionId`, `toolName`, `success` |
| `AskUser` | Agent needs user help | `type`, `message`, `callId` |
| `SupplementReceived` | User sent mid-task supplement | `text` |
| `SessionTakeover` | User takes over; emitted only after agent actually pauses | — |
| `TaskCompleted` | Task ends | `taskId`, `result`, `reason` |
| `SessionCompleted` | Session terminates | `result`, `reason` |

### AskUserType

| Type | Description |
|------|-------------|
| `QUESTION` | Agent asks a question, user types text answer |
| `ACTION` | Agent requests user to perform a physical action, user taps "Done" |

### TurnPhase

| Phase | Description |
|-------|-------------|
| `PERCEPTION` | Capturing and analyzing the screen |
| `PLANNING` | Deciding what to do (LLM reasoning) |
| `EXECUTION` | Executing an action (tool call) |

### ScreenStatePhase

| Phase | Description |
|-------|-------------|
| `PRE_TURN` | Screen state captured before the turn |
| `POST_ACTION` | Screen state captured after action execution |

### CompletionReason

Both `TaskCompleted` and `SessionCompleted` carry a `reason: CompletionReason`.

| Reason | Description |
|--------|-------------|
| `GOAL_ACHIEVED` | Goal completed successfully |
| `USER_STOPPED` | User requested shutdown |
| `MAX_TURNS` | Turn limit reached |
| `TASK_IMPOSSIBLE` | Agent decided task cannot be completed |
| `ERROR` | Error occurred |
| `INTERRUPTED` | Session interrupted |

### Todo Models

> See: `protocol/TodoModels.kt`

```kotlin
data class Todo(val description: String, val status: TodoStatus)

enum class TodoStatus { PENDING, IN_PROGRESS, COMPLETED, CANCELLED }
```

---

## Session State Machine

> See: `protocol/SessionState.kt`

```kotlin
sealed interface SessionState {
    object Created : SessionState
    object Running : SessionState
    object Idle : SessionState
    object Paused : SessionState
    object Completed : SessionState
    object Shutdown : SessionState
}
```

| State | Description |
|-------|-------------|
| `Created` | Session initialized, not started |
| `Running` | Agent actively executing a task |
| `Idle` | Session active, waiting for user input (no task running) |
| `Paused` | Execution paused cooperatively (user took over) |
| `Completed` | Session finished (terminal) |
| `Shutdown` | User requested stop (terminal) |

---

## Session Configuration

> See: `protocol/SessionConfig.kt`

```kotlin
data class SessionConfig(
    val maxTurns: Int = 50,
    val actionDelayMs: Long = 2000,
    val approvalMode: ApprovalMode = ApprovalMode.SMART,
    val agentMode: AgentMode = AgentMode.PRO,
    val llm: SessionLlmConfig,
    val debugMode: Boolean = false,
    val traceEnabled: Boolean = false,
    val traceRunId: String? = null,
    val perceptionConfig: PerceptionConfig = PerceptionConfig.DEFAULT,
    val mainModel: String = "gpt-5.2",
    val executorModel: String? = null,
    val platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
)
```

| Setting | Description |
|---------|-------------|
| `maxTurns` | Max turns before auto-stop (default: 50) |
| `actionDelayMs` | Delay after actions for UI settle (default: 2000ms) |
| `approvalMode` | `ALWAYS_ASK`, `AUTO_APPROVE`, or `SMART` |
| `agentMode` | `BASIC` (standalone) or `PRO` (planner + executor) |
| `llm` | LLM routing config (`SessionLlmConfig`) |
| `mainModel` | Model name for standalone/planner agents |
| `executorModel` | Model name for executor agents (falls back to `mainModel` if null) |
| `traceEnabled` | Persist full JSONL trace events/artifacts |
| `traceRunId` | Explicit trace folder/run id for correlating artifacts |
| `perceptionConfig` | Perception mode: `AccessibilityOnly`, `ScreenshotOnly`, or `Hybrid` |
| `platformMode` | `ACCESSIBILITY` (default) or `VIRTUAL_DISPLAY` (Shizuku-based) |

### SessionLlmConfig

```kotlin
data class SessionLlmConfig(
    val backendType: LLMBackendType = LLMBackendType.OPENAI,
    val localConfig: LocalLLMConfig? = null
)
```

### PlatformMode

| Mode | Platform | Description |
|------|----------|-------------|
| `ACCESSIBILITY` | `AccessibilityPlatform` | Standard mode using Android Accessibility APIs |
| `VIRTUAL_DISPLAY` | `VirtualDisplayPlatform` | Runs apps on virtual display via Shizuku |

### AgentMode

| Mode | Runtime Behavior |
|------|------------------|
| `BASIC` | One standalone agent with direct UI tools |
| `PRO` | Planner agent with delegated executor via `delegate_task` |

### LLMBackendType

| Backend | Description |
|---------|-------------|
| `OPENAI` | Cloud API (OpenAI, OpenRouter, Novita via model catalog) |
| `LOCAL` | On-device LLM via Leap SDK |

### ApprovalMode

| Mode | Behavior |
|------|----------|
| `ALWAYS_ASK` | Always ask user before executing any tool |
| `AUTO_APPROVE` | Never ask, auto-approve all tools |
| `SMART` | Auto-approve low-risk, ask for high-risk |

---

## Error Handling

> See: `protocol/AgentError.kt`

`sealed class AgentError` with abstract `message: String` and `isRecoverable: Boolean`.

| Error Type | Description | Recoverable |
|------------|-------------|-------------|
| `LLMError` | LLM API failure (statusCode, retryAfterMs) | Depends (429, 503, 504 = yes) |
| `LLMParseError` | Response malformed/unparseable | Yes |
| `PlatformError` | Android platform operation failed | No |
| `PermissionError` | Required permission missing | No |
| `ValidationError` | Tool parameters failed validation | Yes |
| `UnknownToolError` | Requested tool does not exist | Yes |
| `InvalidStateError` | Operation not valid in current state | No |
| `SessionClosedError` | Session already cancelled/shutdown | No |
| `ApprovalDeniedError` | User denied the action | Yes |
| `PolicyDeniedError` | Action forbidden by policy | No |
| `UnexpectedError` | Unclassified runtime error | No |

Factory method: `AgentError.from(e: Throwable): AgentError` creates the appropriate error subtype from any exception.

---

## SessionId

> See: `protocol/SessionId.kt`

```kotlin
@JvmInline value class SessionId(val value: String) {
    companion object { fun generate(): SessionId }
}
```

UUID-based unique identifier for agent sessions.

---

## Utility

### sanitizeThought

> See: `protocol/TextUtils.kt`

`sanitizeThought(raw: String): String` — trims whitespace, truncates to 40 characters with ellipsis. Used by both UI layer (`CapsuleStateHolder`) and agent layer (`AgentTurnRunner`) for compact capsule display.

---

## File Structure

```
protocol/
├── Op.kt                     # UI→Agent commands (sealed interface)
├── AgentEvent.kt             # Base event interface (sessionId, timestamp)
├── AgentEventDomains.kt      # 12 domain marker interfaces
├── AgentError.kt             # Error hierarchy (sealed class)
├── SessionConfig.kt          # Session configuration (SessionLlmConfig, PlatformMode, etc.)
├── SessionState.kt           # Session state machine (sealed interface)
├── SessionId.kt              # Session identifier (@JvmInline value class)
├── CompletionReason.kt       # Why session/task completed (enum)
├── SessionLifecycleEvents.kt # Session started/completed/error/takeover/resumed
├── TaskLifecycleEvents.kt    # Task started/completed
├── TurnEvents.kt             # Turn started/completed/phase changed
├── TurnPhase.kt              # PERCEPTION, PLANNING, EXECUTION
├── StreamingEvents.kt        # MessageDelta (text streaming)
├── ActionEvents.kt           # ActionProposed, ActionExecuted
├── ApprovalEvents.kt         # ApprovalRequired, ApprovalResolved
├── ApprovalTypes.kt          # ApprovalDecision, RiskLevel, ApprovalRequirement
├── AskUserEvents.kt          # AskUser event
├── AskUserType.kt            # QUESTION, ACTION
├── ThoughtEvents.kt          # ThoughtUpdate (for Smart Capsule)
├── PlanningStateEvents.kt    # TodosUpdated, ScratchpadUpdated
├── TodoModels.kt             # Todo, TodoStatus
├── SubAgentEvents.kt         # SubAgentStarted/Activity/Completed
├── PerceptionEvents.kt       # ScreenCaptured (with trace artifact paths)
├── ScreenStatePhase.kt       # PRE_TURN, POST_ACTION
├── StatusEvents.kt           # StatusUpdate (generic status line)
└── TextUtils.kt              # sanitizeThought utility
```

---

## Related Docs

- [Session](../infra/session.md) - AgentSession lifecycle and event emission
- [Loop](../agent/loop.md) - Turn execution emitting events
- [Platform](../infra/platform.md) - Platform mode configuration
- [Settings](../app/settings.md) - SessionConfig UI

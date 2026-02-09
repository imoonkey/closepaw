# Agent Protocol Reference

> Op/Event communication protocol, state machine, errors, and configuration.
> Last updated: 2026-02-09 (commit: 5fbeec1)

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

→ See: `protocol/Op.kt`

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
| `Op.Pause` | Running | Pause cooperatively |
| `Op.Resume` | Paused | Resume execution |
| `Op.Interrupt` | Running | Stop current task, session returns to Idle |
| `Op.Shutdown` | Any | Graceful shutdown |
| `Op.Approve(id, decision)` | Running | Resolve pending approval |

### ApprovalDecision

| Decision | Effect |
|----------|--------|
| `APPROVED` | Execute the tool |
| `DENIED` | Skip this tool and continue |
| `ABORT` | Stop the current session/task |

---

## Events (AgentEvent)

→ See: `protocol/AgentEvent.kt`

All events include `sessionId` and `timestamp`.

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
│   └── MessageDelta
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
├── Approval Events
│   ├── ApprovalRequired
│   └── ApprovalResolved
│
└── Status Events
    └── StatusUpdate
```

### Key Events

| Event | When Emitted | Key Fields |
|-------|--------------|------------|
| `SessionStarted` | First Created → Running | `goal` |
| `TaskStarted` | New task begins | `taskId`, `input` |
| `MessageDelta` | Streaming text chunk | `turnId`, `delta` |
| `ActionExecuted` | Tool completes | `actionId`, `toolName`, `success` |
| `TaskCompleted` | Task ends | `taskId`, `result` |
| `SessionCompleted` | Session terminates | `result`, `reason` |

### Completion Reasons

| Reason | Description |
|--------|-------------|
| `GOAL_ACHIEVED` | Goal completed successfully |
| `USER_STOPPED` | User requested shutdown |
| `MAX_TURNS` | Turn limit reached |
| `TASK_IMPOSSIBLE` | Runtime deemed task not completable |
| `ERROR` | Error occurred |
| `INTERRUPTED` | Session interrupted |

### Planning State Events

| Event | Key Fields |
|-------|------------|
| `TodosUpdated` | `todos: List<Todo>` |
| `ScratchpadUpdated` | `key`, `action` (`write` or `delete`) |

### Sub-Agent Events

| Event | Key Fields |
|-------|------------|
| `SubAgentStarted` | `agentName`, `query` |
| `SubAgentActivity` | `agentName`, `activity` |
| `SubAgentCompleted` | `agentName`, `success`, `message` |

### Status Messages

| Emoji | Status | Meaning |
|-------|--------|---------|
| 🚀 | Starting agent... | Session beginning |
| 👀 | Scanning screen... | Perception phase |
| 🧠 | Thinking... | LLM call in progress |
| 💡 | Executing actions... | Tool execution |
| ✅ | Goal achieved! | Success |
| ⏸️ | Paused | Session paused |
| ❌ | Error: {message} | Fatal error |

---

## Session State Machine

→ See: `protocol/SessionState.kt`

| State | Description |
|-------|-------------|
| `Created` | Session initialized, not started |
| `Running` | Agent actively executing a task |
| `Paused` | Execution paused, can resume |
| `Idle` | Session active, waiting for user input |
| `Completed` | Session finished |
| `Shutdown` | User requested stop |

---

## Session Configuration

→ See: `protocol/Op.kt`

```kotlin
data class SessionConfig(
    val maxTurns: Int = 50,
    val actionDelayMs: Long = 2000,
    val approvalMode: ApprovalMode = ApprovalMode.SMART,
    val model: String = "gpt-5.2",
    val llmBackend: LLMBackendType = LLMBackendType.OPENAI,
    val agentMode: AgentMode = AgentMode.PRO,
    val localLLMConfig: LocalLLMConfig? = null,
    val debugMode: Boolean = false,
    val traceEnabled: Boolean = false,
    val traceRunId: String? = null,
    val perceptionConfig: PerceptionConfig = PerceptionConfig.DEFAULT
)
```

| Setting | Description |
|---------|-------------|
| `maxTurns` | Max turns before auto-stop |
| `actionDelayMs` | Delay after actions for UI settle |
| `approvalMode` | `ALWAYS_ASK`, `AUTO_APPROVE`, or `SMART` |
| `model` | Cloud model name |
| `llmBackend` | `OPENAI` or `LOCAL` |
| `agentMode` | `BASIC` (standalone) or `PRO` (planner + executor) |
| `localLLMConfig` | Local model backend config |
| `traceEnabled` | Persist full trace events/artifacts |
| `traceRunId` | Explicit trace folder/run id |
| `perceptionConfig` | Perception mode: `AccessibilityOnly`, `ScreenshotOnly`, or `Hybrid` — see [platform.md](../infra/platform.md) |

### AgentMode

| Mode | Runtime Behavior |
|------|------------------|
| `BASIC` | One standalone main agent with direct UI tools |
| `PRO` | Planner main agent with delegated executor via `delegate_task` |

---

## Error Handling

→ See: `protocol/AgentError.kt`

| Error Type | Description | Recoverable |
|------------|-------------|-------------|
| `LLMError` | LLM API failure | Depends on status code |
| `LLMParseError` | Response parsing failed | Yes |
| `PlatformError` | Android operation failed | No |
| `PermissionError` | Missing permission | No |
| `ValidationError` | Tool parameters invalid | Yes |
| `UnknownToolError` | Tool doesn't exist | Yes |
| `ApprovalDeniedError` | User denied action | Yes |
| `PolicyDeniedError` | Policy forbids action | No |
| `UnexpectedError` | Unclassified runtime error | No |

---

## Implementation Examples

### Observing Events

```kotlin
session.events.collect { event ->
    when (event) {
        is AgentEvent.TaskStarted -> showThinkingUI()
        is AgentEvent.MessageDelta -> appendText(event.delta)
        is AgentEvent.TaskCompleted -> enableInputField()
        is AgentEvent.ApprovalRequired -> showApprovalDialog(event.details)
        // ...
    }
}
```

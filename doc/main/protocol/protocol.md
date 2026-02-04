# Agent Protocol Reference

> Op/Event communication protocol, state machine, errors, and configuration.
> Last updated: 2026-02-05

## Overview

The Android Agent uses a unidirectional data flow pattern with a **Task-based model**:

```
┌────────────┐         Op           ┌──────────────┐
│            │ ─────────────────►   │              │
│     UI     │                      │ AgentSession │
│            │ ◄─────────────────   │              │
└────────────┘      AgentEvent      └──────────────┘
```

- **Operations (Op)**: User intents sent TO the agent via `session.submit(op)`
- **Events (AgentEvent)**: State changes emitted FROM the agent via `session.events` Flow

### Task Model

- **Session**: Long-lived configuration and state (History, Services)
- **Task**: Work executed in response to `Op.UserInput`. One at a time.
- **Turn**: One cycle of `Perceive → Think (LLM) → Act (Tool) → Observe`

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
| `Op.UserInput(text)` | Created, Idle | Start a new Task |
| `Op.Pause` | Running | Pause after current action |
| `Op.Resume` | Paused | Resume execution |
| `Op.Interrupt` | Running | Stop task, session → Idle |
| `Op.Shutdown` | Any | Graceful shutdown |
| `Op.Approve(id, decision)` | Running | Respond to approval request |

### ApprovalDecision

| Decision | Effect |
|----------|--------|
| `APPROVED` | Execute the tool |
| `DENIED` | Skip this tool, continue |
| `ABORT` | Stop the entire session |

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
| `GOAL_ACHIEVED` | Agent completed the goal |
| `USER_STOPPED` | User requested shutdown |
| `MAX_TURNS` | Turn limit reached |
| `TASK_IMPOSSIBLE` | Agent determined task cannot be done |
| `ERROR` | An error occurred |
| `INTERRUPTED` | Session was interrupted |

### Planning State Events

| Event | Key Fields |
|-------|------------|
| `TodosUpdated` | `todos: List<Todo>` |
| `ScratchpadUpdated` | `key`, `action` ("write" or "delete") |

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
| `Running` | Agent actively executing a Task |
| `Paused` | Execution paused, can resume |
| `Idle` | Session active, waiting for input |
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
    val cognitionProfileId: String? = null,
    val localLLMConfig: LocalLLMConfig? = null,
    val debugMode: Boolean = false,
    val traceEnabled: Boolean = false,
    val traceRunId: String? = null,
    val enableScreenshotInput: Boolean = false,
    val screenshotMaxDimension: Int = 1024,
    val screenshotJpegQuality: Int = 70
)
```

| Setting | Description |
|---------|-------------|
| `maxTurns` | Max iterations before auto-stop |
| `actionDelayMs` | Delay after actions for UI settle |
| `approvalMode` | ALWAYS_ASK, AUTO_APPROVE, or SMART |
| `llmBackend` | OPENAI or LOCAL |
| `cognitionProfileId` | Select cognition profile (`baseline`, `concise`, etc.) |
| `traceEnabled` | Persist full trace events/artifacts |
| `traceRunId` | Explicit trace folder/run id |
| `enableScreenshotInput` | Attach screenshots to perception |

---

## Error Handling

→ See: `protocol/AgentError.kt`

| Error Type | Description | Recoverable |
|------------|-------------|-------------|
| `LLMError` | LLM API failure | Yes (with backoff) |
| `LLMParseError` | Response parsing failed | Yes |
| `PlatformError` | Android operation failed | No |
| `PermissionError` | Missing permission | No |
| `ValidationError` | Tool parameters invalid | Yes |
| `UnknownToolError` | Tool doesn't exist | Yes |
| `ApprovalDeniedError` | User denied action | Yes |
| `PolicyDeniedError` | Policy forbids action | No |

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

### Filtering Events

```kotlin
// Only streaming text
session.events
    .filterIsInstance<AgentEvent.MessageDelta>()
    .collect { appendText(it.delta) }

// Only terminal events
session.events
    .filter { it is SessionCompleted || it is SessionError }
    .collect { handleSessionEnd(it) }
```

---

## Best Practices

### For UI Developers

1. Handle `MessageDelta` efficiently — append, don't rebuild
2. Use `TaskStarted`/`TaskCompleted` for input enable/disable
3. Always handle `SessionCompleted` — clean up resources
4. Handle `ApprovalRequired` with timeout (60s)
5. Ignore unknown events for forward compatibility

### For Agent Developers

1. Emit `MessageDelta` during streaming
2. Emit `StatusUpdate` frequently for user feedback
3. Use appropriate `CompletionReason`
4. Transition to `Idle` after Task for multi-round

---

## Related Docs

- [Session](../infra/session.md) - AgentSession implementation
- [Agent Loop](../agent/loop.md) - Event emission during loop
- [Multi-Agent](../agent/multiagent.md) - Sub-agent events

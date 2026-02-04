# Session Infrastructure

> AgentSession, SessionServices, and session lifecycle.
> Last updated: 2026-02-04

## AgentSession

→ See: `session/AgentSession.kt`

Thin lifecycle manager. Does NOT contain agent logic.

**Responsibilities:**
- Process Operations (Op) from UI
- Emit Events (AgentEvent) to UI
- Manage session state transitions (including `Idle` for multi-round)
- Manage Task lifecycle via `handleUserInput()`
- Delegate agent lifecycle to `SessionAgentRunner`

### Key Methods

```kotlin
class AgentSession {
    fun submit(op: Op)                    // Submit an operation
    val events: Flow<AgentEvent>          // Event stream for UI
    val state: StateFlow<SessionState>    // Current session state
}
```

### State Transitions

```
Created ──(UserInput)──► Running ──(TaskCompleted)──► Idle ──(UserInput)──► Running
                                                        │
                                                        ▼
                                                    Shutdown
```

---

## SessionServices

→ See: `session/SessionServices.kt`

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

### Creation

```kotlin
val services = SessionServices.create(config, platform, apiKey)
```

---

## SessionAgentRunner

→ See: `session/SessionAgentRunner.kt`

Bridges AgentSession and AgentRuntime:
- Creates AgentRuntime for each task
- Handles agent lifecycle (start, pause, resume, stop)
- Collects agent events and forwards to session

---

## AgentSessionState

→ See: `session/AgentSessionState.kt`

Shared state container accessible to agent and tools:
- `TodoState` - Current todo list
- `ScratchpadState` - Key-value memory
- Cleared on new task start (except for resumed sessions)

---

## Lifecycle Events

| Event | Description |
|-------|-------------|
| `SessionStarted` | First transition from Created → Running |
| `TaskStarted` | New task begins |
| `TaskCompleted` | Task ends, session → Idle |
| `SessionCompleted` | Session terminates |

→ See: [Protocol](../protocol/protocol.md)

---

## Quick Reference

### Starting the Agent

```kotlin
// In AgentService
val session = AgentSession.create(config, accessibilityService, scope, apiKey)

// Primary entry point
session.submit(Op.UserInput("Open Settings"))
```

### Submitting Operations

```kotlin
session.submit(Op.UserInput("Check my email"))  // Start task
session.submit(Op.Pause)                        // Pause
session.submit(Op.Resume)                       // Resume
session.submit(Op.Interrupt)                    // Stop task, session stays Idle
session.submit(Op.Shutdown)                     // Terminate session
session.submit(Op.Approve(actionId, decision))  // Respond to approval
```

### Observing Events

```kotlin
session.events.collect { event ->
    when (event) {
        is AgentEvent.TaskStarted -> showThinkingUI()
        is AgentEvent.MessageDelta -> appendText(event.delta)
        is AgentEvent.TaskCompleted -> enableInputField()
        // ...
    }
}
```

---

## Related Docs

- [Agent Overview](../agent/overview.md) - Architecture context
- [Protocol](../protocol/protocol.md) - Op/Event details
- [Tools](tools.md) - Tool execution
- [LLM](llm.md) - LLM clients in SessionServices

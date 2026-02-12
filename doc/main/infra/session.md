# Session Infrastructure

> AgentSession, SessionServices, and session lifecycle.
> Last updated: 2026-02-10 (commit: 04cecbd)

## AgentSession

→ See: `session/AgentSession.kt`

Thin lifecycle manager. It does not implement planning/action logic directly.

**Responsibilities:**
- Process `Op` from UI
- Emit `AgentEvent` to UI
- Manage session state transitions (`Created`/`Running`/`Paused`/`Idle`/`Shutdown`)
- Manage per-task lifecycle via `handleUserInput()`
- Delegate runtime start/stop to `SessionAgentRunner`

### Key Methods

```kotlin
class AgentSession {
    suspend fun submit(op: Op)             // Submit an operation
    val events: SharedFlow<AgentEvent>     // Event stream for UI
    val state: StateFlow<SessionState>     // Current session state
}
```

### Platform Lifecycle

On first transition from `Created` → `Running` (first `UserInput`), `AgentSession` calls `platform.start()` to initialize platform resources (e.g., virtual display creation). This is a one-time call per session.

Platform selection is delegated to `PlatformFactory.create()` based on `SessionConfig.platformMode`.

→ See: [Platform](platform.md) for `PlatformFactory` and `VirtualDisplayPlatform` details.

### State Transitions

```
Created ──(UserInput + platform.start())──► Running ──(TaskCompleted)──► Idle ──(UserInput)──► Running
                                                                          │
                                                                          ▼
                                                                      Shutdown
```

---

## SessionServices

→ See: `session/SessionServices.kt`

Dependency-injection container for all session-scoped services.

| Service | Purpose |
|---------|---------|
| `toolRegistry` | Tool discovery and schema generation |
| `toolRouter` | Tool execution + approval lifecycle |
| `historyManager` | Conversation history + compression |
| `sessionState` | Shared planning state (todos + scratchpad) |
| `policyEngine` | Tool approval decisions |
| `platform` | Android operations |
| `config` | Session configuration |
| `llmClient` | LLM client (OpenAI or local LFM) |
| `modelCatalog` | Database of available models and providers |
| `llmClientFactory` | Factory for creating LLM clients |
| `traceRecorder` | Trace persistence sink |

### Cleanup

`SessionServices.cleanup()` calls `platform.stop()` to release platform resources (virtual display teardown, `ImageReader` release). Both calls are wrapped in try-catch to ensure cleanup completes even on errors.

### Creation

```kotlin
// SessionServices.create() now loads ModelCatalog from assets/llm_models.json
val services = SessionServices.create(config, platform, apiKeys, context, scope, traceRecorder)
```

The factory method:
1. Loads `ModelCatalog` from assets (defines available models and API providers)
2. Creates `LLMClientFactory`
3. Instantiates appropriate `LLMClient` (OpenAI or Local) based on config
4. Wires up all other services

Built-in tool registration includes:
- `mobile_action`, `open_app`, `system_button`, `wait`
- `write_todos`, `scratchpad`, `complete_task`

`delegate_task` is not part of static built-in registration. It is attached lazily by `SessionAgentRunner` when required.

---

## SessionAgentRunner

→ See: `session/SessionAgentRunner.kt`

Bridges `AgentSession` and runtime `Agent`:
- Chooses main agent definition via `AgentDefRegistry.mainFor(config.agentMode)`
- Builds `AgentExecutionConfig` from selected definition (prompt + allowed tools + execution role)
- Registers `delegate_task` only when selected definition requires delegation
- Handles lifecycle (`start`, `pause`, `resume`, `stop`, `shutdown`)
- Wires `AgentRegistry` + `IsolatedSubAgentRunner` when delegation is enabled

### Execution Modes

| Mode | Main Agent Definition | Delegation |
|------|------------------------|------------|
| `BASIC` | `StandaloneAgentDef` | Off |
| `PRO` | `PlannerAgentDef` | On (`delegate_task` registered) |

---

## AgentSessionState

→ See: `session/AgentSessionState.kt`

Shared state container accessible to agent and tools:
- `TodoState` - current todo list
- `ScratchpadState` - key-value memory

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
val session = AgentSession.create(config, accessibilityService, scope, apiKeys)

// Primary entry point
session.submit(Op.UserInput("Open Settings"))
```

### Submitting Operations

```kotlin
session.submit(Op.UserInput("Check my email"))  // Start task
session.submit(Op.Pause)                         // Pause
session.submit(Op.Resume)                        // Resume
session.submit(Op.Interrupt)                     // Stop task, session stays Idle
session.submit(Op.Shutdown)                      // Terminate session
session.submit(Op.Approve(actionId, decision))   // Respond to approval
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

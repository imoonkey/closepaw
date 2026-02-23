# Session Infrastructure

> AgentSession, SessionCoordinator, SessionServices, and session lifecycle.
> Last updated: 2026-02-23 (commit: 1dd2020)

## AgentSession

→ See: `session/AgentSession.kt`

Thin lifecycle manager. It does not implement planning/action logic directly.

**Responsibilities:**
- Process `Op` from UI
- Emit `AgentEvent` to UI (SharedFlow with replay=8, buffer=64)
- Manage session state transitions (`Created`/`Running`/`Paused`/`Idle`/`Shutdown`)
- Manage per-task lifecycle via `handleUserInput()`
- Coordinate checkpoint persistence via `SessionCheckpointCoordinator`
- Manage Hot Idle (platform release, idle timeout, follow-up re-acquisition)
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

Platform resources are acquired per-task, not per-session:

- `Created → Running` (first task): `platform.start()`
- `Running → Idle` (task complete): `platform.stop()` — releases VirtualDisplay, ImageReader
- `Idle → Running` (follow-up): `platform.start()` — re-acquires resources
- `Any → Shutdown`: `services.cleanup()` → `platform.stop()`

If `platform.start()` fails on follow-up, session re-arms idle timeout and stays in Idle.

→ See: [Platform](platform.md) for `PlatformFactory` and `VirtualDisplayPlatform` details.

### State Transitions

```
Created ──(UserInput)──► Running ──(Takeover)──► Paused
                           │  ▲                    │
                           │  └────(Resume)────────┘
                           │
                           └──(TaskCompleted)──► Idle ──(UserInput)──► Running
                                                  │
                                 Any ──(Shutdown)──► Shutdown ◄──(IdleTimeout)──┘
```

> See: [Session State Machine](../ui/session/state_machine.md) for formal transition table and resource ownership.

### Hot Idle

After task completion, the session enters `Idle` instead of shutting down. Expensive resources (platform, agent runner) are released; lightweight state (history, todos, scratchpad, LLM client) stays in memory for instant follow-up. Auto-shutdown after 5 minutes of inactivity (`IDLE_TIMEOUT_MS = 300_000`).

→ See: [Session User Flows](../ui/session/user_flows.md) for follow-up task flow.

### Checkpoint Coordination

→ See: `session/SessionCheckpointCoordinator.kt`

Persists session state for process-death recovery. Writes `context-*.json` files alongside `session-*.json`. Watches `HistoryManager`, `TodoState`, and `ScratchpadState` mutation listeners to schedule incremental checkpoints. Checkpoint state is derived from runtime `SessionState`:

| SessionState | CheckpointState | Written when |
|--------------|-----------------|-------------|
| Running/Paused | `RUNNING_DIRTY` | Mutation listener (debounced 500ms) |
| Idle | `IDLE_READY` | Mutation listener, or force-flushed on TaskCompleted |
| Shutdown | `CLOSED` | Force-flushed on session shutdown |

`AgentSession.reload(snapshot)` hydrates a new session from a `SessionRuntimeSnapshot`, restoring history/todos/scratchpad. Returns session in `Created` state.

---

## SessionCoordinator

→ See: `session/SessionCoordinator.kt`

Coordinates session lifecycle and input queuing between `MainActivity` and `AgentSession`. Replaces the previous timer-loop drain pattern with an event-driven approach.

**Responsibilities:**
- Own `currentSession` reference and `selectedSessionForReload`
- Serialize session creation via internal `Mutex` (tryLock fast-fail for concurrent requests)
- Queue user inputs during busy state (Running/Paused)
- Drain queued inputs automatically when session transitions to Idle/Created (StateFlow observation)
- Capture dead session identity on Shutdown for cold-idle auto-reload

### Key Methods

```kotlin
class SessionCoordinator(scope: CoroutineScope) {
    suspend fun submit(text: String): SubmitResult     // Send or queue input
    suspend fun createAndSubmit(text: String, create)   // Create session under lock
    fun enqueue(text: String)                           // Direct queue (lock unavailable fallback)
    fun attachSession(session: AgentSession)            // Rebind from service
    fun detachSession()                                 // Switch to history viewing
    suspend fun clearSession()                          // Shutdown + teardown
    fun consumeDeadSessionFileName(): String?           // One-shot dead session ref for auto-reload
}
```

### Cold-Idle Auto-Reload

When `submit()` detects `SessionState.Shutdown`, it captures the dying session's recording `fileName` before teardown. On the next user input, `MainActivity.ensureSessionAndSend` uses this dead ref + the still-set `activeSessionId` to construct a `SessionInfo` and route through the existing checkpoint reload path. If auto-reload fails, falls back to a fresh session silently.

→ See: [Session User Flows](../ui/session/user_flows.md) for the full cold-idle recovery flow.

---

## SessionServices

→ See: `session/SessionServices.kt`

Dependency-injection container for all session-scoped services. Created via factory method with three bootstrappers.

| Service | Purpose |
|---------|---------|
| `toolRegistry` | Tool discovery and schema generation |
| `toolRouter` | Tool execution + approval lifecycle |
| `historyManager` | Conversation history + compression (thread-safe via `@Synchronized`) |
| `sessionState` | Shared planning state (todos + scratchpad) |
| `policyEngine` | Tool approval decisions |
| `platform` | Android operations |
| `config` | Session configuration |
| `llmClient` | LLM client (OpenAI or local LFM) |
| `modelCatalog` | Database of available models and providers |
| `llmClientFactory` | Factory for creating LLM clients (cached by provider) |
| `traceRecorder` | Trace persistence sink |
| `recordingService` | Session history recording |
| `userResponseChannel` | Suspension bridge for `ask_user` tool (CompletableDeferred) |

### Bootstrappers

Creation is split into three bootstrappers:

| Bootstrapper | Creates |
|-------------|---------|
| `SessionLlmBootstrapper` | `ModelCatalog` (from `assets/llm_models.json`), `LLMClientFactory`, `LLMClient` |
| `SessionToolingBootstrapper` | `PolicyEngine`, `AgentSessionState`, `ToolRegistry`, `ToolRouter` |
| `SessionHistoryBootstrapper` | `HistoryManager` (maxTokenBudget=18,000, AGGRESSIVE truncation), `SessionRecordingService` |

### Cleanup

`SessionServices.cleanup()` calls `platform.stop()` to release platform resources (virtual display teardown, `ImageReader` release). Wrapped in try-catch for resilience.

### Creation

```kotlin
val services = SessionServices.create(config, platform, apiKeys, context, scope, traceRecorder)
```

Built-in tool registration includes:
- `mobile_action`, `open_app`, `system_button`, `wait`
- `write_todos`, `scratchpad`, `complete_task`

`delegate_task` and `ask_user` are not part of static built-in registration. They are attached lazily by `SessionAgentRunner.start()` when required.

---

## SessionAgentRunner

→ See: `session/SessionAgentRunner.kt`

Bridges `AgentSession` and runtime `Agent`:
- Chooses main agent definition via `AgentDefRegistry.mainFor(config.agentMode)`
- Builds `AgentExecutionConfig` from selected definition (prompt + allowed tools + execution role)
- Registers `delegate_task` only when selected definition requires delegation
- Always registers `ask_user` with `UserResponseChannel` and event emitter
- Handles lifecycle (`start`, `pause`, `resume`, `stop`, `shutdown`)
- Wires `AgentRegistry` + `IsolatedSubAgentRunner` when delegation is enabled

### Execution Modes

| Mode | Main Agent Definition | Delegation |
|------|------------------------|------------|
| `BASIC` | `StandaloneAgentDef` | Off |
| `PRO` | `PlannerAgentDef` | On (`delegate_task` registered) |

### Takeover Timing

When the user requests takeover (`Op.Takeover`), `AgentSession.handleTakeover()` calls `agentRunner.pause()`, which returns `Deferred<Unit>`. The session awaits this deferred before emitting `SessionTakeover`. Thus:

1. User taps takeover → capsule shows TakeoverPending immediately
2. Session calls `agentRunner.pause()`, receives deferred
3. Agent finishes current turn, then actually pauses (loop top check)
4. Deferred completes → session emits `SessionTakeover`
5. Capsule transitions to Takeover

The capsule's TakeoverPending state reflects reality: handover is not instant while the agent finishes its current action.

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
| `SessionStarted` | First transition from Created → Running (goal) |
| `TaskStarted` | New task begins (taskId, input) |
| `TaskCompleted` | Task ends (taskId, result, reason) |
| `SessionCompleted` | Session terminates (result, reason) |

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
session.submit(Op.UserInput("Check my email"))            // Start task
session.submit(Op.Takeover)                               // User takes over
session.submit(Op.Resume)                                 // Resume after takeover
session.submit(Op.Supplement("also check spam folder"))   // Inject mid-task context
session.submit(Op.UserResponse(callId, "yes"))            // Respond to ask_user
session.submit(Op.Interrupt)                              // Stop task, session stays Idle
session.submit(Op.Shutdown)                               // Terminate session
session.submit(Op.Approve(actionId, decision))            // Respond to approval
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

## UserResponseChannel

→ See: `session/UserResponseChannel.kt`

Suspension bridge between the `ask_user` tool and the UI. Uses `AtomicReference<PendingRequest?>` for thread safety. Only one pending request is allowed at a time.

| Method | Called By | Purpose |
|--------|-----------|---------|
| `awaitResponse(callId)` | `AskUserTool.execute()` | Suspend until user responds |
| `deliver(callId, response)` | `AgentSession.handleUserResponse()` | Complete the deferred with user's answer |
| `cancel()` | `AgentSession.handleInterrupt/Shutdown()` | Cancel pending request |

**Exit paths:**
- **Normal**: User responds → `deliver()` → deferred completes → tool returns success
- **Timeout**: `withTimeoutOrNull(5min)` → returns `null` → `finally` clears state → tool returns "timed out"
- **Cancellation**: `cancel()` → `CancellationException` → tool returns `Cancelled`

---

## Related Docs

- [Agent Overview](../agent/overview.md) - Architecture context
- [Session State Machine](../ui/session/state_machine.md) - Formal transition rules and resource ownership
- [Session User Flows](../ui/session/user_flows.md) - Session/task user interaction flows
- [Protocol](../protocol/protocol.md) - Op/Event details
- [Tools](tools.md) - Tool execution
- [LLM](llm.md) - LLM clients in SessionServices

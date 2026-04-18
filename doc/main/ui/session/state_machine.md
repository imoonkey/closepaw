# Session Lifecycle State Machine

> Formal state model, transition rules, resource ownership, and checkpoint coordination.
> Last updated: 2026-02-22

## 1. Concepts

**Session** = long-lived conversation thread. Survives across multiple tasks.
**Task** = single execution run (user sends input → agent acts → completes).

A session contains one or more sequential tasks. `TaskCompleted` ends a task; `SessionCompleted` ends the session.

> See: `session/AgentSession.kt`, `protocol/SessionState.kt`

## 2. SessionState

```kotlin
sealed interface SessionState {
    data object Created         : SessionState  // constructed, not yet started
    data object Running         : SessionState  // actively executing a task
    data object TakeoverPending : SessionState  // takeover requested; awaiting agent pause-point
    data object Paused          : SessionState  // cooperative takeover (user has control)
    data object Idle            : SessionState  // between tasks (Hot Idle)
    data object Shutdown        : SessionState  // terminal — all resources released
}
```

Terminal state: `Shutdown` (irreversible).

`TakeoverPending` is a transient gate between `Running` and `Paused`: `handleTakeover` flips state to `TakeoverPending`, releases the lifecycle mutex, awaits the agent's pause-point deferred, then re-acquires the mutex and flips to `Paused`. `Op.Resume` is rejected while in `TakeoverPending`.

## 3. Transition Rules

```
Created ──(UserInput)──► Running ──(Takeover)──► TakeoverPending ──(agent pause-point)──► Paused
                           │  ▲                                                              │
                           │  └────────────────────(Resume)────────────────────────────────-─┘
                           │
                           └──(TaskCompleted)──► Idle ──(UserInput)──► Running
                                                  │
                                 Any ──(Shutdown)──► Shutdown ◄──(IdleTimeout)──┘
```

### 3.1 State transition table

| Current State | Trigger | New State | Actions |
|---------------|---------|-----------|---------|
| `Created` | `Op.UserInput` | `Running` | init recording, `platform.start()`, emit `SessionStarted` + `TaskStarted`, start agent |
| `Running` / `Paused` / `TakeoverPending` | `Op.UserInput` | — (rejected) | emit status "Agent is busy" |
| `Running` | agent completes | `Idle` | emit `TaskCompleted`, flush checkpoint, `agentRunner.clear()`, schedule idle timeout (note: platform stays alive) |
| `Running` | `Op.Takeover` | `TakeoverPending` → `Paused` | `agentRunner.pause()` then await deferred (mutex released); on confirm flip to `Paused` and emit `SessionTakeover` |
| `Running` / `TakeoverPending` | `Op.Interrupt` | unchanged | cancel pending `ask_user`, `agentRunner.stop()` + `cancelJob()` |
| `TakeoverPending` | `Op.Resume` | — (rejected) | log warn (agent hasn't reached pause-point) |
| `Paused` | `Op.Resume` | `Running` | `agentRunner.resume()`, emit `SessionResumed` |
| `Idle` | `Op.UserInput` | `Running` | cancel idle timeout, `platform.start()` (idempotent), emit `TaskStarted`, start agent |
| `Idle` | idle timeout (5min) | `Shutdown` | via `handleShutdown(IdleTimeout)` |
| Any (non-`Shutdown`) | `Op.Shutdown` | `Shutdown` | if a task was active emit `TaskCompleted(USER_STOPPED)`; cancel idle timeout, flush CLOSED checkpoint, `agentRunner.shutdown()`, `services.cleanup()`, emit `SessionCompleted` |
| `Shutdown` | any Op | — (ignored) | idempotent guard |

> Note: `Running → Idle` does **not** call `platform.stop()` — the platform stays alive across Hot Idle and is only released by `Shutdown` (`services.cleanup()`). See Section 7.

### 3.2 Supplement and UserResponse (non-state-changing)

| Current State | Trigger | Effect |
|---------------|---------|--------|
| `Running`, `Paused`, or `TakeoverPending` | `Op.Supplement` | inject text into conversation history, emit `SupplementReceived` |
| Any | `Op.UserResponse` | deliver response to pending `ask_user` call via `UserResponseChannel` |
| Any | `Op.Approve` | resolve pending tool approval |

## 4. Event Semantics

Two distinct completion events:

| Event | Scope | When | Meaning |
|-------|-------|------|---------|
| `TaskCompleted` | Per-task | Agent finishes one execution run | Task ended; session stays alive in Idle |
| `SessionCompleted` | Per-session | Explicit shutdown or idle timeout | Session terminated; all resources released |

`TaskCompleted` does **not** imply the conversation is over. The user can send follow-up input.

### 4.1 Task/session outcome mapping

`TaskCompleted.outcome` is `TaskOutcome` (task-level):

| AgentStopReason | TaskOutcome |
|-----------------|-------------|
| `GoalAchieved` | `GOAL_ACHIEVED` |
| `MaxTurnsReached` | `MAX_TURNS` |
| `TaskImpossible` | `TASK_IMPOSSIBLE` |
| `UserRequested` | `USER_STOPPED` |
| `Error` | `ERROR` |

`SessionCompleted.reason` is `SessionEndReason` (session-level, no task outcomes). It is derived from the **shutdown cause**, not the prior state:

| `ShutdownCause` | `SessionEndReason` |
|-----------------|--------------------|
| `UserRequested` (explicit `Op.Shutdown`) | `USER_STOPPED` |
| `IdleTimeout` (idle timer fires) | `IDLE_TIMEOUT` |

The enum also defines `INTERRUPTED` for external-cause interruptions, but no current code path emits it.

The split (pc-completion-semantics, 2026-04-16) removes the impossible-state overlap where `SessionCompleted` carried task-outcome values like `GOAL_ACHIEVED`.

## 5. Hot Idle

After task completion, the session enters `Idle` instead of shutting down.

### 5.1 Resource ownership

| Resource | Held during Idle? | Released on | Rationale |
|----------|-------------------|-------------|-----------|
| VirtualDisplay + ImageReader | Yes (released only on Shutdown via `services.cleanup()`) | Shutdown | Kept alive for instant follow-up; running VD apps survive |
| AgentRunner state | **No** (`agentRunner.clear()`) | TaskCompleted | Loop references; rebuilt on follow-up |
| HistoryManager | Yes | Shutdown | ~100KB-1MB; needed for follow-up context |
| TodoState + ScratchpadState | Yes | Shutdown | ~2KB conversation state |
| LLM client (cloud) | Yes | Shutdown | Stateless HTTP wrapper, negligible |
| ToolRouter | Yes | Shutdown | Cheap; tool registry reused |
| TraceRecorder | Yes | Shutdown | May append follow-up traces |
| Event stream (SharedFlow) | Yes | Shutdown (`closeChannelWithDelay`) | Open for follow-up events |

Total Idle memory footprint: < 2MB.

### 5.2 Idle timeout

| Parameter | Value |
|-----------|-------|
| `IDLE_TIMEOUT_MS` | 300,000ms (5 minutes) |

Implementation: single `Job` in `AgentSession`, launched on Idle entry, cancelled on any exit from Idle.

```
TaskCompleted → Idle: start timeout job
Idle + UserInput → Running: cancel timeout (user engaged)
Idle + timeout expires → Shutdown (auto, emits SessionCompleted with IDLE_TIMEOUT)
Idle + explicit Shutdown → cancel timeout, immediate cleanup
```

## 6. Checkpoint Coordination

`SessionCheckpointCoordinator` persists session state for process-death recovery.

### 6.1 Checkpoint states

| CheckpointState | Written when | Content |
|-----------------|-------------|---------|
| `IDLE_READY` | Task completion (`flushIdleReady()`) | History, todos, scratchpad, config |
| `CLOSED` | Shutdown (`flushClosed()`) | Same data, marks session as finished |

### 6.2 Mutation-driven scheduling

History, todos, and scratchpad changes trigger `scheduleCheckpoint()` via mutation listeners. Listeners are disabled on Shutdown to prevent writes after cleanup.

### 6.3 Reload from checkpoint

`AgentSession.reload(snapshot)` hydrates a new session from a persisted `SessionRuntimeSnapshot`:
- Restores `HistoryManager.replaceAll()` with deserialized history items
- Restores `TodoState` and `ScratchpadState`
- Returns session in `Created` state (first `UserInput` re-acquires platform)

Guard: only `IDLE_READY` and `CLOSED` snapshots are reloadable.

## 7. Platform Lifecycle

`platform.start()` is invoked at the start of each task; `services.cleanup()` (which stops the platform) runs only at Shutdown:

```
Created → Running:  platform.start()       (first task)
Idle    → Running:  platform.start()       (idempotent — no-op if already running)
Running → Idle:     (no platform.stop)     — VD/recording stay alive across Hot Idle
Any     → Shutdown: services.cleanup()     — releases platform + recording
```

If `platform.start()` fails on follow-up, session re-arms idle timeout and stays in Idle.

## 8. Session Creation Paths

| Path | Entry Point | Initial State |
|------|-------------|---------------|
| Fresh session | `AgentSession.create()` | `Created` |
| Fresh with pre-built services | `AgentSession.createWithServices()` | `Created` |
| Reload from checkpoint | `AgentSession.reload(snapshot)` | `Created` |

All paths converge: first `Op.UserInput` triggers `Created → Running`.

## Related Docs

- [User Flows](user_flows.md) -- session/task user interaction flows
- [Session Infrastructure](../../infra/session.md) -- SessionServices, AgentRunner, UserResponseChannel
- [Capsule State Machine](../capsule/state_machine.md) -- UI state machine (CapsuleMode)
- [Protocol](../../protocol/overview.md) -- Op/Event definitions

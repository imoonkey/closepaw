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
    data object Created  : SessionState  // constructed, not yet started
    data object Running  : SessionState  // actively executing a task
    data object Paused   : SessionState  // cooperative takeover (user has control)
    data object Idle     : SessionState  // between tasks (Hot Idle)
    data object Shutdown : SessionState  // terminal — all resources released
}
```

Terminal state: `Shutdown` (irreversible).

## 3. Transition Rules

```
Created ──(UserInput)──► Running ──(Takeover)──► Paused
                           │  ▲                    │
                           │  └────(Resume)────────┘
                           │
                           └──(TaskCompleted)──► Idle ──(UserInput)──► Running
                                                  │
                                 Any ──(Shutdown)──► Shutdown ◄──(IdleTimeout)──┘
```

### 3.1 State transition table

| Current State | Trigger | New State | Actions |
|---------------|---------|-----------|---------|
| `Created` | `Op.UserInput` | `Running` | init recording, `platform.start()`, emit `SessionStarted` + `TaskStarted`, start agent |
| `Running` | `Op.UserInput` | — (rejected) | emit status "Agent is busy" |
| `Running` | agent completes | `Idle` | emit `TaskCompleted`, flush checkpoint, `agentRunner.clear()`, `platform.stop()`, schedule idle timeout |
| `Running` | `Op.Takeover` | `Paused` | `agentRunner.pause()`, await deferred, emit `SessionTakeover` |
| `Running` | `Op.Interrupt` | stays `Running` | `agentRunner.stop()` (cooperative, agent stops at next turn boundary) |
| `Paused` | `Op.Resume` | `Running` | `agentRunner.resume()`, emit `SessionResumed` |
| `Idle` | `Op.UserInput` | `Running` | cancel idle timeout, `platform.start()`, emit `TaskStarted`, start agent |
| `Idle` | idle timeout (5min) | `Shutdown` | via `handleShutdown()` |
| Any | `Op.Shutdown` | `Shutdown` | cancel idle timeout, flush CLOSED checkpoint, `agentRunner.shutdown()`, `services.cleanup()`, emit `SessionCompleted` |
| `Shutdown` | any Op | — (ignored) | idempotent guard |

### 3.2 Supplement and UserResponse (non-state-changing)

| Current State | Trigger | Effect |
|---------------|---------|--------|
| `Running` or `Paused` | `Op.Supplement` | inject text into conversation history, emit `SupplementReceived` |
| Any | `Op.UserResponse` | deliver response to pending `ask_user` call via `UserResponseChannel` |
| Any | `Op.Approve` | resolve pending tool approval |

## 4. Event Semantics

Two distinct completion events:

| Event | Scope | When | Meaning |
|-------|-------|------|---------|
| `TaskCompleted` | Per-task | Agent finishes one execution run | Task ended; session stays alive in Idle |
| `SessionCompleted` | Per-session | Explicit shutdown or idle timeout | Session terminated; all resources released |

`TaskCompleted` does **not** imply the conversation is over. The user can send follow-up input.

### 4.1 CompletionReason mapping

`TaskCompleted` reasons (from `AgentStopReason`):

| AgentStopReason | CompletionReason |
|-----------------|-----------------|
| `GoalAchieved` | `GOAL_ACHIEVED` |
| `MaxTurnsReached` | `MAX_TURNS` |
| `UserRequested` | `USER_STOPPED` |
| `Error` | `ERROR` |

`SessionCompleted` reasons (from previous state):

| Previous State | CompletionReason |
|----------------|-----------------|
| `Running` / `Paused` | `USER_STOPPED` |
| `Idle` | `IDLE_TIMEOUT` |
| `Created` / other | `INTERRUPTED` |

## 5. Hot Idle

After task completion, the session enters `Idle` instead of shutting down.

### 5.1 Resource ownership

| Resource | Held during Idle? | Released on | Rationale |
|----------|-------------------|-------------|-----------|
| VirtualDisplay + ImageReader | **No** (`platform.stop()`) | TaskCompleted | Expensive; re-acquired on follow-up |
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

`platform.start()` and `platform.stop()` bracket each task execution:

```
Created → Running:  platform.start()  (first task)
Idle → Running:     platform.start()  (follow-up task)
Running → Idle:     platform.stop()   (task done)
Any → Shutdown:     services.cleanup() → platform.stop()
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

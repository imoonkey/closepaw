# Session Lifecycle Refactor Design

Date: 2026-02-22
Status: Draft by Claude
Follows: sop/system_design.md principles (but file not found; using established project patterns)

---

## 1. Problem Statement

After task completion, follow-up messages create a brand-new session with empty history
instead of continuing the conversation. The checkpoint is saved but never loaded because
the only reload path requires explicit user selection from history.

See `root_cause_claude.md` for full causal analysis and evidence.

## 2. Design Principle: Session = Conversation, Not Task

A **session** is a conversation that spans multiple tasks. It lives as long as the user
is engaged in a continuous interaction. It dies only when explicitly ended.

A **task** is a single agent run within a session. Tasks start and finish, but the
session continues.

This maps directly to how the CapsuleMode state machine works at the UI level:
- `Done` (3s auto-hide → Hidden) = task finished, ready for next
- Session end = explicit action (new session button, app shutdown)

The `SessionState` machine should mirror this semantic.

## 3. State Machine: Current vs Proposed

### 3.1 Current (6 states, 2 terminal)

```
Created ──UserInput──► Running ──TaskCompleted──► Completed [terminal]
                          │                            ↑
                          ├──Pause──► Paused ──Resume──┤
                          │                            │
                          └──Shutdown──► Shutdown [terminal]
                                          ↑
                          Idle ───────────┘ (unreachable in practice)
```

Problems:
- `Completed` is terminal → follow-up impossible without reload
- `Idle` exists but is only used as a fallback when checkpoint fails
- `Completed` vs `Shutdown` semantic overlap (both mean "done")
- 6 states for what is essentially a 3-state problem

### 3.2 Proposed (4 states, 1 terminal)

```
Created ──UserInput──► Running ──TaskCompleted──► Idle
    │                     │                         │
    │                     ├──Pause──► Paused         │
    │                     │             │            │
    │                     │ ◄──Resume──┘            │
    │                     │                         │
    │                     └──Shutdown──► Shutdown    │
    │                                     ↑         │
    │                                     │         │
    └─────────────Shutdown────────────────┤         │
                                          │         │
               Idle ──UserInput──► Running│         │
                    ──Shutdown──► Shutdown ◄─────────┘
```

Clean version:
```
     ┌────────────────────────────────────────────┐
     │                                            │
     ▼                                            │
  Created ──(UserInput)──► Running ──(TaskDone)──► Idle
                              │ ▲                   │
                              │ └──(Resume)──┐      │
                              │              │      │
                              └──(Pause)──► Paused  │
                              │                     │
                              └──(Shutdown)──┐      │
                                             ▼      │
                                          Shutdown ◄┘
                                          [terminal]
```

| State | Meaning | Accepts UserInput? |
|-------|---------|-------------------|
| `Created` | Session initialized, platform not started | Yes (triggers platform start) |
| `Running` | Agent is executing a task | No (busy) |
| `Paused` | Agent paused (takeover) | No (paused) |
| `Idle` | Between tasks, ready for follow-up | Yes |
| `Shutdown` | Session ended | No (terminal) |

### 3.3 Key Change: `Completed` Merged Into `Idle`

`Completed` was a terminal state that blocked all further input. `Idle` is the
"between tasks" state that accepts new `UserInput`. This is the single change that
fixes the follow-up problem.

`CompletionReason` (GOAL_ACHIEVED, MAX_TURNS, ERROR, etc.) is preserved in the
`TaskCompleted` event for observability, but it does NOT determine session liveness.

### 3.4 `Shutdown` Is the Only Terminal State

`Shutdown` means "this session object is done, release everything." It happens when:
- User starts a new session (explicit)
- User closes the app
- `Op.Shutdown` is submitted

## 4. Lifecycle: What Happens at Each Transition

### 4.1 TaskCompleted → Idle

```kotlin
private suspend fun handleAgentComplete(reason: AgentStopReason) {
    // 1. Emit TaskCompleted event (for UI: chat completion text, capsule Done)
    emit(TaskCompleted(...))

    // 2. Persist checkpoint (IDLE_READY)
    checkpointCoordinator.flushIdleReady()
    // Note: failure is logged but does NOT change session behavior.
    // Session stays alive in memory regardless.

    // 3. Release heavy task-scoped resources
    agentRunner.clear()
    services.platform.stop()  // release a11y/VD, re-acquire on next task

    // 4. Transition to Idle
    _state.value = SessionState.Idle
    currentTaskId = null
}
```

**What is NOT done:**
- `services.cleanup()` — NOT called. LLM client, HistoryManager stay alive.
- `closeChannelWithDelay()` — NOT called. Event stream stays open.
- `SessionCompleted` event — NOT emitted. Session is not over.

### 4.2 Idle → Running (follow-up)

```kotlin
private suspend fun handleUserInput(op: Op.UserInput) {
    when (_state.value) {
        SessionState.Idle -> {
            // Re-acquire platform if needed
            services.platform.start()

            // Start new task within same session
            val taskId = "task-${System.currentTimeMillis()}"
            currentTaskId = taskId
            _state.value = SessionState.Running
            emit(TaskStarted(...))
            agentRunner.start(op.text, taskId)
        }
        SessionState.Created -> { /* first task — same as today */ }
        SessionState.Running, SessionState.Paused -> { /* reject: busy */ }
        SessionState.Shutdown -> { /* reject: dead */ }
    }
}
```

**Key point:** HistoryManager already contains the full conversation from the previous
task. The LLM will see all prior turns when building the prompt. No reload needed.

### 4.3 Shutdown (explicit end)

```kotlin
private suspend fun handleShutdown() {
    _state.value = SessionState.Shutdown
    checkpointCoordinator.flushClosed()
    services.cleanup()  // release everything
    emit(SessionCompleted(...))
    closeChannelWithDelay()
}
```

## 5. Impact on MainActivity

### 5.1 `onTaskCompleted` Callback: No Longer Clears Session

Current:
```kotlin
onTaskCompleted = {
    lifecycleScope.launch {
        delay(50)
        if (state == Completed || state == Shutdown) {
            currentSession = null  // ← THIS IS THE BUG
        }
    }
}
```

Proposed:
```kotlin
onTaskCompleted = {
    // No-op. Session stays alive in Idle state.
    // Session is only cleared on explicit new session or shutdown.
}
```

### 5.2 `ensureSessionAndSend`: Simplified

Current flow has a complex `selectedSessionForReload` / `tryReloadSelectedSession` /
`createFreshSession` branching tree. With sessions staying alive, the common path is:

```kotlin
fun ensureSessionAndSend(text: String, ...) {
    val session = currentSession
    if (session != null) {
        when (session.state.value) {
            Idle, Created -> { session.submit(Op.UserInput(text)) }
            Running, Paused -> { enqueuePendingInput(text) }
            Shutdown -> { currentSession = null; ensureSessionAndSend(text, ...) }
        }
        return
    }
    // No session: create fresh or reload from checkpoint
    // (checkpoint reload is only needed after process death, not normal follow-up)
}
```

### 5.3 `selectedSessionForReload` Becomes Process-Death Recovery Only

The checkpoint reload path is no longer the primary follow-up mechanism. It's
a recovery path for when the process was killed between tasks:

1. User completes task → checkpoint saved (IDLE_READY)
2. Android kills process (memory pressure)
3. User reopens app → no `currentSession` in memory
4. User selects session from history → load checkpoint → continue

For non-process-death follow-up, the session is simply alive in memory.

## 6. Impact on Checkpoint/Persistence

Checkpointing still happens exactly as designed:
- Debounced during task execution (RUNNING_DIRTY)
- Force flush on task completion (IDLE_READY)
- Force flush on shutdown (CLOSED)

The only change is that checkpoint is no longer the PRIMARY mechanism for follow-up.
It's a RECOVERY mechanism for process death.

## 7. Impact on CapsuleMode / UI

No change. CapsuleMode already handles this correctly:
- `TaskCompleted` → `Done` → 3s auto-hide → `Hidden`
- `Hidden` accepts new input → `Running`

The UI was already designed for multi-task sessions. Only the backend was broken.

## 8. Resource Management

### What stays alive between tasks (in Idle state):
| Resource | Memory Cost | Rationale |
|----------|-------------|-----------|
| `HistoryManager` | ~100KB-1MB | Core conversation context, needed for follow-up |
| `TodoState` | ~1KB | Lightweight, part of conversation state |
| `ScratchpadState` | ~1KB | Lightweight, part of conversation state |
| `SessionRecordingService` | ~10KB | Needed to continue recording |
| `AgentSession` object | ~1KB | Session identity and state |

### What is released between tasks:
| Resource | Savings | Rationale |
|----------|---------|-----------|
| `AndroidPlatform` (a11y/VD) | Significant | No active task, no screen interaction needed |
| `AgentRunner` internals | Moderate | No active agent loop |
| Virtual Display (if VD mode) | Significant | Expensive system resource |

### LLM Client
The LLM client object is lightweight (just holds config + HTTP client). It does
NOT hold a persistent connection. Keeping it alive costs almost nothing.
Releasing and recreating it on every task adds unnecessary complexity.

## 9. State Machine Comparison: Session vs CapsuleMode

| Aspect | CapsuleMode (UI) | SessionState (Backend) |
|--------|-------------------|----------------------|
| Task active | Running | Running |
| Task done | Done → Hidden (3s) | Idle |
| Between tasks | Hidden (ready) | Idle (ready) |
| User input accepted | Hidden: yes | Idle: yes |
| Terminal | Error (dismiss → Hidden) | Shutdown |
| Paused | Takeover | Paused |

The state machines are now symmetric.

## 10. Migration: What Changes in Code

### Remove `SessionState.Completed`
- Delete the `Completed` variant from `SessionState`
- All code paths that check `Completed` either:
  - Should check `Shutdown` instead (if checking for terminal)
  - Should be removed (if they were handling post-task cleanup)

### Simplify `handleAgentComplete()`
- Remove `services.cleanup()` call
- Remove `closeChannelWithDelay()` call
- Remove `SessionCompleted` emission
- Remove `completionEmitted` flag
- Add `services.platform.stop()` for resource release
- Transition to `Idle` instead of `Completed`

### Simplify `handleUserInput()`
- Remove the `Completed` rejection branch
- Add `Idle` handling (re-start platform, run new task)
- Both `Created` and `Idle` paths converge to "start task"

### Simplify `onTaskCompleted` in `MainActivity`
- Remove the `currentSession = null` assignment
- The callback can be reduced to a no-op or removed

### Simplify `ChatViewModel.sendMessage()`
- Remove the `Completed` check that gates `onSessionNeeded`
- Session stays alive → `sessionProvider()` returns non-null → direct submit

### Simplify `ensureSessionAndSend()`
- Remove the `Completed` branch in the `session?.state?.value` when-block
- Cleanup and reload logic becomes process-death-only

## 11. Files to Modify

| File | Change | LOC Est. |
|------|--------|----------|
| `protocol/SessionState.kt` | Remove `Completed`, update doc comment | -10 |
| `session/AgentSession.kt` | Simplify `handleAgentComplete()`, `handleUserInput()` | -30 |
| `app/MainActivity.kt` | Simplify `onTaskCompleted`, `ensureSessionAndSend()` | -20 |
| `ui/chat/ChatViewModel.kt` | Remove `Completed` checks in `sendMessage()` | -5 |
| `app/AgentServiceEventHandler.kt` | Update Completed → Idle handling if any | ~0 |

Total: Net reduction of ~65 lines. The fix is a simplification, not an addition.

## 12. Verification

### Unit Tests
1. `SessionState` does not have `Completed`
2. `AgentSession`: after task completion, state is `Idle`
3. `AgentSession`: in `Idle` state, `UserInput` transitions to `Running`
4. `AgentSession`: in `Idle` state, history from previous task is preserved
5. `AgentSession`: `Shutdown` from `Idle` emits `SessionCompleted` and cleans up

### Integration Tests
1. Task → complete → follow-up: second task sees first task's history
2. Task → complete → new session (explicit): second session has empty history
3. Process death recovery: checkpoint reload still works for history-selected sessions

### Manual E2E
1. `debug-run.sh "Play Fish Leong on YouTube"` → complete → type follow-up in UI
2. Follow-up should reference the previous task correctly
3. Start new session → should have clean slate

## 13. What This Does NOT Change

- Checkpoint format (`SessionRuntimeSnapshot`) — unchanged
- Persistence strategy (debounce + force flush) — unchanged
- `HistoryItemConverter` — unchanged
- `SessionRecordingService` — unchanged (still records to same file)
- CapsuleMode state machine — unchanged
- UI layout/rendering — unchanged
- `AgentService.observeExternalSession()` — unchanged
- `SessionHistoryManager` — unchanged

## 14. Risks and Mitigations

### Risk: Memory pressure from long-lived sessions
**Mitigation:** HistoryManager already has auto-compress and token budget management.
A typical session's history is 100KB-1MB, well within Android memory norms.
If needed, a configurable idle timeout can release sessions after prolonged inactivity.

### Risk: Stale platform state after idle period
**Mitigation:** `services.platform.start()` is called on each new task from Idle.
This re-acquires accessibility/VD resources fresh. The platform start is idempotent.

### Risk: Event stream backpressure from long-lived sessions
**Mitigation:** `MutableSharedFlow` with `replay = 8` and `extraBufferCapacity = 64`
is already configured. Events are consumed as they're produced. No accumulation risk.

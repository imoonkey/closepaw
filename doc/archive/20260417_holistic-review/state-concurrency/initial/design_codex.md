# State & Concurrency Review (Codex)

## Scope

Reviewed from the state-management and concurrency angle:

- `session/`
- `protocol/`
- `history/`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt`

I did not read other `doc/todo/` design material.

## Executive Summary

The codebase has several good local building blocks:

- `HistoryManager` keeps its internal list behind `@Synchronized` and returns defensive copies.
- `PolicyEngine` uses `AtomicReference` plus concurrent sets and is comparatively well-contained.
- `SessionHistoryManager` is conservative about cache invalidation and avoids sharing mutable session-info objects.

The main problems show up one layer higher, where multiple locally-thread-safe components are composed without a single serialization boundary. The largest risks are:

1. Persistence writes are not single-writer and can be reordered or partially written.
2. The session lifecycle state machine is missing transient states, especially around takeover/pause.
3. `AgentSession` lifecycle mutations are not serialized end-to-end.
4. Tool cancellation is mostly bookkeeping, not actual execution cancellation.

Recommendation: `CHANGES_REQUESTED` before treating the current session/persistence path as robust under rapid user interaction, stop/shutdown races, or process death.

## State Ownership Map

| Component | Mutable State | Current Guard | Review Note |
| --- | --- | --- | --- |
| `AgentSession` | `_state`, `currentTaskId`, `idleTimeoutJob`, checkpoint listener lifecycle | No explicit guard; serialized only by caller convention | Main weak point |
| `SessionAgentRunner` | active `Agent`, `Job`, completion signal | `synchronized(stateLock)` | Local guard is fine, cross-component lifecycle is not |
| `Agent` | `turnCount`, `pauseState`, `pauseConfirmed`, `stopRequested` | Mixed (`StateFlow`, `AtomicBoolean`, `Mutex`) | Pause protocol is logically inconsistent |
| `HistoryManager` | `items`, token estimate | `@Synchronized` | Locally solid |
| `SessionRecordingService` | `currentSession`, `currentFileName`, save/checkpoint jobs, message buffer | `synchronized(stateLock)` | In-memory state is guarded; disk writes are not serialized |
| `ToolRouter` | `activeToolCalls`, `pendingApprovals` | `ConcurrentHashMap` | Tracking is concurrent, cancellation semantics are incomplete |
| `PolicyEngine` | approval mode, allow-lists | `AtomicReference`, concurrent sets | No major concurrency issue found |
| `ChatViewModel` | `_uiState`, `_messages`, `streamingBuffer` | Main-thread convention + `chatStateLock` | Works by convention, not hard guarantees |

The recurring pattern is that leaf containers are individually guarded, but cross-component transitions are not modeled as one serial state machine.

## Findings

## Critical

### 1. `SessionRecordingService` can let older writes overwrite newer session or checkpoint state

Why this matters:

- This is the highest-risk issue in the reviewed scope.
- It can lose the latest conversation state on disk, which directly impacts resume/reload correctness.

Evidence:

- `scheduleSave()` cancels the previous job and starts a new debounced job, but it does not wait for an already-running write to finish: `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:388-395`.
- `save()` snapshots `currentSession` under `stateLock` and writes it outside the lock: `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:343-354`.
- `scheduleCheckpoint()` has the same shape for checkpoint writes: `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:295-303`.
- `forceCheckpoint()` waits for the pending checkpoint job with `join()`, but does not cancel it first, so "force" is not actually preemptive: `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:310-317`.
- `SessionStorage.writeSession()` writes directly to the target file instead of using temp-file + rename, unlike snapshot writes: `app/src/main/kotlin/com/moonkey/androidagent/history/storage/SessionStorage.kt:77-87` vs. `app/src/main/kotlin/com/moonkey/androidagent/history/storage/SessionStorage.kt:181-196`.

Failure mode:

- Save A snapshots older in-memory state.
- Save B snapshots newer in-memory state.
- Save B finishes first.
- Save A, already past cancellation and already in file I/O, finishes later and overwrites the file with stale data.

The same reordering risk exists for checkpoint writes. In addition, a process death during `writeSession()` can leave a partially written JSON file because the session file path is not written atomically.

### 2. The declared pause/takeover state machine is not what the implementation actually does

Why this matters:

- The protocol says takeover becomes `Paused` only after the current action is allowed to finish.
- The implementation exposes `Paused` earlier and accepts `Resume` in that interim window.

Evidence:

- The protocol contract explicitly says takeover finishes the current action, then enters `Paused`: `app/src/main/kotlin/com/moonkey/androidagent/protocol/Op.kt:19-35`.
- The session-state diagram also models a clean `Running -> Paused -> Running` lifecycle with no transient state: `app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionState.kt:8-16`.
- `handleTakeover()` requests `agentRunner.pause()`, immediately sets `_state` to `Paused`, and only then waits for confirmation: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:399-411`.
- `handleResume()` accepts resume whenever `_state == Paused`: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:414-424`.
- `Agent.resume()` completes any pending pause confirmation and clears `pauseState`: `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt:183-190`.

Failure mode:

- User taps takeover.
- Session immediately reports `Paused`, even though the agent may still be finishing the current turn/action.
- A fast resume is accepted before the actual pause point is reached.
- `SessionResumed` can be emitted before `SessionTakeover`, or the agent can effectively skip the paused state entirely while the UI believed it had control.

This is a state-machine validity bug, not just a UX detail.

## High

### 3. `AgentSession` lifecycle operations are not serialized across suspend points

Why this matters:

- `AgentSession` is the top-level owner of lifecycle state, but it has no actor, mutex, or command queue.
- Several handlers suspend while partially through a transition, which allows interleaving with other lifecycle operations.

Evidence:

- `submit(op)` dispatches directly into handlers with no serialization boundary: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:227-239`.
- `handleAgentComplete()` performs trace flush, event emission, checkpoint flush, state mutation, runner clear, and idle-timeout scheduling in sequence: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:337-389`.
- `handleShutdown()` mutates `_state`, flushes a checkpoint, disables mutation listeners, shuts the runner down, cleans services up, and emits `SessionCompleted`: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:469-509`.
- `SessionAgentRunner.deliverCompletion()` invokes `onComplete` from `NonCancellable`, so completion handling can still arrive after cancellation/shutdown starts: `app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt:111-114`.
- Ops are launched from several different places as separate coroutines: `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:387-425`, `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:244-258`, `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:314-341`, `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:192-246`.

Important nuance:

- Much of the app currently relies on `Dispatchers.Main` scopes, so this is not always a multi-threaded race.
- It is still an interleaving problem, because these handlers suspend (`emit`, `forceCheckpoint`, `platform.start`, `confirmed.await`, etc.), and a second coroutine can run while the first transition is half-finished.

Representative bad interleaving:

- Completion starts and gets past its initial shutdown check.
- Completion suspends in `emit()` or `forceCheckpoint()`.
- A shutdown coroutine runs, sets `Shutdown`, cleans up resources, and emits `SessionCompleted`.
- Completion resumes and sets `Idle` plus a new idle-timeout job against a session that has already been torn down.

The current code relies too heavily on "probably main-thread serialized" behavior for a component this central.

### 4. `ToolRouter.cancel()` and `cancelAll()` do not actually cancel executing tools

Why this matters:

- The router claims responsibility for active tool calls.
- During cleanup, the session assumes tool execution has been canceled.
- In reality, only approval waiters are resolved; an already executing tool is not signaled by the router itself.

Evidence:

- Before execution, the router checks `context.isCancelled()`, and it passes that same context through to the invocation: `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:239-270`.
- `cancel()` only resolves approval and removes the active call from the tracking map: `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:333-337`.
- `cancelAll()` only completes approval deferreds and clears maps: `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:343-347`.
- `SimpleToolRouterContext.cancel()` exists, but the router does not retain or invoke per-call contexts/tokens: `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:388-397`.
- `ToolExecutionContext` defines cooperative cancellation, but the router's own cancel APIs never trip it: `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolSpec.kt:108-123`.

Consequence:

- A tool can keep running after `cancelAll()` has reported no active calls.
- Session cleanup can proceed under the false assumption that all device mutations have stopped.

The current tests only cover canceling the outer coroutine job (`ToolRouterTest.kt:113-127`), not router-driven cancellation of a live invocation.

## Medium

### 5. Explicit shutdown from `Idle` is reported as `IDLE_TIMEOUT`

Why this matters:

- This is a pure state-machine correctness issue.
- It pollutes user-visible status, analytics, and persisted completion metadata.

Evidence:

- `handleShutdown()` derives the completion reason from the previous state and maps `Idle` to `IDLE_TIMEOUT`: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:495-499`.
- A normal manual clear path can call shutdown while the session is idle: `app/src/main/kotlin/com/moonkey/androidagent/session/SessionCoordinator.kt:164-175`, `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:320-330`.

Consequence:

- A user explicitly stopping an idle session is recorded as a timeout, even when no timeout happened.

### 6. Off-main bootstrap is a convention, not an invariant

Why this matters:

- `SessionLlmBootstrapper` performs blocking asset I/O and model-catalog parsing.
- The component only logs when it is called from the main thread.

Evidence:

- `create()` calls `requireOffMainThread()` and proceeds either way: `app/src/main/kotlin/com/moonkey/androidagent/session/SessionLlmBootstrapper.kt:31-39`, `app/src/main/kotlin/com/moonkey/androidagent/session/SessionLlmBootstrapper.kt:90-97`.
- `loadModelCatalog()` does synchronous asset I/O on the caller thread: `app/src/main/kotlin/com/moonkey/androidagent/session/SessionLlmBootstrapper.kt:99-109`.
- Some call sites do the right thing by wrapping session creation in `withContext(Dispatchers.Default)`: `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:524-533`, `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:591-601`, `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:314-335`.
- The bootstrapper itself does not enforce that requirement, so safety depends on every caller remembering to do this.

This is not the worst defect in the reviewed scope, but it is exactly the kind of lifecycle footgun that drifts back into ANR territory over time.

## Additional Observations

- `PolicyEngine` is in relatively good shape for this review angle. `AtomicReference` plus concurrent allow-list sets is enough for its current responsibilities.
- `HistoryManager` itself is locally coherent; the larger issue is that checkpointing/persistence composes several mutable stores without a higher-level serialization boundary.
- `ChatViewModel` state is mostly safe because current mutations happen on `viewModelScope` / main-thread flows, but the locking strategy is not strong enough to make Compose state safe off-main. This is currently a convention-based design, not a hard guarantee.

## Test Gaps

The current test suite covers happy-path behavior better than adversarial interleavings. I did not find coverage for:

- overlapping `SessionRecordingService` save/checkpoint jobs where an older write completes after a newer one
- forced checkpoint behavior when a debounced checkpoint is still pending
- takeover followed immediately by resume, before pause confirmation arrives
- session completion interleaving with explicit shutdown
- router-driven `cancel()` / `cancelAll()` while a tool is already executing
- explicit idle-session shutdown being distinguished from actual idle timeout

Those gaps align closely with the highest-risk findings above.

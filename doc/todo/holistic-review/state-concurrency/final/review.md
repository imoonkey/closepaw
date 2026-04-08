# State & Concurrency Review — Final

Aligned review produced by double-design between Claude and Codex. Base: Codex design, supplemented by Claude's local hardening findings.

---

## Scope

- `session/` (13 files)
- `protocol/` (27 files)
- `history/` (15 files)
- `agent/Agent.kt`
- `tool/ToolRouter.kt`, `ToolRegistry.kt`
- `tool/PolicyEngine.kt`
- `ui/chat/ChatViewModel.kt`

## Executive Summary

The codebase has good local building blocks: `HistoryManager` uses `@Synchronized` with defensive copies, `PolicyEngine` uses `AtomicReference` plus concurrent sets, `SessionHistoryManager` is conservative about cache invalidation. No deadlocks are possible in the current code. No resource leaks were found.

The main problems show up one layer higher, where multiple locally-thread-safe components are composed without a single serialization boundary. The largest risks are:

1. Persistence writes can be reordered, causing data loss
2. The takeover/pause state machine doesn't match its contract
3. `AgentSession` lifecycle operations aren't serialized across suspend points
4. Tool cancellation is bookkeeping-only — executing tools aren't actually stopped

**Recommendation**: `CHANGES_REQUESTED` before treating the session/persistence path as robust under rapid user interaction, stop/shutdown races, or process death.

---

## Findings

### Critical

#### 1. SessionRecordingService: older writes can overwrite newer session/checkpoint state

**Data loss risk — highest priority finding.**

`scheduleSave()` cancels the previous job and starts a new debounced job, but does not wait for an already-running write to finish. `save()` snapshots state under lock and writes outside it. Two concurrent saves can complete out of order, with the older snapshot overwriting the newer one.

Additionally, `SessionStorage.writeSession()` writes directly to the target file (not temp-file + rename), unlike snapshot writes. A process death mid-write can corrupt the session JSON.

`forceCheckpoint()` waits for the pending job with `join()` but does not cancel it first, so "force" is not preemptive.

**Evidence**: `SessionRecordingService.kt:295-395`, `SessionStorage.kt:77-87` vs `SessionStorage.kt:181-196`.

#### 2. Takeover/pause state machine violates its declared contract

The protocol says takeover finishes the current action, then enters `Paused`. The implementation sets `Paused` immediately in `handleTakeover()` before the agent reaches its pause point, and accepts `Resume` in that window.

This means `SessionResumed` can be emitted before `SessionTakeover`, or the agent can skip the paused state entirely while the UI believed it had control.

**Evidence**: `protocol/Op.kt:19-35`, `session/AgentSession.kt:399-424`, `agent/Agent.kt:183-190`.

### High

#### 3. AgentSession lifecycle operations not serialized across suspend points

`AgentSession` is the top-level lifecycle owner but has no actor, mutex, or command queue. Handlers suspend during transitions (emit, forceCheckpoint, platform.start, etc.), allowing interleaving. Multiple entry points launch ops as separate coroutines.

Representative failure: completion starts, suspends in emit/checkpoint, shutdown runs and tears down resources, completion resumes and sets `Idle` on an already-shutdown session.

Currently relies on main-thread confinement for partial safety, but this is a coroutine-interleaving problem (not just multi-threaded).

**Evidence**: `session/AgentSession.kt:227-509`, `session/SessionAgentRunner.kt:111-114`.

#### 4. ToolRouter cancel/cancelAll don't actually cancel executing tools

`cancel()` resolves approval waiters and removes tracking entries. `cancelAll()` completes approval deferreds and clears maps. Neither signals executing tools. `SimpleToolRouterContext.cancel()` exists but the router never invokes it. Session cleanup proceeds under the false assumption that tool execution has stopped.

**Evidence**: `tool/ToolRouter.kt:239-397`, `tool/ToolSpec.kt:108-123`.

### Medium

#### 5. Explicit shutdown from Idle is reported as IDLE_TIMEOUT

`handleShutdown()` derives completion reason from previous state and maps `Idle` → `IDLE_TIMEOUT`. A user explicitly stopping an idle session is recorded as a timeout.

**Evidence**: `session/AgentSession.kt:495-499`, `session/SessionCoordinator.kt:164-175`.

#### 6. Off-main bootstrap is convention, not invariant

`SessionLlmBootstrapper.requireOffMainThread()` logs a warning but proceeds regardless. Blocking asset I/O runs on the caller thread. Safety depends on every caller wrapping in `withContext(Dispatchers.Default)`.

**Evidence**: `session/SessionLlmBootstrapper.kt:31-109`.

### Low (Quick Wins)

#### 7. ToolRegistry: unsynchronized HashMap

Plain `mutableMapOf()` accessed from multiple coroutines. Fix: `ConcurrentHashMap`.

#### 8. TodoState: onMutation not @Volatile

Inconsistent with `ScratchpadState` which correctly uses `@Volatile`.

#### 9. HistoryManager: onMutation not @Volatile

Callback invoked outside `@Synchronized` blocks. Shutdown null-set can race with concurrent mutation.

#### 10. SessionAgentRunner: state published after coroutine launch

State assignment happens after `scope.launch`, so `pause()`/`stop()` called in the window see stale state (agent=null).

#### 11. SessionHistoryManager: redundant ConcurrentHashMap + Mutex

All access guarded by `cacheMutex`, making the concurrent map redundant.

---

## State Ownership Map

| Component | Mutable State | Current Guard | Status |
| --- | --- | --- | --- |
| AgentSession | _state, currentTaskId, idleTimeoutJob | No explicit guard; caller convention | **Needs serialization** |
| SessionAgentRunner | active Agent, Job, completion signal | `synchronized(stateLock)` | Local OK, cross-component not |
| Agent | turnCount, pauseState, pauseConfirmed, stopRequested | Mixed (StateFlow, AtomicBoolean, Mutex) | Pause protocol inconsistent |
| HistoryManager | items, token estimate | `@Synchronized` | Locally solid |
| SessionRecordingService | currentSession, fileName, save/checkpoint jobs, buffer | `synchronized(stateLock)` | In-memory guarded; **disk writes not serialized** |
| ToolRouter | activeToolCalls, pendingApprovals | `ConcurrentHashMap` | Tracking concurrent; **cancellation incomplete** |
| PolicyEngine | approval mode, allow-lists | AtomicReference, concurrent sets | OK |
| ChatViewModel | _uiState, _messages, streamingBuffer | Main-thread convention + chatStateLock | Convention-based |

---

## Test Gaps

No coverage found for:
- Overlapping save/checkpoint jobs where older write completes after newer
- Force checkpoint while debounced checkpoint is pending
- Takeover followed immediately by resume before pause confirmation
- Session completion interleaving with explicit shutdown
- Router-driven cancel/cancelAll while tool is executing
- Explicit idle-session shutdown vs actual idle timeout reason

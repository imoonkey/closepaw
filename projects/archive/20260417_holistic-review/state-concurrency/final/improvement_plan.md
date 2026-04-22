# State & Concurrency Improvement Plan — Final

Aligned plan from double-design (Claude + Codex). Ordered by risk. KISS: reduce the number of synchronization stories, not add more.

---

## Guiding Principles

1. One serialized lifecycle owner for session state
2. One serialized writer for persisted session/checkpoint state
3. True cancellation semantics for in-flight tools
4. Explicit transient states where handoff is not instantaneous

---

## Phase 0: Trivial Hardening (land opportunistically alongside Phase 1)

These are low-effort fixes that don't require structural changes. Land them as they naturally fit, not as a separate gating tranche.

### 0a. ToolRegistry: use ConcurrentHashMap

**File**: `tool/ToolRegistry.kt`
**Change**: `mutableMapOf()` → `ConcurrentHashMap()` (1 line)

### 0b. TodoState: add @Volatile to onMutation

**File**: `session/TodoState.kt`
**Change**: Add `@Volatile` (1 line)

### 0c. HistoryManager: add @Volatile to onMutation

**File**: `history/HistoryManager.kt`
**Change**: Add `@Volatile` (1 line)

### 0d. SessionAgentRunner: assign state before launching coroutine

**File**: `session/SessionAgentRunner.kt`
**Change**: Pre-register `RunnerState` with null job before `scope.launch`, then update with captured job reference (~10 lines)

---

## Phase 1: Make Persistence Single-Writer

**Priority**: Highest — prevents data loss.

### Changes

- Serialize all disk writes in `SessionRecordingService` through one `Mutex`-guarded writer with a monotonically increasing revision number
- A write checks its revision against the current before committing — stale writes are dropped
- Make `SessionStorage.writeSession()` atomic: temp-file + rename (matching existing `writeSnapshot()` pattern)
- `forceCheckpoint()` preempts pending debounced work, not waits behind it

### Acceptance Criteria

- No older save can overwrite a newer session record
- No older checkpoint can overwrite a newer checkpoint
- Force checkpoint writes the newest snapshot immediately
- Session JSON files are crash-safe at the file level

### Tests

- Overlapping saves: later logical revision wins regardless of completion order
- Same for checkpoints
- `forceCheckpoint()` while debounced checkpoint is pending → persisted file has forced state
- Session writes use temp-file replacement

---

## Phase 2: Serialize AgentSession Lifecycle

**Priority**: High — prevents invalid state from interleaving.

### Changes

- Put all lifecycle inputs through one serialized path:
  - UserInput, Takeover, Resume, Interrupt, Shutdown
  - Runner completion callback
  - Idle-timeout expiry
- Implementation: one serialized lifecycle path — prefer `Mutex` if sufficient; use a minimal command serializer if the multi-source event pattern makes that clearer. Decision made at implementation time.
- Do not let `SessionAgentRunner.onComplete` mutate session state directly; route through the same path
- Keep `idleTimeoutJob` creation/cancellation under the serialized owner

### Acceptance Criteria

- Session state can only move forward via one serialized transition path
- Completion and shutdown cannot interleave into invalid state
- Idle-timeout creation/cancellation cannot race with user input or shutdown

### Tests

- Completion + shutdown triggered back-to-back → final state is `Shutdown`, not `Idle`
- Two `UserInput` ops around Idle→Running → only one task starts
- Duplicate shutdown → exactly one terminal event emitted

---

## Phase 3: Model Takeover as Real Multi-Step State

**Priority**: High — fixes contract violation.

### Changes

- Add internal transient state (e.g., `PauseRequested` / `TakeoverPending`)
- Do not publish `Paused` until agent confirms it reached the pause point
- Reject `Resume` while takeover is still pending
- Keep protocol surface minimal — transient state can be internal if UI doesn't need it

### Acceptance Criteria

- Implementation matches `Op.Takeover` contract
- `SessionTakeover` cannot be emitted after `SessionResumed`
- Rapid resume before pause point is either queued or rejected cleanly

### Tests

- Takeover-then-immediate-resume: correct ordering
- `Paused` not observable before pause confirmation
- Takeover/resume events emitted in valid order only

---

## Phase 4: Make Tool Cancellation Real

**Priority**: Medium-High — prevents false "all clear" during cleanup.

### Changes

- Store per-call `ToolExecutionContext` (or cancellation token) in `activeToolCalls`
- `cancel(callId)` signals the token for both approval waiters and executing invocations
- `cancelAll()` transitions calls toward cancellation, only removes tracking once terminal
- If a tool cannot be cooperatively cancelled, state machine says so explicitly

### Acceptance Criteria

- Router-driven cancel propagates to executing tools
- `activeToolCalls` reflects real execution, not optimistic cleanup
- Session cleanup doesn't report "all canceled" while actions still running

### Tests

- Tool stub polling `context.isCancelled()`: `router.cancel(callId)` flips it
- `cancelAll()` while one tool awaits approval and another executes
- Cleanup verifies terminal tool state before dropping tracking

---

## Phase 5: Correct Shutdown Reason Semantics

**Priority**: Medium — correctness and analytics.

### Changes

- Pass explicit shutdown cause into lifecycle transition instead of inferring from previous state
- Map `SessionCompleted.reason` from the explicit cause

### Acceptance Criteria

- Manual shutdown from `Idle` → `USER_STOPPED`, not `IDLE_TIMEOUT`
- Timeout shutdown → `IDLE_TIMEOUT` only when timeout actually fired

### Tests

- Manual shutdown from Idle: reason is USER_STOPPED
- Actual timeout: reason is IDLE_TIMEOUT

---

## Phase 6: Bootstrap Hardening (evidence-driven, lowest priority)

**Priority**: Low — defer until core invariants are fixed.

### Changes (only if evidence justifies)

- Enforce off-main bootstrap inside the layer itself, not at call sites
- Change `requireOffMainThread()` from warning to invariant or internal dispatcher hop
- Consider whether high-frequency MessageDelta should be best-effort while lifecycle events are lossless

### Acceptance Criteria

- Session creation cannot do asset I/O on main thread
- Slow UI collectors don't stall shutdown or task completion

---

## Cleanup (when convenient)

### SessionHistoryManager: remove redundant ConcurrentHashMap + Mutex

**File**: `history/SessionHistoryManager.kt`
**Change**: Replace `ConcurrentHashMap` with plain `HashMap` — all access already guarded by `cacheMutex`

---

## Minimum Test Matrix

Before calling this done, all of the following must be green:

- [ ] SessionRecordingService overlapping-save ordering
- [ ] SessionRecordingService force-checkpoint preemption
- [ ] AgentSession completion-vs-shutdown interleaving
- [ ] AgentSession takeover/resume race
- [ ] ToolRouter router-driven cancellation during execution
- [ ] AgentSession explicit idle shutdown reason

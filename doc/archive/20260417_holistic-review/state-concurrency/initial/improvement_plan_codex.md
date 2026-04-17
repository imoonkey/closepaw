# State & Concurrency Improvement Plan (Codex)

## Goal

Reduce the session stack to a simpler set of invariants:

1. one serialized lifecycle owner for session state
2. one serialized writer for persisted session/checkpoint state
3. real cancellation semantics for in-flight tools
4. explicit transient states where handoff is not instantaneous

KISS principle for this work:

- do not add more ad hoc locks
- do not patch symptoms in multiple places
- move responsibility toward one owner per mutable domain

## Proposed Order

1. Fix persistence ordering and atomicity first.
2. Then serialize `AgentSession` lifecycle handling.
3. Then repair tool cancellation semantics.
4. Finally harden bootstrap/event-path assumptions.

This order gets the highest data-loss risk down first, while keeping each step locally reviewable.

## Phase 1: Make Persistence Single-Writer

### Changes

- Replace `saveJob` and `checkpointSaveJob` with a single persistence coordinator inside `SessionRecordingService`.
- Feed that coordinator debounced intents such as:
  - `SaveSession`
  - `SaveCheckpoint`
  - `ForceCheckpoint`
  - `FinalizeAndSave`
- Serialize all disk writes through one coroutine/actor or one `Mutex`-guarded writer loop.
- Introduce a monotonically increasing in-memory revision number so a write can prove it is still current before committing.
- Make `SessionStorage.writeSession()` atomic using the same temp-file + rename pattern already used by `writeSnapshot()`.
- Change `forceCheckpoint()` to preempt pending debounced checkpoint work instead of waiting behind it.

### Acceptance Criteria

- No older save can overwrite a newer session record.
- No older checkpoint can overwrite a newer checkpoint.
- A force checkpoint writes the newest snapshot immediately, even if a debounce window is open.
- Session JSON files are crash-safe at the file level, not just snapshot files.

### Tests

- Add a deterministic test where two saves overlap and the later logical revision must win regardless of completion order.
- Add the same test for checkpoints.
- Add a test where `forceCheckpoint()` is called while a debounced checkpoint is pending; the persisted file must end in the forced state.
- Add a test verifying session writes use temp-file replacement semantics.

## Phase 2: Serialize `AgentSession` Lifecycle

### Changes

- Put all session lifecycle inputs through a single command processor:
  - `UserInput`
  - `Takeover`
  - `Resume`
  - `Interrupt`
  - `Shutdown`
  - runner completion callback
  - idle-timeout expiry
- Preferred implementation: a small actor/channel inside `AgentSession`.
- Alternative if you want the smaller refactor first: a single `Mutex` around every lifecycle transition plus strict "read state only inside transition" rules.
- Do not let `SessionAgentRunner.onComplete` mutate session state directly; route it through the same command path as user ops.
- Keep `idleTimeoutJob` creation/cancellation under the same serialized owner.

### Acceptance Criteria

- `AgentSession` state can only move forward via one serialized transition path.
- Completion and shutdown cannot interleave into an invalid state.
- Idle-timeout creation/cancellation cannot race with user input or shutdown.

### Tests

- Add a test where task completion and shutdown are triggered back-to-back; final state must be `Shutdown`, not `Idle`.
- Add a test where two `UserInput` ops arrive around an `Idle -> Running` transition; only one task start should occur.
- Add a test for duplicate shutdown requests; exactly one terminal event should be emitted.

## Phase 3: Model Takeover as a Real Multi-Step State

### Changes

- Add an internal transient lifecycle state such as `PauseRequested` or `TakeoverPending`.
- Keep public protocol options simple:
  - either expose the transient state explicitly
  - or keep it internal but do not publish `Paused` until confirmation arrives
- Update `handleTakeover()` so it does not flip to `Paused` before the agent actually reaches its pause point.
- Reject `Resume` while takeover is still pending.
- Separate "resume acknowledged" from "resume requested" if needed, but avoid over-modeling unless the UI actually needs both.

### Acceptance Criteria

- The implementation matches the `Op.Takeover` contract.
- `SessionTakeover` cannot be emitted after `SessionResumed`.
- A rapid resume request before the pause point is either queued cleanly or rejected cleanly.

### Tests

- Add a takeover-then-immediate-resume test.
- Add a test that `Paused` is not observable before pause confirmation.
- Add a test that takeover/resume events are emitted in valid order only.

## Phase 4: Make Tool Cancellation Real

### Changes

- Store a per-call cancellation token or mutable execution handle in `activeToolCalls`.
- `cancel(callId)` must signal that token for both:
  - approval waiters
  - already-executing invocations
- `cancelAll()` should not clear call tracking immediately; it should transition calls toward cancellation and only remove them once they reach terminal state.
- If a tool cannot be cooperatively canceled, the state machine should say so explicitly instead of pretending it is already gone.
- Keep `ToolRouter` responsible for truthfully representing execution state.

### Acceptance Criteria

- Router-driven cancel propagates to executing tools, not just approval waiters.
- `activeToolCalls` reflects real execution, not optimistic cleanup.
- Session cleanup does not report "all calls canceled" while actions are still running.

### Tests

- Add a tool stub that polls `context.isCancelled()` and assert `router.cancel(callId)` flips it.
- Add a `cancelAll()` test while one tool is awaiting approval and another is executing.
- Add a cleanup test that verifies terminal tool state is reached before the router drops tracking.

## Phase 5: Harden Bootstrap and Event-Critical Paths

### Changes

- Move blocking LLM/bootstrap asset work off-main inside the session/bootstrap layer itself, not only at selected call sites.
- Change `SessionLlmBootstrapper.requireOffMainThread()` from a warning-only check into an enforced invariant or an internal dispatcher hop.
- Revisit whether high-frequency UI deltas should use the same suspending `SharedFlow.emit()` path as lifecycle-critical events.
- If keeping one event bus, consider making frequent `MessageDelta` delivery best-effort while keeping lifecycle/approval events lossless.

### Acceptance Criteria

- Session creation cannot accidentally do asset I/O on the main thread.
- Slow UI event collectors do not stall shutdown, approval handling, or task completion signaling.

### Tests

- Add a test that session/bootstrap creation is safe from a main-thread caller.
- Add a stress test with many `MessageDelta` events and a slow collector; shutdown and task completion must still complete promptly.

## Phase 6: Correct Shutdown Reason Semantics

### Changes

- Stop inferring shutdown reason only from `previousState`.
- Pass an explicit shutdown cause into the lifecycle transition:
  - user requested stop
  - idle timeout expired
  - teardown / interruption
- Map `SessionCompleted.reason` from that explicit cause.

### Acceptance Criteria

- Manual shutdown from `Idle` is reported as `USER_STOPPED`, not `IDLE_TIMEOUT`.
- Timeout shutdown is reported as `IDLE_TIMEOUT` only when the timeout path actually fired.

### Tests

- Add one test for manual shutdown from `Idle`.
- Add one test for actual idle-timeout shutdown.
- Assert the emitted `SessionCompleted.reason` values differ correctly.

## Suggested Refactor Shape

Keep the implementation small and explicit:

- `AgentSession`
  - owns one command actor
  - owns one lifecycle enum
  - owns idle-timeout scheduling
- `SessionRecordingService`
  - owns one persistence writer
  - exposes simple "record mutation" and "flush now" APIs
- `ToolRouter`
  - owns true call lifecycle
  - owns cancellation tokens for those calls

Avoid spreading fixes across `MainActivity`, `AgentService`, `ChatViewModel`, and the reducers unless a user-facing requirement truly lives there. Most of the current problems come from core invariants being enforced at the edges instead of at the owners.

## Minimum Test Matrix Before Calling This Done

- `SessionRecordingService` overlapping-save ordering
- `SessionRecordingService` force-checkpoint preemption
- `AgentSession` completion-vs-shutdown interleaving
- `AgentSession` takeover/resume race
- `ToolRouter` router-driven cancellation during execution
- `AgentSession` explicit idle shutdown reason

If those tests are green, the state/concurrency posture of this subsystem will be materially better, and the design will be simpler than the current mix of local guards plus cross-component conventions.

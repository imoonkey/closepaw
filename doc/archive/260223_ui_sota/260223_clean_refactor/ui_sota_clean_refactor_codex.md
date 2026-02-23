# UI SOTA Clean Refactor Design (Codex)

Date: 2026-02-23  
Scope: session lifecycle (hot-idle/reload), main orchestration, overlay/capsule coordination  
Style: KISS, no backward-compat constraints, delete obsolete paths

## 1. Hard Findings (Code Review)

### P0 - Async clear can wipe a newly created session (real race)
- `SessionRecordingService.clearSession()` clears state in a fire-and-forget coroutine, not synchronously.  
  Code: `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:262`
- Callers continue immediately and can create/resume another session before that coroutine runs.  
  Code: `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:248`
- Result: delayed clear can nuke new `currentSession/currentFileName/contextFileName`.

### P1 - Pending input queue uses timer polling and self-requeue loop
- Busy session path enqueues input (`Running/Paused`), then a timer drains queue every 200ms.  
  Code: `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:421`
- Drain calls `ensureSessionAndSend`, which re-enqueues while still busy, then schedules another timer.  
  Code: `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:559`
- This is a loop-by-design during long tasks: noisy, wasteful, and makes control flow opaque.

### P1 - Session lookup is fuzzy (`contains`) instead of exact
- Load/delete finds file by `file.name.contains(sessionId)`.  
  Code: `app/src/main/kotlin/com/moonkey/androidagent/history/SessionHistoryManager.kt:90`
- This is not a stable key match and is logically wrong even if collisions are rare.

### P1 - Checkpoint state can drift from runtime reality
- Mutation listener always schedules `RUNNING_DIRTY` snapshot, regardless of session phase.  
  Code: `app/src/main/kotlin/com/moonkey/androidagent/session/SessionCheckpointCoordinator.kt:37`
- In hot-idle design, this can overwrite a clean `IDLE_READY` checkpoint with a dirty marker.

### P2 - Session op handling relies on implicit single-thread usage
- `AgentSession.submit()` mutates shared fields/state without explicit serialization primitive.  
  Code: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:220`
- Works today mostly because callers happen to be on Main, but contract is implicit.

### P2 - Overgrown orchestration files violate local complexity budget
- `MainActivity.kt` is 603 lines and mixes intent parsing, session orchestration, queueing, and UI wiring.  
  Code: `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt`
- `AgentSession.kt` is 557 lines and mixes lifecycle FSM, platform lease, checkpointing, and event emission.  
  Code: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt`

### P3 - Dead or weakly justified paths
- `onTaskCompleted` callback is effectively a no-op log.  
  Code: `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:114`
- `autoStart` payload field is parsed but not used for behavior.  
  Code: `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityIntentPayload.kt:21`

## 2. Refactor Goal

Make session orchestration boring and deterministic:
1. One owner for session/thread decisions.
2. One serialized event loop for all lifecycle mutations.
3. No timer polling for input queue.
4. No async “eventual clear” race.
5. MainActivity becomes thin wiring only.

## 3. Target Design

### 3.1 Introduce `SessionThreadCoordinator` (single owner)

New component (owned by `MainActivity`) that handles:
- thread binding (`none` / `selected-for-reload` / `live`)
- runtime lease (`none` / `agentSession`)
- input queue
- create/reload/shutdown transitions

Public API (minimal):
- `onUserInput(text)`
- `onSelectHistory(sessionInfo)`
- `onNewSession()`
- `onTaskCompleted()` (signal from UI/event layer)
- `onServiceAvailable(agentService?)`

Output:
- current `AgentSession?`
- UI capability flags (`canSend`, `viewOnlyReason?`)

Everything above is serialized by a single coroutine actor (`Channel<Event>` + reducer).

### 3.2 Canonicalize input flow (remove polling)

Current behavior has two paths: immediate submit vs enqueue+timer retry.  
Replace with one rule:
- Every user input is appended to queue.
- Actor drains queue only when runtime is in `Idle/Created`.
- On `Running/Paused`, queue stays queued (no retries, no timers).
- On task completion signal, actor tries drain once.

This removes `scheduleDrainPendingInputs()` and recursive `ensureSessionAndSend()` behavior.

### 3.3 Make clear deterministic

Change:
- `SessionRecordingService.clearSession()` -> `suspend fun clearSessionAndAwait()`
- It must complete clears before returning.

Callers (`MainActivity` / coordinator) must await clear before creating/resuming another session.

### 3.4 Make key matching exact

In `SessionHistoryManager`, replace `contains(sessionId)` with exact extraction:
- Parse session id from filename format `session-{ts}-{sessionId}.json`
- Compare equality.

If parse fails: skip file, do not guess.

### 3.5 Checkpoint state should be derived, not hardcoded

Refactor `SessionCheckpointCoordinator.scheduleCheckpoint()` to derive state from session phase:
- running/paused -> `RUNNING_DIRTY`
- idle -> `IDLE_READY`
- shutdown -> `CLOSED`

Do not let a listener blindly stamp `RUNNING_DIRTY`.

### 3.6 Split fat classes by responsibility

`MainActivity.kt` split into:
- `MainSessionBridge` (wires coordinator <-> viewmodel/service)
- `MainIntentCoordinator` (intent extras to settings/actions)
- `MainUiEntrypoint` (Compose setup only)

`AgentSession.kt` split into:
- `SessionLifecycleController` (FSM + op reducer)
- `SessionRuntimeLease` (platform start/stop, runner clear/restart)
- `SessionCheckpointFacade` (flush/schedule policy)
- `SessionEventPublisher` (emission and delivery policy)

## 4. Deprecate/Delete Plan

Delete after replacement:
- `MainActivity.pendingInputs`, `drainPendingRunnable`, `scheduleDrainPendingInputs()`, `drainPendingInputs()`
- recursive retry path inside `ensureSessionAndSend()`
- no-op `onTaskCompleted` callback hook in `MainActivity` if coordinator takes over
- unused `autoStart` payload field if no product requirement needs it

## 5. Implementation Sequence (Safe + Fast)

### Stage A - Correctness First (smallest risky set)
1. Add `clearSessionAndAwait()` and migrate all clear callers.
2. Replace queue polling with event-driven drain (`onTaskCompleted` trigger).
3. Fix exact filename/sessionId matching.
4. Fix checkpoint state derivation.

### Stage B - Structure
1. Introduce `SessionThreadCoordinator`.
2. Move `ensureSessionAndSend` orchestration into coordinator.
3. Keep `MainActivity` as lifecycle/ui shell.

### Stage C - Cleanup
1. Remove deprecated fields/functions.
2. Remove dead extras/callbacks.
3. Optionally make `SessionStorage.writeSession` atomic (same standard as snapshot writes).

## 6. Required Tests

Add tests before/with refactor:
1. `SessionRecordingService` race test: clear-then-create cannot clear new session.
2. Queue behavior test: while `Running`, input is queued once and not timer-retried.
3. Coordinator test: completion signal drains exactly one queued input.
4. SessionHistoryManager test: exact file-session matching (no substring false match).
5. Checkpoint test: idle mutation writes `IDLE_READY`, not `RUNNING_DIRTY`.
6. AgentSession lifecycle test: idle timeout and follow-up are serialized through one reducer path.

## 7. Non-Goals

- No behavioral compatibility with old orchestration internals.
- No migration layer for obsolete queue/retry logic.
- No parallel state owners in Activity + Service after coordinator lands.


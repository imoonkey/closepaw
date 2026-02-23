# Code Review: Hot Idle Session Lifecycle Refactor (Phase 1)

**Commit**: `044b075` feat: session reload checkpoint infra + lifecycle refactor design
**Reviewer**: Claude (Opus 4) -- Systematic /code-review
**Date**: 2026-02-22
**Files reviewed**: 4 Kotlin files (SessionState.kt, AgentSession.kt, MainActivity.kt, ChatViewModel.kt)

---

## CODE REVIEW: `app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionState.kt`

Clean removal of `Completed` variant. State diagram in KDoc is accurate and matches the implementation. No issues.

**Verdict**: CLEAN

---

## CODE REVIEW: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt`

### [HIGH] Race: idleTimeoutJob variable not thread-safe

**Lines**: 216, 521-527, 530-533
**Problem**: `idleTimeoutJob` is a plain `var` with no synchronization. It is written from:
1. `handleAgentComplete()` -> `scheduleIdleTimeout()` (called from agentRunner's coroutine via `onComplete`)
2. `handleUserInput()` -> `cancelIdleTimeout()` (called from `submit()` on main thread or any caller's thread)
3. `handleShutdown()` -> `cancelIdleTimeout()` (can be called from the idle timeout coroutine itself, from `submit()`, or from `AgentService.onDestroy`)

Although the `scope` is typically `Dispatchers.Main`, the `submit()` function is a `suspend fun` callable from any dispatcher, and `onComplete` fires from a `scope.launch` which inherits `Dispatchers.Main`. In practice on Android these will serialize on the main looper, but the code has no structural guarantee of this. Compare with `channelCloseScheduled` which uses `AtomicBoolean` for the same class of problem, and `SessionAgentRunner.state` which uses a `stateLock`.

**Fix**: Either:
- Document that `submit()` and all callers must run on `Dispatchers.Main` (the current implicit contract), OR
- Wrap `idleTimeoutJob` access in a `synchronized(lock)` block or use `Mutex` for consistency with the defensive patterns already in the codebase.

---

### [HIGH] Missing `disableCheckpointMutationListeners()` during Hot Idle -- intentional but undocumented risk

**Lines**: 357-370 (handleAgentComplete)
**Problem**: In the old code, `handleAgentComplete()` called `disableCheckpointMutationListeners()`. In the new code, mutation listeners remain active during Idle. This is *correct* for Hot Idle (listeners are needed if follow-up modifies history/todos). However, there is a subtle risk: if anything modifies `historyManager`, `todos`, or `scratchpad` between task completion and shutdown (e.g., a rogue event, a follow-up that fails before transitioning to Running), it will trigger checkpoint writes against a session that has already been checkpointed as `IDLE_READY`. This could corrupt the checkpoint state if `scheduleCheckpoint()` overwrites the `IDLE_READY` checkpoint with a different state.

**Fix**: Add a comment at line 357 explicitly documenting that mutation listeners are intentionally kept alive during Idle, and verify that `scheduleCheckpoint()` produces an `IDLE_READY` checkpoint (not a `RUNNING` one) when the session state is `Idle`.

---

### [MEDIUM] `handleAgentComplete` does not call `services.userResponseChannel.cancel()`

**Lines**: 325-372
**Problem**: When transitioning to Hot Idle, if there is a pending `ask_user` request that was never resolved, the `userResponseChannel` still holds a dangling `CompletableDeferred`. On follow-up, a new task starts while the old unresolved request lingers. `handleShutdown()` correctly calls `services.userResponseChannel.cancel()`, but `handleAgentComplete()` does not.

This is unlikely to cause a crash (the agent loop that was awaiting the response is gone after `agentRunner.clear()`), but it is a resource hygiene issue -- the dangling deferred will only be collected when the session shuts down.

**Fix**: Call `services.userResponseChannel.cancel()` in `handleAgentComplete()` before `agentRunner.clear()`.

---

### [MEDIUM] `channelCloseScheduled` is never reset for Hot Idle follow-up

**Lines**: 218, 537-546
**Problem**: `channelCloseScheduled` is an `AtomicBoolean` initialized once to `false`. It is set to `true` in `closeChannelWithDelay()`, which is called from `handleShutdown()`. This is fine. However, the field is *never* reset. If a session enters Hot Idle, then receives a follow-up, then eventually shuts down, `handleShutdown()` calls `closeChannelWithDelay()` which will succeed (CAS false->true).

But consider: if `handleShutdown()` is somehow called twice despite the idempotency guard (e.g., coroutine scheduling edge case where the timeout coroutine and an explicit shutdown both pass the state check before either sets `Shutdown`), the second call would be blocked by the idempotency guard at line 455. So this is actually safe.

However, conceptually `channelCloseScheduled` serves no useful purpose in the Hot Idle world since `closeChannelWithDelay()` is only called from `handleShutdown()` which is already idempotent. The `AtomicBoolean` is redundant defense -- not a bug, but dead complexity.

**Fix**: Consider removing `channelCloseScheduled` since `handleShutdown()` already has an idempotency guard. Or keep it as belt-and-suspenders. Low priority.

---

### [MEDIUM] Idle timeout fires `handleShutdown()` with `CompletionReason.INTERRUPTED` -- misleading

**Lines**: 479-482, 521-527
**Problem**: When idle timeout triggers `handleShutdown()`, the `previousState` is `SessionState.Idle`. The `when` expression maps this to `CompletionReason.INTERRUPTED`. An idle timeout is not an "interrupt" -- it is a natural session expiration. The user sees "Session interrupted" in the UI (via `AgentServiceEventHandler` line 116) which is confusing.

**Fix**: Add an explicit branch for `SessionState.Idle` that maps to a more appropriate reason. Consider adding `CompletionReason.IDLE_TIMEOUT` or mapping to `GOAL_ACHIEVED` since the task already completed. Alternatively, map `Idle` to `CompletionReason.USER_STOPPED` since the session was not forcibly interrupted.

---

### [MEDIUM] No test for Hot Idle follow-up or idle timeout

**Lines**: N/A (missing code)
**Problem**: The existing `AgentSessionTest.kt` only tests `shutdown from running` and `session lifecycle remains stable for all agent modes`. Neither test exercises:
1. Task completion -> Idle -> follow-up UserInput -> Running
2. Task completion -> Idle -> timeout -> Shutdown
3. Task completion -> Idle -> explicit Shutdown (cancel timeout race)
4. Follow-up when `platform.start()` throws

These are the critical paths of this refactor and have zero test coverage.

**Fix**: Add unit tests for the Hot Idle lifecycle transitions. These are testable with the existing `buildSession()` helper and `runTest` with `advanceTimeBy()`.

---

### [LOW] `completionEmitted` removal is correct but `SessionCompleted` is now unconditionally emitted from `handleShutdown`

**Lines**: 483-490
**Problem**: With `completionEmitted` removed, `SessionCompleted` is emitted exactly once because `handleShutdown()` has the idempotency guard (line 455). This is correct. However, the downstream `AgentServiceEventHandler` at line 120 calls `sessionCleared()` which sets `session = null` in `AgentService`. This means after idle timeout, the `AgentService` forgets the session. If `MainActivity.currentSession` still holds a reference, there is a reference mismatch between `AgentService.session` and `MainActivity.currentSession`. This is not new behavior (the old code had the same pattern), but worth documenting.

**Fix**: Document this intentional behavior divergence, or ensure `MainActivity` also clears its `currentSession` reference on `SessionCompleted` event.

---

### [LOW] Magic number 300_000L for idle timeout

**Lines**: 44
**Problem**: The constant `IDLE_TIMEOUT_MS = 300_000L` is well-named and documented in the companion object. This is acceptable. However, it is not configurable at runtime or via `SessionConfig`, which limits operational flexibility for testing or different deployment scenarios.

**Fix**: Consider making the idle timeout configurable via `SessionConfig` for testability (e.g., `config.idleTimeoutMs`).

---

## CODE REVIEW: `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt`

### [MEDIUM] `onTaskCompleted` callback is now a no-op log statement

**Lines**: 114-116
**Problem**: The `onTaskCompleted` callback in `ChatViewModel` is now just `Log.d(TAG, "Task completed; session remains in Idle for follow-up")`. This callback propagates from `ChatEventReducer.handleTaskCompleted()` to the `MainActivity`. Since it does nothing, it adds noise to the architecture -- a lambda is allocated and called on every task completion just to log.

**Fix**: Either:
- Remove the `onTaskCompleted` parameter from `ChatViewModel` entirely if it serves no purpose, OR
- Keep it as a hook for future use but add a comment explaining the intent.

---

### [LOW] `ensureSessionAndSend` state check at line 312 uses `when` with `else` branch for Idle/Created

**Lines**: 312-324
**Problem**: When `shouldCreate` is false and `active != null`, the `when` expression handles `Shutdown` and `Running/Paused` explicitly, but `Idle` and `Created` fall through to `else` which calls `active.submit(Op.UserInput(text))`. This is correct behavior (Idle sessions should accept UserInput), but the intent is not obvious from the code.

**Fix**: Add `SessionState.Idle, SessionState.Created` as explicit branches for clarity.

---

## CODE REVIEW: `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt`

### CLEAN

The change at line 189 correctly removes the `Completed` check, allowing `sendMessage()` to submit `Op.UserInput` to sessions in `Idle` state. The session's own `handleUserInput()` handles state validation.

**Verdict**: CLEAN

---

## Cross-Cutting Analysis

### 1. Remaining references to `SessionState.Completed`

**Result**: CLEAN. Grep across the entire `app/src/main/kotlin` tree confirms zero references to `SessionState.Completed`. The only `Completed` references are to unrelated types (`LLMStreamEvent.Completed`, `TaskCompleted`, `SessionCompleted`, `SubAgentCompleted`, etc.).

### 2. Thread safety in idle timeout mechanism

**Result**: HIGH risk (documented above). The `idleTimeoutJob` var has no synchronization. In practice, Android's `Dispatchers.Main` serializes access, but this is an implicit contract, not a structural guarantee.

### 3. Edge case: shutdown races with follow-up

**Scenario**: User sends follow-up at T=299,999ms (1ms before timeout). Timeline:
1. `handleUserInput()` enters, checks `_state.value == Idle` (true)
2. `cancelIdleTimeout()` cancels the job
3. `services.platform.start()` begins

But what if the timeout coroutine's `handleShutdown()` is already *dispatched* (queued) on the main looper? Since the idle timeout job runs `handleShutdown()` which is a `suspend fun`, and `cancelIdleTimeout()` calls `job.cancel()`, the coroutine will be cancelled before `handleShutdown()` executes (structured concurrency guarantee -- `delay()` is a cancellation point, and after `delay()` completes, the next suspension point in `handleShutdown()` will check for cancellation).

However, `handleShutdown()` itself has no suspension points before `_state.value = SessionState.Shutdown` (line 463). If `delay()` has already returned and `handleShutdown()` is mid-execution, `cancel()` on the job will not take effect until the next suspension point. This means there is a **narrow window** where:
1. Idle timeout's `delay()` returns
2. `handleShutdown()` starts executing, sets `_state.value = Shutdown`
3. Meanwhile, on the same dispatcher, `handleUserInput()` has just passed the `_state.value == Idle` check but hasn't set state to `Running` yet

Since both run on `Dispatchers.Main` (single-threaded), this interleaving **cannot** actually happen -- coroutines on the same single-threaded dispatcher do not preempt each other. But this depends entirely on the scope using `Dispatchers.Main`.

**Result**: Safe in practice on Android due to `Dispatchers.Main` serialization. Would be a race condition if the scope were multi-threaded.

### 4. Resource lifecycle correctness

**handleAgentComplete() releases**: `agentRunner.clear()`, `services.platform.stop()`
**handleAgentComplete() keeps**: `historyManager`, `sessionState`, `llmClient`, `recordingService`, `traceRecorder`, mutation listeners
**handleShutdown() releases**: Everything via `services.cleanup()` (which calls `platform.stop()`, `historyManager.clear()`, `llmClient.cleanup()`, `llmClientFactory.cleanupAll()`, `traceRecorder.close()`, `toolRouter.cancelAll()`, `userResponseChannel.cancel()`)

**Issue**: `services.platform.stop()` is called in `handleAgentComplete()` (line 364), and then `services.cleanup()` calls `platform.stop()` again in `handleShutdown()` (line 477 -> `SessionServices.cleanup()` line 183). The `AndroidPlatform.stop()` contract says "Must be idempotent" (line 29 of AndroidPlatform.kt), so this double-call is safe.

**Issue**: After Hot Idle follow-up, `services.platform.start()` is called (line 291). If the follow-up task completes, `platform.stop()` is called again (line 364). This start/stop cycle is correct.

**Result**: Resource lifecycle is correct. The double-stop is safe due to the idempotency contract on `AndroidPlatform.stop()`.

### 5. AgentService references to Completed

**Result**: CLEAN. `AgentService.kt` does not reference `SessionState.Completed` anywhere. The `AgentServiceEventHandler` handles `SessionCompleted` (the event, not the state), which is correct and unchanged.

### 6. Missing error handling

**Noted**: If `platform.start()` fails during Hot Idle follow-up (line 291), the session remains in `Idle` state and the user gets a status message. This is acceptable but note that the idle timeout is already cancelled at line 289 and **not rescheduled** if `platform.start()` fails. This means the session will remain in Idle forever with no timeout, leaking memory until the activity is destroyed.

---

## Summary

| Severity | Count | Items |
|----------|-------|-------|
| CRITICAL | 0 | -- |
| HIGH     | 2 | idleTimeoutJob thread safety; mutation listener risk undocumented |
| MEDIUM   | 4 | userResponseChannel not cancelled on Idle; channelCloseScheduled redundant; INTERRUPTED reason misleading for idle timeout; no tests for Hot Idle |
| LOW      | 3 | SessionCompleted clears AgentService.session but not MainActivity.currentSession; magic number not configurable; onTaskCompleted is no-op |

## Recommendation: **CHANGES REQUESTED**

The two HIGH issues should be addressed before merging:
1. Either formally document the single-threaded dispatcher contract or add synchronization to `idleTimeoutJob`.
2. Document or mitigate the mutation listener behavior during Idle to prevent checkpoint state corruption.

The MEDIUM issue about missing tests deserves special attention -- this refactor introduces a fundamentally new lifecycle path (Hot Idle) with zero test coverage. Post-merge test debt this large on session lifecycle code is risky.

The `CompletionReason.INTERRUPTED` for idle timeout is a UX issue that will confuse users. Consider addressing it before shipping.

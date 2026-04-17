# Error Resilience Improvement Plan

Prioritized by impact (P0 = fix now, P1 = fix soon, P2 = improve later).

---

## P0: Fix Now

### 1. Make session file writes atomic

**File**: `history/storage/SessionStorage.kt` -- `writeSession()`

**Problem**: Direct file write risks corruption on process death. `writeSnapshot()` already has the correct pattern.

**Fix**: Apply the same temp+rename pattern from `writeSnapshot()`:

```kotlin
suspend fun writeSession(fileName: String, record: SessionRecord): Result<Unit> = withContext(ioDispatcher) {
    try {
        val dir = getSessionsDir()
        val target = File(dir, fileName)
        val tmp = File(dir, "$fileName.tmp")
        val jsonString = json.encodeToString(record)
        tmp.writeText(jsonString)
        if (!tmp.renameTo(target)) {
            target.writeText(jsonString)
            tmp.delete()
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to write session $fileName", e)
        Result.failure(e)
    }
}
```

**Effort**: Small (copy pattern from `writeSnapshot`).

---

### 2. Remove `runBlocking` from `AgentService.onDestroy()`

**File**: `app/AgentService.kt:213-224`

**Problem**: `runBlocking` on the main thread can cause ANR if the shutdown takes time.

**Fix**: Fire-and-forget the shutdown. The session will be cleaned up by scope cancellation anyway. If checkpoint persistence matters, it's already handled by `SessionCheckpointCoordinator.flushClosed()` which runs inside the session's own scope before `onComplete` fires.

```kotlin
override fun onDestroy() {
    isServiceActive = false
    instance = null
    eventCollectorJob?.cancel()
    eventCollectorJob = null

    val currentSession = session
    session = null
    // Non-blocking: session shutdown races with scope.cancel() below.
    // The session handles its own checkpoint persistence.
    currentSession?.let { scope.launch { it.submit(Op.Shutdown) } }

    overlayController?.dispose()
    // ... rest of cleanup ...
    scope.cancel()
}
```

**Risk**: Shutdown may not complete. Mitigated by the fact that `scope.cancel()` will cancel the agent job anyway, and `deliverCompletion` uses `NonCancellable` to persist the completion.

**Effort**: Small.

---

## P1: Fix Soon

### 3. Increase agent recoverable retry budget

**File**: `agent/Agent.kt:29`

**Problem**: `MAX_RECOVERABLE_RETRIES = 1` means two consecutive transient errors kill the session.

**Fix**: Increase to 2 or 3. The LLM-level retry (5 attempts with backoff) handles most transient issues, so the agent-level retry is a last resort. But on flaky mobile networks, 1 is too aggressive.

```kotlin
private const val MAX_RECOVERABLE_RETRIES = 3
```

**Effort**: Trivial.

---

### 4. Add context-length error detection with user-friendly message

**File**: `agent/TurnErrorClassifier.kt`

**Problem**: Context-length errors surface raw API messages to the user.

**Fix**: In `TurnErrorClassifier.classify()`, when `isContextLimit` is true, return a user-friendly message:

```kotlin
val isContextLimit = /* existing detection */

if (isContextLimit) {
    return TurnErrorClassification(
        message = "Conversation too long for model context window. " +
            "Try starting a new task or reducing the number of turns.",
        recoverable = false
    )
}
```

**Effort**: Small.

---

### 5. Remove dead `AgentError` infrastructure

**Files**: `protocol/AgentError.kt`, `protocol/SessionLifecycleEvents.kt` (SessionError), `ui/chat/ChatEventReducer.kt` (handleError), `app/AgentServiceEventHandler.kt` (SessionError handling)

**Problem**: `AgentError` sealed class (11 variants), `AgentError.from()`, and `SessionError` event are dead code. They were designed as part of a structured error system that was superseded by the simpler `TurnErrorClassifier` + `AgentStopReason.Error(message)` pattern.

**Fix**: Either:
- (a) **Delete**: Remove `AgentError.kt`, remove `SessionError` event, remove handlers. The runtime error path (`TurnErrorClassifier` -> `TurnOutcome.Error` -> `AgentStopReason.Error`) is the canonical path.
- (b) **Integrate**: Wire `AgentError` into the runtime path, replacing `TurnErrorClassifier`. This would give richer error types to the UI layer.

**Recommendation**: Option (a). The current system works. Adding structured error types would be over-engineering given the agent's error handling is fundamentally "log it, tell the user, stop or retry."

**Effort**: Medium (need to verify no other references, update imports).

---

### 6. Add log to `completeSession()` null-session guard

**File**: `history/SessionRecordingService.kt:208`

**Problem**: `currentSession ?: return` silently skips session completion with no log.

**Fix**:

```kotlin
val session = currentSession ?: run {
    Log.w(TAG, "completeSession called with no active session")
    return
}
```

**Effort**: Trivial.

---

## P2: Improve Later

### 7. Unify error classification into one path

**Problem**: Three classifiers (`AgentError.from`, `TurnErrorClassifier`, `OpenAIErrorClassifier`) with overlapping logic.

**Fix** (if doing P1.5 option a): After removing `AgentError`, consider merging `OpenAIErrorClassifier` output types (`RateLimitException`, `TransientException`, `RuntimeException`) with `TurnErrorClassifier` input expectations. Today they work in series (OpenAI classifier -> exception -> Turn classifier), which is fine but the two-hop classification means the same error (e.g., "timeout") is classified twice with slightly different logic.

**Effort**: Medium. Low urgency since the current two-hop system works correctly.

---

### 8. Consider longer approval timeout for eval/debug-run mode

**File**: `tool/ToolRouter.kt:38`

**Problem**: 60-second approval timeout always fires in automated eval runs where no human approves.

**Fix**: Make the timeout configurable via `SessionConfig` or `AgentExecutionConfig`:

```kotlin
private val approvalTimeoutMs: Long = config.approvalTimeoutMs ?: 60_000L
```

For eval runs, set to a shorter value (5 seconds) or bypass approval entirely via `ApprovalMode.ALWAYS_ALLOW`.

**Effort**: Small, but requires plumbing config through to `ToolRouter`.

---

### 9. Stream partial-output-then-failure resilience

**File**: `llm/CloudStreamRetryPolicy.kt:24-29`, `agent/Turn.kt:122-126`

**Problem**: If the stream fails after emitting text + tool calls, the partial tool calls are lost. The text was already sent to the UI. The retry framework correctly refuses to retry (to avoid duplicate text), but the collected-but-not-yet-emitted tool calls disappear.

**Fix**: This is hard to fix correctly because:
- Re-emitting partial tool calls risks acting on incomplete data
- The LLM may have intended more tool calls that never arrived
- The safest action is to fail the turn (current behavior)

**Recommendation**: No code change needed. The current behavior (fail the turn, let agent-level retry re-run the full LLM call) is the safest option. Document the design decision.

**Effort**: None (documentation only).

---

## Summary

| # | Priority | Description | Effort | Risk |
|---|----------|-------------|--------|------|
| 1 | P0 | Atomic session file writes | Small | Data loss on crash |
| 2 | P0 | Remove `runBlocking` from `onDestroy()` | Small | ANR risk |
| 3 | P1 | Increase recoverable retry budget | Trivial | Session death on flaky network |
| 4 | P1 | User-friendly context-length error | Small | Poor UX |
| 5 | P1 | Remove dead `AgentError` / `SessionError` | Medium | Dead code confusion |
| 6 | P1 | Log null-session in `completeSession()` | Trivial | Silent failure |
| 7 | P2 | Unify error classification | Medium | Code clarity |
| 8 | P2 | Configurable approval timeout | Small | Eval-only issue |
| 9 | P2 | Document stream partial-failure design | None | N/A |

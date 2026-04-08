# Error Resilience Improvement Plan — Final

Prioritized by impact (P0 = fix now, P1 = fix soon, P2 = improve later).
**Base**: Codex improvement plan + Claude items. Agreed via double-design alignment.

---

## P0: Fix Now

### 1. Make task completion depend on executed tool results

**Files**: `agent/AgentTurnRunner.kt`, `agent/TurnExecutionPhaseRunner.kt`, `agent/cognition/policy/TurnToolPolicy.kt`, `agent/AgentRuntimeTypes.kt`
**Tests**: `TurnToolPolicyTest.kt`, `AgentErrorRecoveryTest.kt`

**Problem**: `decideCompletion()` uses the planned tool list, not the executed result. If a cognitive tool fails before `complete_task`, the turn can still be reported as complete.

**Fix**: Return structured execution data from `TurnExecutionPhaseRunner`:

```kotlin
internal data class ExecutionPhaseResult(
    val actionSignature: String?,
    val executedToolIds: Set<String>,
    val terminalResult: ToolCallResult? = null
)
```

`AgentTurnRunner` should only emit `TurnOutcome.Complete` when:
- `complete_task` was selected in the plan
- `complete_task` is present in `executedToolIds`
- no earlier tool returned `Error` or `Cancelled`

Otherwise return `TurnOutcome.Error` / `TurnOutcome.Cancelled` from the real execution result.

**Effort**: Medium.

---

### 2. Fail fast when approval UI dispatch breaks

**Files**: `tool/ToolRouter.kt`, `agent/TurnExecutionPhaseRunner.kt`
**Tests**: `ToolRouterTest.kt`

**Problem**: `emitApprovalRequired()` catches and suppresses emitter failures. That turns a broken approval-notification path into a fake 60-second user timeout.

**Fix**: Let approval notification failures propagate back into `ToolRouter.execute()`:

```kotlin
private suspend fun emitApprovalRequired(details: ApprovalDetails) {
    eventEmitter(
        ApprovalRequired(
            sessionId = config.sessionId,
            timestamp = System.currentTimeMillis(),
            actionId = details.callId,
            description = details.description,
            details = details
        )
    )
}
```

If `eventEmitter` throws, `ToolRouter` returns `ToolCallResult.Error("Approval request failed: ...")`, not `ToolCallResult.Cancelled("Approval timed out")`.

**Effort**: Small.

---

### 3. Classify `ask_user` as non-screen-changing

**Files**: `tool/ToolName.kt`, `tool/PolicyEngine.kt`, `tool/impl/AskUserTool.kt`
**Tests**: `PolicyEngineTest.kt`

**Problem**: `ask_user` falls through to `ToolName.Unknown`, treated as screen-changing. Can require approval before asking for help, or be denied in blocked apps.

**Fix**: Add `ToolName.AskUser` to the canonical list, mark `isScreenChanging = false`. Also add any other shipped non-UI tools using the `Unknown` fallback (e.g., `shell`).

**Effort**: Small.

---

### 4. Remove `runBlocking` from `AgentService.onDestroy()`

**Files**: `app/AgentService.kt:213-224`

**Problem**: `runBlocking` on the main thread can block for up to 5 seconds, risking ANR during service restart.

**Fix**: Fire-and-forget shutdown. The session handles its own checkpoint persistence, and `scope.cancel()` will cancel in-flight work. `deliverCompletion` uses `NonCancellable` to persist completion.

```kotlin
override fun onDestroy() {
    isServiceActive = false
    instance = null
    eventCollectorJob?.cancel()
    eventCollectorJob = null

    val currentSession = session
    session = null
    currentSession?.let { scope.launch { it.submit(Op.Shutdown) } }

    overlayController?.dispose()
    // ... rest of cleanup ...
    scope.cancel()
}
```

**Effort**: Small.

---

## P1: Fix Soon

### 5. Preserve action outcome semantics end to end

**Files**: `protocol/ActionEvents.kt`, `agent/TurnExecutionPhaseRunner.kt`, `app/AgentServiceEventHandler.kt`, `ui/chat/ChatEventReducer.kt`, `history/model/MessageConverter.kt`
**Tests**: `ChatActionExecutionMappingTest.kt`, `SessionRecordingServiceTest.kt`

**Problem**: `ActionExecuted(success: Boolean)` collapses cancellation, denial, timeout, and failure into the same bucket. `"✓ executed"` status shown for failures.

**Fix**: Replace boolean with explicit status enum:

```kotlin
enum class ActionExecutionStatus {
    SUCCESS,
    FAILED,
    CANCELLED,
    SKIPPED
}
```

Mapping:
- `ToolCallResult.Success` → `SUCCESS`
- `ToolCallResult.Error` → `FAILED`
- `ToolCallResult.Cancelled("User denied"|"Approval timed out"|"Blocked app...")` → `SKIPPED`
- User-initiated abort → `CANCELLED`

Only emit success checkmark for `SUCCESS`.

**Effort**: Medium.

---

### 6. Split `TASK_IMPOSSIBLE` from internal `ERROR`

**Files**: `agent/Agent.kt`, `agent/AgentRuntimeTypes.kt`, `session/AgentSession.kt`
**Tests**: `AgentSessionTest.kt`, `CompleteTaskToolTest.kt`

**Problem**: `complete_task(status = "failure")` becomes `AgentStopReason.Error`, so `CompletionReason.TASK_IMPOSSIBLE` is never produced.

**Fix**: Add dedicated stop reason:

```kotlin
sealed class AgentStopReason {
    data class GoalAchieved(val message: String = "Goal achieved") : AgentStopReason()
    data class TaskImpossible(val message: String) : AgentStopReason()
    data object UserRequested : AgentStopReason()
    data object MaxTurnsReached : AgentStopReason()
    data class Error(val message: String) : AgentStopReason()
}
```

`Agent.kt` converts unsuccessful `TurnOutcome.Complete` → `TaskImpossible`. `AgentSession.toCompletionReason()` maps to `CompletionReason.TASK_IMPOSSIBLE`.

**Effort**: Medium.

---

### 7. Make typed error envelope authoritative

**Files**: `protocol/AgentError.kt`, `protocol/TaskLifecycleEvents.kt`, `protocol/SessionLifecycleEvents.kt`, `agent/TurnErrorClassifier.kt`, `agent/AgentRuntimeTypes.kt`, `session/AgentSession.kt`, `app/AgentServiceEventHandler.kt`, `ui/chat/ChatEventReducer.kt`

**Problem**: `AgentError` exists but live execution strips to strings. Duplicated logic and dead protocol surface.

**Fix**: Trim `AgentError` to categories the runtime actually produces, then carry it through `TurnOutcome.Error`, `AgentStopReason.Error`, and terminal event payloads:

```kotlin
data class TaskCompleted(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val taskId: String,
    val result: String?,
    val reason: CompletionReason,
    val error: AgentError? = null
) : TaskLifecycleEvent
```

UI renders `error?.message`; no string-inference of categories. Remove `SessionError` if redundant after this change.

**Effort**: Large.

---

### 8. Return structural failure from `delegate_task`

**Files**: `tool/impl/DelegateTaskTool.kt`
**Tests**: `DelegateTaskToolTest.kt`

**Problem**: Failed sub-agent runs returned via `textToolSuccess(...)`, so parent control flow and action cards treat them as successful.

**Fix**: When `result.success` is false, return `ToolExecutionResult.Failure("Sub-agent failed: ...")`. Reserve success only for successful delegation.

**Effort**: Small.

---

### 9. Harden cleanup and observation fallback

**Files**: `session/SessionServices.kt`, `agent/TurnExecutionPhaseRunner.kt`
**Tests**: `TurnExecutionPhaseRunnerActionSignatureTest.kt`

**Problem**: Teardown stops at first thrown cleanup call. Post-failure observation capture can escalate local tool failure to turn failure.

**Fix**:
- Wrap each cleanup step independently (`platform.stop()`, `llmClient.cleanup()`, `llmClientFactory.cleanupAll()`, `traceRecorder.close()`)
- Wrap `captureObservationWithSnapshot()` in `runCatching`; on failure, fall back to `ToolObservation.TextOutput(result.toContextString())`

**Effort**: Small-Medium.

---

### 10. Increase agent recoverable retry budget

**Files**: `agent/Agent.kt:29`

**Problem**: `MAX_RECOVERABLE_RETRIES = 1` means two consecutive transient errors kill the session. Too aggressive for mobile networks.

**Fix**:

```kotlin
private const val MAX_RECOVERABLE_RETRIES = 3
```

The LLM-level retry (5 attempts with backoff) handles most transient issues. The agent-level retry is a last resort, but on flaky mobile networks, 1 is insufficient.

**Effort**: Trivial.

---

### 11. User-friendly context-length error message

**Files**: `agent/TurnErrorClassifier.kt`

**Problem**: Context-length errors surface raw API messages with no actionable guidance.

**Fix**:

```kotlin
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

### 12. Log null-session guard in `completeSession()`

**Files**: `history/SessionRecordingService.kt:207`

**Problem**: `currentSession ?: return` silently skips session completion with no log. All other guards in the class log warnings.

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

### 13. Make session writes atomic and surface corrupted history

**Files**: `history/storage/SessionStorage.kt`, `history/SessionHistoryManager.kt`, `ui/chat/ChatSessionHistoryController.kt`
**Tests**: `SessionStorageTest.kt`, `SessionHistoryManagerTest.kt`

**Problem**: `writeSession()` writes directly while `writeSnapshot()` is atomic. Corrupted files silently drop from session list.

**Fix**: Use temp+rename for `writeSession()`. When `extractSessionInfo()` can't parse, surface a visible placeholder or explicit message instead of silently omitting.

**Effort**: Medium.

---

### 14. Make cancellation exception-safe

**Files**: `agent/AgentTurnRunner.kt`, `agent/Turn.kt`, `session/SessionAgentRunner.kt`
**Tests**: `AgentErrorRecoveryTest.kt`, `AgentSessionTest.kt`

**Problem**: Broad `catch (Exception)` blocks can convert cooperative cancellation into generic error completion.

**Fix**: Add explicit `catch (e: CancellationException) { throw e }` before generic catches. Keep `SessionAgentRunner`'s user-request path separate from unexpected cancellation.

**Effort**: Small.

---

### 15. Configurable approval timeout for eval/debug-run

**Files**: `tool/ToolRouter.kt:38`

**Problem**: 60-second approval timeout always fires in automated eval runs.

**Fix**: Make timeout configurable via `SessionConfig` or `AgentExecutionConfig`:

```kotlin
private val approvalTimeoutMs: Long = config.approvalTimeoutMs ?: 60_000L
```

**Effort**: Small (requires plumbing config to `ToolRouter`).

---

### 16. Document stream partial-failure design

**Files**: `llm/CloudStreamRetryPolicy.kt`, `agent/Turn.kt`

**Problem**: Stream failure after partial output discards collected tool calls. Current behavior is safest.

**Fix**: Document the design decision — no code change needed. Failing the turn and re-running via agent-level retry is the correct approach because re-emitting partial tool calls risks acting on incomplete data.

**Effort**: None (documentation only).

---

### 17. Improve bootstrap/session failure UX

**Files**: `session/SessionCoordinator.kt`, `app/AgentService.kt`, `ui/main/MainActivity.kt`

**Problem**: Session creation failures show only toast/overlay status. User's initial input text is lost. Chat UI doesn't surface the error.

**Fix**:
- Preserve user's initial text when session creation fails
- Surface startup failure through chat/session UX, not only toast/status
- Keep retry/reload behavior explicit

**Effort**: Medium.

---

## Summary

| # | Priority | Description | Effort | Risk |
|---|----------|-------------|--------|------|
| 1 | P0 | Completion depends on executed `complete_task` | Medium | False success/failure |
| 2 | P0 | Approval dispatch failures fail fast | Small | Fake user timeout |
| 3 | P0 | `ask_user` non-screen-changing | Small | User handoff blocked |
| 4 | P0 | Remove `runBlocking` from `onDestroy()` | Small | ANR risk |
| 5 | P1 | Action outcome semantics end to end | Medium | Misleading UI/history |
| 6 | P1 | Map failed completion to `TASK_IMPOSSIBLE` | Medium | Error types conflated |
| 7 | P1 | Make typed errors authoritative | Large | Dead code + inconsistency |
| 8 | P1 | `delegate_task` returns structural failure | Small | Failed = success |
| 9 | P1 | Harden cleanup and observation fallback | Small-Med | Brittle failure handling |
| 10 | P1 | Increase recoverable retry budget | Trivial | Session death on flaky net |
| 11 | P1 | Context-length user-friendly message | Small | Poor UX |
| 12 | P1 | Log null-session in `completeSession()` | Trivial | Silent skip |
| 13 | P2 | Atomic session writes + corruption UX | Medium | Session loss |
| 14 | P2 | CancellationException safety | Small | Stop misreported as error |
| 15 | P2 | Configurable approval timeout | Small | Eval-only |
| 16 | P2 | Document stream partial-failure | None | N/A |
| 17 | P2 | Bootstrap/session failure UX | Medium | Lost user input |

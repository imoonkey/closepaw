# Error Resilience Improvement Plan

Prioritized by impact (P0 = fix now, P1 = fix soon, P2 = improve later).

---

## P0: Fix Now

### 1. Make task completion depend on executed tool results

**Files**: `agent/AgentTurnRunner.kt`, `agent/TurnExecutionPhaseRunner.kt`, `agent/cognition/policy/TurnToolPolicy.kt`, `agent/AgentRuntimeTypes.kt`

**Tests**: `app/src/test/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicyTest.kt`, `app/src/test/kotlin/com/moonkey/androidagent/agent/AgentErrorRecoveryTest.kt`

**Problem**: `decideCompletion()` uses the planned tool list, not the executed result. If a cognitive tool fails before `complete_task`, the turn can still be reported as complete even though `complete_task` never ran.

**Fix**: Return structured execution data from `TurnExecutionPhaseRunner` and make completion contingent on `complete_task` actually executing successfully.

```kotlin
internal data class ExecutionPhaseResult(
    val actionSignature: String?,
    val executedToolIds: Set<String>,
    val terminalResult: ToolCallResult? = null
)
```

`AgentTurnRunner` should only emit `TurnOutcome.Complete` when:
- `complete_task` was selected
- `complete_task` is present in `executedToolIds`
- no earlier tool returned `Error` or `Cancelled`

Otherwise return `TurnOutcome.Error` / `TurnOutcome.Cancelled` from the real execution result.

**Effort**: Medium.

---

### 2. Fail fast when approval UI dispatch breaks

**Files**: `tool/ToolRouter.kt`, `agent/TurnExecutionPhaseRunner.kt`

**Tests**: `app/src/test/kotlin/com/moonkey/androidagent/tool/ToolRouterTest.kt`

**Problem**: `emitApprovalRequired()` catches and suppresses emitter failures. That turns a broken approval-notification path into a fake 60-second user timeout.

**Fix**: Let approval notification failures propagate back into `ToolRouter.execute()` and terminate the tool call immediately.

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

If `eventEmitter` throws, `ToolRouter` should return `ToolCallResult.Error("Approval request failed: ...")`, not `ToolCallResult.Cancelled("Approval timed out")`.

**Effort**: Small.

---

### 3. Classify `ask_user` as a non-screen-changing tool

**Files**: `tool/ToolName.kt`, `tool/PolicyEngine.kt`, `tool/impl/AskUserTool.kt`

**Tests**: `app/src/test/kotlin/com/moonkey/androidagent/tool/PolicyEngineTest.kt`

**Problem**: `ask_user` falls through to `ToolName.Unknown`, so it is treated like a screen-changing action. That can force approval before asking the user for help, or deny the tool entirely in blocked apps.

**Fix**: Add `ToolName.AskUser` to the canonical list and mark it `isScreenChanging = false`. While touching `ToolName`, also add any other shipped non-UI tools that currently rely on the `Unknown` fallback (for example `shell`, if that tool remains registered).

That keeps `PolicyEngine` simple:

```kotlin
if (!ToolName.from(toolName).isScreenChanging) return PolicyDecision.Allow
```

**Effort**: Small.

---

## P1: Fix Soon

### 4. Preserve action outcome semantics end to end

**Files**: `protocol/ActionEvents.kt`, `agent/TurnExecutionPhaseRunner.kt`, `app/AgentServiceEventHandler.kt`, `ui/chat/ChatEventReducer.kt`, `history/model/MessageConverter.kt`, `ui/chat/model/ChatMessage.kt`

**Tests**: `app/src/test/kotlin/com/moonkey/androidagent/ui/chat/ChatActionExecutionMappingTest.kt`, `app/src/test/kotlin/com/moonkey/androidagent/history/SessionRecordingServiceTest.kt`

**Problem**: `ActionExecuted(success: Boolean)` collapses cancellation, policy denial, user denial, approval timeout, and execution failure into the same bucket. `TurnExecutionPhaseRunner` also emits `"✓ <tool> executed"` for every non-success case.

**Fix**: Replace the boolean with a small explicit status enum that the UI/history layers persist directly.

```kotlin
enum class ActionExecutionStatus {
    SUCCESS,
    FAILED,
    CANCELLED,
    SKIPPED
}
```

Suggested mapping:
- `ToolCallResult.Success` -> `SUCCESS`
- `ToolCallResult.Error` -> `FAILED`
- `ToolCallResult.Cancelled("User denied" | "Approval timed out" | "Blocked app..." )` -> `SKIPPED`
- user-initiated abort/cancellation -> `CANCELLED`

Only emit the success checkmark for `SUCCESS`.

**Effort**: Medium.

---

### 5. Split `TASK_IMPOSSIBLE` from internal `ERROR`

**Files**: `agent/Agent.kt`, `agent/AgentRuntimeTypes.kt`, `session/AgentSession.kt`

**Tests**: `app/src/test/kotlin/com/moonkey/androidagent/session/AgentSessionTest.kt`, `app/src/test/kotlin/com/moonkey/androidagent/tool/impl/CompleteTaskToolTest.kt`

**Problem**: `complete_task(status = "failure")` currently becomes `AgentStopReason.Error`, so the session always ends as `CompletionReason.ERROR` even though the protocol already has `TASK_IMPOSSIBLE`.

**Fix**: Add a dedicated runtime stop reason and map it directly.

```kotlin
sealed class AgentStopReason {
    data class GoalAchieved(val message: String = "Goal achieved") : AgentStopReason()
    data class TaskImpossible(val message: String) : AgentStopReason()
    data object UserRequested : AgentStopReason()
    data object MaxTurnsReached : AgentStopReason()
    data class Error(val message: String) : AgentStopReason()
}
```

`Agent.kt` should convert unsuccessful `TurnOutcome.Complete` into `TaskImpossible`, and `AgentSession.toCompletionReason()` should map that to `CompletionReason.TASK_IMPOSSIBLE`.

**Effort**: Medium.

---

### 6. Make one typed error envelope authoritative

**Files**: `protocol/AgentError.kt`, `protocol/TaskLifecycleEvents.kt`, `protocol/SessionLifecycleEvents.kt`, `agent/TurnErrorClassifier.kt`, `agent/AgentRuntimeTypes.kt`, `session/AgentSession.kt`, `app/AgentServiceEventHandler.kt`, `ui/chat/ChatEventReducer.kt`

**Problem**: `AgentError` exists, but live execution strips failures down to strings before session and UI layers see them. The result is duplicated logic and dead protocol surface area.

**Fix**: Keep this small and real. Trim `AgentError` to the categories the runtime actually produces, then carry it through `TurnOutcome.Error`, `AgentStopReason.Error`, and the terminal task/session event payload.

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

UI code should render `error?.message`; it should not infer categories from raw strings.

**Recommendation**: Do not preserve all 11 current `AgentError` variants just because they already exist. A smaller authoritative model is better than a rich dead one. If `SessionError` remains redundant after this change, remove it.

**Effort**: Large.

---

### 7. Return structural failure from `delegate_task`

**Files**: `tool/impl/DelegateTaskTool.kt`

**Tests**: `app/src/test/kotlin/com/moonkey/androidagent/tool/impl/DelegateTaskToolTest.kt`

**Problem**: failed sub-agent runs are returned via `textToolSuccess(...)`, so parent control flow and action cards treat them as successful tool executions.

**Fix**: When `result.success` is false, return `ToolExecutionResult.Failure("Sub-agent failed: ...")`. Reserve success only for successful delegation. Keep agent name and child message in `data` if they are useful for prompt context or history.

**Effort**: Small.

---

### 8. Harden cleanup and post-action observation fallback

**Files**: `session/SessionServices.kt`, `agent/TurnExecutionPhaseRunner.kt`

**Tests**: `app/src/test/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunnerActionSignatureTest.kt` or a new dedicated runner test

**Problem**: teardown stops at the first thrown cleanup call, and post-failure observation capture can turn a localized tool problem into a full turn failure.

**Fix**:
- Wrap each cleanup step (`platform.stop()`, `llmClient.cleanup()`, `llmClientFactory.cleanupAll()`, `traceRecorder.close()`) independently and continue.
- Wrap `captureObservationWithSnapshot()` in `runCatching`; if capture fails, keep the original `ToolCallResult` and fall back to `ToolObservation.TextOutput(result.toContextString())` with no new snapshot.

**Effort**: Small to Medium.

---

## P2: Improve Later

### 9. Make session writes atomic and surface corrupted history entries

**Files**: `history/storage/SessionStorage.kt`, `history/SessionHistoryManager.kt`, `ui/chat/ChatSessionHistoryController.kt`

**Tests**: `app/src/test/kotlin/com/moonkey/androidagent/history/SessionStorageTest.kt`, `app/src/test/kotlin/com/moonkey/androidagent/history/SessionHistoryManagerTest.kt`

**Problem**: `writeSession()` writes directly, while `writeSnapshot()` is atomic. If a session file becomes unreadable, it silently drops out of the session list.

**Fix**: Use the same temp-file + rename pattern for `writeSession()`. When `extractSessionInfo()` cannot parse a file, surface a visible placeholder session or at least an explicit UI/log message instead of silently omitting it.

**Effort**: Medium.

---

### 10. Make cancellation exception-safe across wrappers

**Files**: `agent/AgentTurnRunner.kt`, `agent/Turn.kt`, `session/SessionAgentRunner.kt`

**Tests**: `app/src/test/kotlin/com/moonkey/androidagent/agent/AgentErrorRecoveryTest.kt`, `app/src/test/kotlin/com/moonkey/androidagent/session/AgentSessionTest.kt`

**Problem**: broad `catch (Exception)` blocks can convert cooperative cancellation into generic error completion.

**Fix**: Add explicit `catch (e: CancellationException) { throw e }` before generic catches, and keep `SessionAgentRunner`'s user-request path separate from truly unexpected cancellation.

**Effort**: Small.

---

## Summary

| # | Priority | Description | Effort | Risk |
|---|----------|-------------|--------|------|
| 1 | P0 | Completion depends on executed `complete_task` | Medium | False success / false failure |
| 2 | P0 | Approval dispatch failures fail fast | Small | Fake user timeout hides real outage |
| 3 | P0 | `ask_user` no longer treated as screen-changing | Small | User handoff blocked in login/CAPTCHA flows |
| 4 | P1 | Preserve action outcome semantics end to end | Medium | Misleading UI/history |
| 5 | P1 | Map failed completion to `TASK_IMPOSSIBLE` | Medium | Internal error vs impossible task conflated |
| 6 | P1 | Make typed errors authoritative | Large | Dead code and inconsistent error handling |
| 7 | P1 | `delegate_task` returns structural failure | Small | Failed delegation treated as success |
| 8 | P1 | Harden cleanup and observation fallback | Small-Medium | Failure handling is brittle |
| 9 | P2 | Atomic session writes + visible corrupt history handling | Medium | Session loss / silent disappearance |
| 10 | P2 | Propagate cancellation cleanly | Small | User stop misreported as error |

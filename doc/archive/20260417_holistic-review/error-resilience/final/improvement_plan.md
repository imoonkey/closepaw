# Error Resilience Improvement Plan — Final

Prioritized by impact (P0 = fix now, P1 = fix soon, P2 = improve later).
**Revalidated**: 2026-04-15 against current codebase. Items already fixed, not worth it, or needing rethink have been removed or updated.

---

## P0: Fix Now

### 1. Make task completion depend on executed tool results

**Files**: `agent/AgentTurnRunner.kt`, `agent/TurnExecutionPhaseRunner.kt`
**Tests**: `TurnToolPolicyTest.kt`, `AgentErrorRecoveryTest.kt`

**Problem**: `executeActions()` returns `Unit`, so `AgentTurnRunner` decides completion from the planned turn result, not from what actually executed. If a cognitive tool fails before `complete_task`, the turn can still be reported as complete.

**Fix**: Return minimal execution data from `TurnExecutionPhaseRunner` — enough for `AgentTurnRunner` to know whether `complete_task` actually ran and whether execution terminated early.

`AgentTurnRunner` should only emit `TurnOutcome.Complete` when:
- `complete_task` was selected in the plan
- `complete_task` actually executed
- no earlier tool returned `Error` or `Cancelled`

Otherwise return `TurnOutcome.Error` / `TurnOutcome.Cancelled` from the real execution result.

**Effort**: Medium.

---

### 2. Fail fast when approval UI dispatch breaks

**Files**: `agent/TurnExecutionPhaseRunner.kt:164-174`
**Tests**: `ToolRouterTest.kt`

**Problem**: `emitApprovalRequired()` catches and suppresses emitter failures. `ToolRouter` is already prepared to convert the exception into `ToolCallResult.Error(...)`, but never sees it.

**Fix**: Remove the try/catch suppression in `emitApprovalRequired()`. Let the exception propagate so `ToolRouter` returns `ToolCallResult.Error("Approval request failed: ...")` instead of waiting 60 seconds and blaming the user.

**Effort**: Small.

---

### 3. Return structural failure from `delegate_task`

**Files**: `tool/impl/DelegateTaskTool.kt:156-177`
**Tests**: `DelegateTaskToolTest.kt`

**Problem**: Failed sub-agent runs return `textToolSuccess(...)`. Parent turn control flow, action cards, and history all treat failed delegation as successful. With PRO mode as default, this is a core orchestration bug.

**Fix**: When `result.success` is false, return `ToolExecutionResult.Failure("Sub-agent failed: ...")`. Reserve success only for successful delegation.

**Effort**: Small.

---

### 4. Move `onDestroy()` shutdown off main thread

**Files**: `app/AgentService.kt:206-236`

**Problem**: `runBlocking` on main thread can block for up to 5 seconds, risking ANR during service restart.

**Fix**: Move shutdown to a dedicated scope or detached coroutine that is not cancelled by `onDestroy()`. The existing `scope.launch { ... }` + `scope.cancel()` pattern won't work — scope cancellation kills the launched shutdown.

Options:
- Use `GlobalScope.launch(NonCancellable)` with a timeout for the shutdown op
- Create a separate `shutdownScope` that outlives the service scope
- Use `CoroutineScope(SupervisorJob() + Dispatchers.Default)` for shutdown

The session handles its own checkpoint persistence via `NonCancellable`, so partial shutdown is tolerable.

**Effort**: Small-Medium.

---

## P1: Fix Soon

### 5. Split `TASK_IMPOSSIBLE` from internal `ERROR`

**Files**: `agent/Agent.kt`, `agent/AgentRuntimeTypes.kt`, `session/AgentSession.kt`
**Tests**: `AgentSessionTest.kt`, `CompleteTaskToolTest.kt`

**Problem**: `complete_task(status = "failure")` becomes `AgentStopReason.Error`, so `CompletionReason.TASK_IMPOSSIBLE` is never produced. The impossible-vs-internal-fault distinction is lost.

**Fix**: Add `AgentStopReason.TaskImpossible`. Convert unsuccessful `TurnOutcome.Complete` → `TaskImpossible`. Map to `CompletionReason.TASK_IMPOSSIBLE` in `AgentSession`.

**Effort**: Medium.

---

### 6. Harden cleanup and observation fallback

**Files**: `session/SessionServices.kt:210-235`, `agent/TurnExecutionPhaseRunner.kt:176-235`
**Tests**: `TurnExecutionPhaseRunnerActionSignatureTest.kt`

**Problem**: Two separate but related issues:
1. `SessionServices.cleanup()` only guards `platform.stop()`. Other cleanup calls can abort teardown mid-sequence.
2. `captureObservationWithSnapshot()` is called without shielding after tool failures. A local tool failure can escalate to a turn-level failure if screen capture throws.

**Fix** (implement as two independent small fixes):
- Wrap each cleanup step independently with try/catch
- Wrap `captureObservationWithSnapshot()` in `runCatching`; on failure, fall back to text-only observation

**Effort**: Small.

---

### 7. Preserve action outcome semantics end to end

**Files**: `protocol/ActionEvents.kt`, `agent/TurnExecutionPhaseRunner.kt`, `app/AgentServiceEventHandler.kt`, `ui/chat/ChatEventReducer.kt`

**Problem**: `ActionExecuted(success: Boolean)` collapses all non-success outcomes. `"✓ executed"` status shown for failures. `ActionState.Skipped` exists in UI model but is never used.

**Fix**: Replace boolean with a three-value enum derived directly from router results:

```kotlin
enum class ActionOutcome { SUCCESS, FAILED, SKIPPED }
```

- `ToolCallResult.Success` → `SUCCESS`
- `ToolCallResult.Error` → `FAILED`
- `ToolCallResult.Cancelled` (user denied, approval timeout, blocked app) → `SKIPPED`

Only emit success checkmark for `SUCCESS`. No string-matching needed — derive from result type. Leave task/session cancellation to higher-level lifecycle events.

**Effort**: Medium.

---

### 8. Improve bootstrap/session failure UX

**Files**: `session/AgentSession.kt`, `app/MainActivity.kt`, `ui/chat/ChatViewModel.kt`, `ui/chat/ChatEventReducer.kt`

**Problem**: Bootstrap failures surface only as toast/status. User's input disappears if startup fails before `TaskStarted`.

**Fix** (scope tightly):
- Preserve the user's pending input text when session creation fails
- Surface startup failure through main chat/session UX, not only toast/status
- Keep retry/reload behavior explicit

**Effort**: Medium.

---

## P2: Improve Later

### 9. Clean up or replace dead typed error surface

**Files**: `protocol/AgentError.kt`, `protocol/SessionLifecycleEvents.kt`

**Problem**: `AgentError` (11 variants) and `SessionError` are dead protocol surface — never produced by live runtime.

**Fix**: Making the full hierarchy authoritative is too much machinery. Either:
- (a) Delete the dead types entirely — the specific semantic fixes (items #1, #3, #5, #7) already address the concrete problems
- (b) Replace with a much smaller live failure kind (3-4 variants max) used only where structure materially helps

Decide after implementing P0/P1 items, which may reduce the need for a separate error envelope.

**Effort**: Medium (either direction).

---

### 10. Surface corrupted session history entries

**Files**: `history/SessionHistoryManager.kt:237-238, 266-297`, `ui/chat/ChatSessionHistoryController.kt`

**Problem**: Session writes are now atomic. But when `extractSessionInfo()` cannot parse a file, it returns `null` and the manager drops the entry silently. Users get no explanation for missing sessions.

**Fix**: Show a visible placeholder or explicit message for unreadable sessions instead of silently omitting them. Add read-time diagnostics logging.

**Effort**: Small-Medium.

---

### 11. User-friendly context-length error message

**Files**: `agent/TurnErrorClassifier.kt:32-50`

**Problem**: Context-length errors surface raw provider text with no actionable guidance.

**Fix**: When `isContextLimit` is true, return a user-friendly message like "Conversation too long for model context window. Try starting a new task."

**Effort**: Small.

---

## Summary

| # | Priority | Description | Effort | Risk |
|---|----------|-------------|--------|------|
| 1 | P0 | Completion depends on executed `complete_task` | Medium | False success/failure |
| 2 | P0 | Approval dispatch failures fail fast | Small | Fake user timeout |
| 3 | P0 | `delegate_task` returns structural failure | Small | Failed delegation = success |
| 4 | P0 | Move `onDestroy()` shutdown off main thread | Small-Med | ANR risk |
| 5 | P1 | Map failed completion to `TASK_IMPOSSIBLE` | Medium | Error types conflated |
| 6 | P1 | Harden cleanup and observation fallback | Small | Brittle failure handling |
| 7 | P1 | Action outcome semantics end to end | Medium | Misleading UI/history |
| 8 | P1 | Bootstrap/session failure UX | Medium | Lost user input |
| 9 | P2 | Clean up dead typed error surface | Medium | Dead code |
| 10 | P2 | Surface corrupted session history | Small-Med | Silent disappearance |
| 11 | P2 | Context-length user-friendly message | Small | Poor UX |

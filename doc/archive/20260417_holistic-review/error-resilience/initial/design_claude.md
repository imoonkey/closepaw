# Error Handling & Resilience Review

Scope: `app/src/main/kotlin/com/moonkey/androidagent/`

---

## Perspective A: Error Path Correctness

### A1. Dual Error Classification Systems (Redundant, Not Aligned)

**Files**: `protocol/AgentError.kt`, `agent/TurnErrorClassifier.kt`, `llm/OpenAIErrorClassifier.kt`

Three independent classifiers exist with overlapping responsibilities:

- `AgentError.from(Throwable)` -- maps exceptions to `AgentError` variants. **Never called at runtime.** No call site found in codebase.
- `TurnErrorClassifier.classify(Throwable)` -- used by `AgentTurnRunner.handleTurnFailure()` to decide recoverable vs fatal.
- `OpenAIErrorClassifier.classify(Exception)` -- used by LLM clients to wrap exceptions.

**Problem**: `AgentError.from()` and `TurnErrorClassifier.classify()` have divergent recoverability logic. `AgentError.from()` marks all `IOException` as recoverable (via `LLMError` with `retryAfterMs`). `TurnErrorClassifier` only marks timeout/connection-refused as recoverable, treating DNS failures and context-limit errors as non-recoverable. The divergence is moot today since `AgentError.from()` is dead code, but it's confusing for maintainers.

**Finding**: `AgentError` sealed class and its `from()` companion are dead code. The 11 error variants are defined but only `AgentError` itself is referenced in `SessionError` (which is also never emitted -- see A3). The actual runtime error handling bypasses `AgentError` entirely.

### A2. Context-Length Errors Are Silently Non-Recoverable

**File**: `agent/TurnErrorClassifier.kt:31-37`

`TurnErrorClassifier` correctly identifies context-length errors via keyword matching (`"context length"`, `"maximum context"`, `"too many tokens"`, etc.) and marks them as non-recoverable. However, the error message surfaced to the user is just the raw exception message from the LLM provider, which may be cryptic (e.g., "This model's maximum context length is 128000 tokens").

**Concrete scenario**: Agent uses 25 turns, history grows large, LLM returns context-length error. Agent stops with `AgentStopReason.Error("This model's maximum context length...")`. User sees raw API error with no actionable guidance.

### A3. SessionError Event is Dead Code

**Files**: `protocol/SessionLifecycleEvents.kt:19`, `ui/chat/ChatEventReducer.kt:47`, `app/AgentServiceEventHandler.kt:129`

`SessionError` is defined, handled in both the chat reducer and the overlay event handler, but **never emitted** by `AgentSession`. All error paths in `AgentSession.handleAgentComplete()` route through `TaskCompleted` with a `CompletionReason.ERROR` instead. The `ChatEventReducer.handleError()` and `AgentServiceEventHandler`'s `SessionError` branch are unreachable.

### A4. Stream Failure After Partial Output -- Data Integrity

**File**: `llm/CloudStreamRetryPolicy.kt:24-29`

When a stream error occurs after events have already been emitted (`emittedEvent = true`), the policy correctly refuses to retry (to avoid duplicate output). It emits a `FailAndStop` with the error message. However, the partial tool calls collected before the failure are lost -- the `Turn.runStreaming()` flow emits `TurnStreamEvent.Error(e)` via the catch block, and the accumulated `toolCalls` list is discarded.

**Concrete scenario**: LLM returns a text delta + one tool call, then the connection drops. The text delta was already emitted to the UI (user sees partial text), but the tool call is lost. `TurnPlanningPhaseRunner` throws on `streamError?.let { throw it }`, the turn fails, and `TurnErrorClassifier` may or may not classify it as recoverable depending on the exception type. If recoverable, the retry re-sends the full prompt and the LLM may produce different output.

### A5. CloudLlmRetry Throws Cause, Losing TransientException Wrapper

**File**: `llm/CloudLlmRetry.kt:38`

```kotlin
} catch (e: TransientException) {
    // ...
    throw e.cause ?: e
```

When max retries are exceeded for a `TransientException`, the code throws `e.cause ?: e`. This unwraps the `TransientException`, so the thrown exception may be a raw `IOException` or `SocketTimeoutException`. Upstream in `OpenAIResponseClient.executeChatWithTools()`, this raw exception then passes through `OpenAIErrorClassifier.classify()` again (since the non-streaming path catches and reclassifies). For the streaming path, it propagates to `Turn.runStreaming()` as a non-`LLMStreamEvent.Failed` exception.

This is correct but confusing: the same exception gets classified twice in the non-streaming path.

### A6. Approval Timeout Handling is Correct but Rigid

**File**: `tool/ToolRouter.kt:157-170`

Approval timeout (60 seconds) returns `ToolCallResult.Cancelled("Approval timed out")`. This is treated by `TurnExecutionPhaseRunner` the same as a tool failure -- the remaining tool calls in the turn are aborted. The LLM then sees "Cancelled: Approval timed out" in history and decides what to do.

This is correctly handled. The 60-second timeout is reasonable for interactive use but may be too short for eval/debug-run scenarios where no human is present (the timeout will always fire).

### A7. Error Swallowing in Recording Service

**File**: `history/SessionRecordingService.kt` (multiple methods)

Several methods in `SessionRecordingService` silently return when there's no active session:

- `recordUserMessage()` -- logs warning, returns false
- `startAgentMessage()` -- logs warning, returns false
- `appendTextDelta()` -- logs warning, returns (void)
- `recordAction()` -- logs warning, returns (void)

These are guard clauses, not error swallowing. The pattern is correct -- the recording service is a side-effect observer, not a critical path. If it has no session, the agent loop continues fine.

**One genuine concern**: `completeSession()` on line 207 does `val session = currentSession ?: return` inside the synchronized block, meaning if `currentSession` is null, the session completion is silently skipped with no log message.

### A8. SessionStorage Write Without Atomic Guarantee on All Paths

**File**: `history/storage/SessionStorage.kt:77-88`

`writeSession()` writes directly to the target file (no temp+rename). If the process crashes mid-write, the session file is corrupted. In contrast, `writeSnapshot()` (line 181-201) correctly uses temp+rename atomic write pattern.

**Concrete scenario**: Process killed by OOM during `writeSession()`. Session file is truncated. On next app launch, `readSession()` fails with a `SerializationException`, and the session is lost.

---

## Perspective B: Graceful Degradation

### B1. Agent Recoverable Retry Budget Is Too Low

**File**: `agent/Agent.kt:29`

```kotlin
private const val MAX_RECOVERABLE_RETRIES = 1
```

The agent allows only 1 recoverable retry across the entire session. A single transient network blip (classified as recoverable by `TurnErrorClassifier`) consumes the budget. A second transient error -- even 10 turns later -- immediately terminates the agent.

The retry counter `recoverableRetryCount` is only reset on `TurnOutcome.Continue` (line 102). This means consecutive recoverable errors exhaust the budget, which is correct. But a single recoverable error followed by many successful turns, then another recoverable error, also exhausts the budget because the counter was already at 1.

Wait -- line 102 does `recoverableRetryCount = 0`. So it IS reset on success. The issue is more subtle: with `MAX_RECOVERABLE_RETRIES = 1`, you get exactly one retry per consecutive-failure streak. This is intentionally conservative but means two back-to-back transient errors (e.g., brief network outage spanning two turns) kill the session.

### B2. LLM Client Initialization Failure is Unrecoverable

**File**: `session/SessionLlmBootstrapper.kt:57-58`

`ensureRequiredCloudKeys()` throws `IllegalStateException` if the API key is missing. This propagates up through `SessionServices.create()` to `AgentSession.create()`. In `AgentService.runAgent()` (line 342), this is caught:

```kotlin
} catch (e: Exception) {
    Log.e(TAG, "Failed to create session", e)
    updateStatus("Failed to start: ${e.message}")
    overlayController?.hideAll()
}
```

The user sees a status message, but the error is not communicated through the chat UI -- only through the overlay status. In `MainActivity`, the error path through `SessionCoordinator.createAndSubmit()` returns `false`, and the user's input text is lost.

### B3. Platform Start Failure Leaves Session in Limbo

**File**: `session/AgentSession.kt:286-293`

If `services.platform.start()` throws on the first task, `initializeForFirstTask()` returns false. `handleUserInput()` then returns early without transitioning state -- the session stays in `SessionState.Created`. The user sees a status message but can try again. This is correct.

However, on follow-up tasks (Hot Idle path, line 297-309), if `services.platform.start()` fails, `reacquirePlatform()` returns false but also re-arms the idle timeout. The session stays in `SessionState.Idle` and will eventually auto-shutdown. The user can try again before the timeout, which is good.

### B4. Checkpoint Failure Does Not Block Session Flow

**File**: `session/AgentSession.kt:372-376`

```kotlin
val checkpointed = checkpointCoordinator.flushIdleReady()
if (!checkpointed) {
    emitStatus("Checkpoint save failed; session kept alive in memory.")
}
```

This is correct graceful degradation. Checkpoint failure is non-fatal; the session continues in memory. The user is informed. If the process dies, the session is lost (no checkpoint), but that's an acceptable trade-off.

### B5. Empty Screen Capture Degrades to Zero-Element Snapshot

**File**: `platform/AccessibilityPlatform.kt:127-155`

If the accessibility tree has no roots after 3 attempts, the platform returns a `ScreenSnapshot` with `elements = emptyList()`. This propagates to the LLM, which sees an empty screen. The agent can still function (e.g., press home, wait, try again). This is correct degradation.

### B6. Trace Recording Failures Are Non-Fatal

**File**: `session/AgentSession.kt:354-358`

```kotlin
try {
    services.traceRecorder.flush()
} catch (e: Exception) {
    Log.w(TAG, "Trace flush failed (non-fatal): ${e.message}")
}
```

Trace recording is correctly wrapped as non-fatal throughout. `traceRecorder.storeText()` failures in `AccessibilityPlatform` are also handled (they return null paths, which are just absent in the snapshot debug info).

### B7. SessionCoordinator Handles Dead Sessions Correctly

**File**: `session/SessionCoordinator.kt:57-61`

When a session is in `SessionState.Shutdown` and the user submits new input, the coordinator returns `SESSION_DEAD`, saves the dead session's filename for auto-reload, and tears down. The caller (`MainActivity`) can then create a new session or reload. This is robust.

### B8. DelegateTaskTool Failure Returns Success with Failure Message

**File**: `tool/impl/DelegateTaskTool.kt:173-179`

When a sub-agent fails, `DelegateTaskTool` returns `textToolSuccess(output = "Sub-agent failed: ...")`. This means the tool result is always `ToolCallResult.Success`, even when the sub-agent failed. The LLM sees the failure message and can decide what to do, which is the right pattern for a delegation tool.

### B9. Tool Argument Parse Failure Degrades to Empty JSONObject

**File**: `agent/Turn.kt:144-154`

When tool arguments can't be parsed as JSON, `convertToToolCallRequest()` falls back to `JSONObject()`. This means the tool gets empty parameters, which will fail validation in `ToolRouter` and return `ToolCallResult.Error`. The error message "Validation failed: Missing required parameter: ..." then goes back to the LLM, which can retry with correct parameters. This is correct degradation.

### B10. Service Destruction Races with Session Shutdown

**File**: `app/AgentService.kt:213-224`

`onDestroy()` uses `runBlocking` with a 5-second timeout to submit `Op.Shutdown`. If the shutdown times out, the session resources may not be fully cleaned up. The coroutine scope is then cancelled (`scope.cancel()`, line 236), which cancels any in-flight agent work. The `withContext(NonCancellable)` in `SessionAgentRunner.deliverCompletion()` (line 112) ensures the completion callback runs even during cancellation.

**Risk**: If `runBlocking` blocks the main thread for 5 seconds, Android may ANR if another accessibility event arrives. This is a real risk during service restart.

---

## Synthesis

### What Works Well

1. **Retry infrastructure**: The three-layer retry system (CloudLlmRetry for non-streaming, CloudStreamRetryRunner for streaming, Agent-level recoverable retry) provides defense in depth. Rate limit handling with `retryAfterMs` extraction is solid.

2. **Tool execution isolation**: ToolRouter wraps all tool execution in try/catch (line 270-273), so a tool crash never crashes the agent loop.

3. **Cancellation propagation**: The `CompletableDeferred<AgentStopReason>` + `AtomicBoolean` pattern ensures cancellation reaches all levels (Agent -> TurnRunner -> ToolRouter -> tool invocation).

4. **Recording service as side-effect observer**: All recording service failures are non-fatal. The agent never stops because recording failed.

5. **TOCTOU guard in approval flow**: Re-checking the foreground app after approval wait (ToolRouter line 196-225) prevents executing actions on the wrong app.

### Critical Findings

1. **`AgentError` and `SessionError` are dead code** (A1, A3). The entire `protocol/AgentError.kt` sealed class hierarchy is unused at runtime. `SessionError` event is never emitted. This is wasted complexity.

2. **Session file write is not atomic** (A8). Unlike snapshot writes (which use temp+rename), session record writes are direct, risking corruption on process death.

3. **Agent recoverable retry counter resets on success but the budget is 1** (B1). Two back-to-back transient errors kill the session. This is too aggressive for mobile networks.

4. **Service `onDestroy` uses `runBlocking` on main thread** (B10). 5-second timeout risks ANR.

5. **Context-length errors give raw API messages to user** (A2). No actionable guidance.

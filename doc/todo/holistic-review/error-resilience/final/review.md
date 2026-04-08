# Error Handling & Resilience Review — Final

**Base**: Codex design (cross-layer semantic analysis) + Claude degradation analysis
**Agreed by**: Claude + Codex via double-design alignment

---

## Executive Summary

The codebase has good **local fault containment**: result objects instead of unchecked throwing, retries near the source, conservative fallbacks, and security-oriented degradation that fails closed.

The main weakness is **cross-layer error semantics**. The system defines a rich typed error model in `protocol/AgentError.kt`, but the live execution path degrades errors into strings, booleans, and generic completion reasons before session and UI layers see them. This causes:

1. Some failures are reported as the wrong thing (false completion, approval swallowing)
2. Some failures are recoverable locally but become terminal because post-processing is too brittle
3. User-facing states collapse together: denied, cancelled, timed out, impossible, and internal error
4. History/session durability is weaker than the checkpoint design implies

---

## What Works Well

1. **Tool execution isolation**: `ToolRouter` wraps all tool execution in try/catch — a tool crash never crashes the agent loop
2. **Cloud LLM retry**: Three-layer retry system (CloudLlmRetry for non-streaming, CloudStreamRetryRunner for streaming, Agent-level recoverable retry) provides defense in depth. Rate limit handling with `retryAfterMs` extraction is solid
3. **Platform result objects**: `AccessibilityPlatform` and `VirtualDisplayPlatform` prefer returning `ActionResult` over throwing
4. **Checkpoint system**: `SessionCheckpointCoordinator` + `SessionRecordingService` create a real recovery story
5. **Security degradation**: Blocked-app masking and approval TOCTOU rechecks fail closed
6. **Cancellation propagation**: `CompletableDeferred<AgentStopReason>` + `AtomicBoolean` pattern ensures cancellation reaches all levels
7. **Recording service as side-effect observer**: All recording failures are non-fatal — the agent never stops because recording failed
8. **Checkpoint failure is non-fatal**: `flushIdleReady()` failure keeps the session alive in memory with a user-visible status message
9. **Empty screen capture degrades gracefully**: Returns zero-element snapshot; agent can still navigate (press home, wait, retry)
10. **Dead session handling**: `SessionCoordinator` returns `SESSION_DEAD` with auto-reload support

---

## Findings

### Critical (P0)

#### C1. Task completion declared even when `complete_task` never executed

**Files**: `agent/AgentTurnRunner.kt:103-122`, `agent/TurnExecutionPhaseRunner.kt:51-63`

`AgentTurnRunner.decideCompletion()` uses the planned tool list, not the executed result. If a cognitive tool fails before `complete_task`, execution breaks but the turn still reports completion.

**Concrete bad path**: Model returns `remember_experience(...)` then `complete_task(status=success)`. `remember_experience` fails. Execution loop breaks. `complete_task` never runs. Turn still completes because `decideCompletion()` only checks planned tool calls.

This is a correctness bug — the agent reports success when it never actually called `complete_task`.

#### C2. Approval notification failure swallowed, re-labeled as user timeout

**Files**: `tool/ToolRouter.kt:129-153`, `agent/TurnExecutionPhaseRunner.kt:165-178`

`TurnExecutionPhaseRunner.emitApprovalRequired()` catches and suppresses exceptions from the event emitter. `ToolRouter` expects the callback to throw on failure. When it doesn't, the tool waits 60 seconds, then returns `"Approval timed out"` — blaming the user for not responding to a prompt they never saw.

Root cause is lost and user is incorrectly blamed.

### High

#### H1. Typed protocol error model is dead code

**Files**: `protocol/AgentError.kt:11-170`, `protocol/SessionLifecycleEvents.kt:18-23`

`AgentError` defines 11 error variants with recoverability and category-specific payloads. `SessionError` is defined as a first-class protocol event. Neither is produced by the live runtime. All error paths route through `TurnErrorClassifier` → `TurnOutcome.Error(message)` → `AgentStopReason.Error(message)` → `CompletionReason.ERROR`.

The practical result: LLM errors, policy denials, user denials, platform faults, and malformed tool calls all look structurally similar at session/UI boundaries.

#### H2. `TASK_IMPOSSIBLE` exists in protocol but never produced by runtime

**Files**: `protocol/CompletionReason.kt:14-18`, `agent/Agent.kt:117-123`, `session/AgentSession.kt:391-397`

The distinction between "system fault" and "agent concluded task is impossible" is thrown away. Both map to `CompletionReason.ERROR`. UI and recording layers are wired for `TASK_IMPOSSIBLE` but never see it.

#### H3. Action states flattened — denied/cancelled/timed-out/failed all → "failed"

**Files**: `agent/TurnExecutionPhaseRunner.kt:148-162`, `app/AgentServiceEventHandler.kt:60-63`, `ui/chat/ChatEventReducer.kt:101-129`

`ActionExecuted(success: Boolean)` collapses all non-success outcomes. `TurnExecutionPhaseRunner` emits `"✓ <tool> executed"` even for failed actions. `ActionState.Skipped` exists in the UI model but is never used.

#### H4. `ask_user` classified as screen-changing tool

**Files**: `tool/ToolName.kt:11-17, 70-83`, `tool/PolicyEngine.kt:48-57`

`ask_user` isn't in the `ToolName` enum, defaults to `isScreenChanging = true`. Requires approval before asking for help; denied entirely in blocked apps. This is backwards — `ask_user` exists for exactly the cases where the agent is blocked.

#### H5. Shutdown cleanup only partially hardened

**Files**: `session/SessionServices.kt:221-225`

Only `platform.stop()` is guarded with try/catch. `llmClient.cleanup()`, `llmClientFactory.cleanupAll()`, `traceRecorder.close()` can abort teardown mid-sequence.

#### H6. `delegate_task` reports sub-agent failure as tool success

**Files**: `tool/impl/DelegateTaskTool.kt:161-186`

Returns `textToolSuccess(...)` even when `result.success` is false. This affects action cards, history state, and parent turn control flow — not just LLM context. Structural semantic bug.

#### H7. Non-atomic session file writes

**Files**: `history/storage/SessionStorage.kt:77-87`

`writeSession()` writes directly (no temp+rename). `writeSnapshot()` correctly uses atomic pattern. Process crash during `writeSession()` corrupts the session file. Corrupted files silently disappear from `SessionHistoryManager` with no user explanation.

#### H8. `onDestroy()` blocks main thread with `runBlocking`

**Files**: `app/AgentService.kt:213-224`

5-second timeout on main thread risks ANR during service restart.

#### H9. Agent recoverable retry budget too low

**Files**: `agent/Agent.kt:29`

`MAX_RECOVERABLE_RETRIES = 1` means two consecutive transient errors kill the session. Counter resets on success, but on flaky mobile networks, one retry is too aggressive.

### Medium

#### M1. Post-action observation capture can escalate local failure

**Files**: `agent/TurnExecutionPhaseRunner.kt:181-206, 214-239`

When a tool fails and has no embedded observation, `captureObservationWithSnapshot()` is called without shielding. If screen capture throws, a local tool failure becomes a turn-level failure. Error handling should not itself be fragile.

#### M2. CancellationException caught as generic failure

**Files**: `agent/AgentTurnRunner.kt:82-126`, `agent/Turn.kt:78-140`, `session/SessionAgentRunner.kt:87-101`

Broad `catch (Exception)` blocks can convert cooperative cancellation into generic error completion. Some paths handle it, but the design isn't consistently cancellation-safe.

#### M3. Context-length errors surface raw API messages

**Files**: `agent/TurnErrorClassifier.kt:31-37`

`TurnErrorClassifier` correctly identifies context-length errors as non-recoverable but surfaces raw provider text (e.g., "This model's maximum context length is 128000 tokens"). No actionable guidance for the user.

#### M4. Session/bootstrap failure poorly surfaced

**Files**: `session/SessionCoordinator.kt`, `app/AgentService.kt:342`, `ui/main/MainActivity.kt`

LLM bootstrap failures surface through overlay status only. The chat path loses context and the user's original input text is dropped.

#### M5. `completeSession()` silently skips on null session

**Files**: `history/SessionRecordingService.kt:207`

`currentSession ?: return` with no log message. All other guard clauses in the same class log warnings.

### Low

#### L1. Partial stream failure after emitting text — correct but undocumented

**Files**: `llm/CloudStreamRetryPolicy.kt:24-29`, `agent/Turn.kt:122-126`

Stream failure after emitting text + partial tool calls discards the tool calls. Current behavior (fail turn, agent-level retry re-runs) is the safest option. Should be documented as intentional.

#### L2. CloudLlmRetry throws cause, losing TransientException wrapper

**Files**: `llm/CloudLlmRetry.kt:38`

Unwrapping `TransientException` causes the same exception to be classified twice in the non-streaming path. Functionally correct but confusing for maintainers.

---

## Synthesis

The highest-value fixes are not broad refactors. They are:

1. **Fix the two correctness bugs** where the runtime reports the wrong outcome (C1, C2)
2. **Make one typed error envelope authoritative** across agent/session/UI — trim `AgentError` to live variants, then wire it through (H1)
3. **Preserve cancel/deny/skip semantics end to end** (H3, H4)
4. **Harden shutdown and persistence** so failure handling itself is not fragile (H5, H7, H8)

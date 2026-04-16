# Error Handling & Resilience Review — Final

**Base**: Codex design (cross-layer semantic analysis) + Claude degradation analysis
**Agreed by**: Claude + Codex via double-design alignment
**Revalidated**: 2026-04-15 against current codebase

---

## Executive Summary

The codebase has good **local fault containment**: result objects instead of unchecked throwing, retries near the source, conservative fallbacks, and security-oriented degradation that fails closed.

The main weakness is **cross-layer error semantics**. The live execution path degrades errors into strings, booleans, and generic completion reasons before session and UI layers see them. This causes:

1. Some failures are reported as the wrong thing (false completion, approval swallowing)
2. User-facing states collapse together: denied, cancelled, timed out, impossible, and internal error
3. Delegation failures are masked as successes

---

## What Works Well

1. **Tool execution isolation**: `ToolRouter` wraps all tool execution in try/catch — a tool crash never crashes the agent loop
2. **Cloud LLM retry**: Three-layer retry system (CloudLlmRetry for non-streaming, CloudStreamRetryRunner for streaming, Agent-level recoverable retry) provides defense in depth. Rate limit handling with `retryAfterMs` extraction is solid
3. **Platform result objects**: `AccessibilityPlatform` and `VirtualDisplayPlatform` prefer returning `ActionResult` over throwing
4. **Checkpoint system**: `SessionCheckpointCoordinator` + `SessionRecordingService` create a real recovery story
5. **Security degradation**: Blocked-app masking and approval TOCTOU rechecks fail closed
6. **Cancellation propagation**: `CompletableDeferred<AgentStopReason>` + `AtomicBoolean` pattern ensures cancellation reaches all levels. Main outer layers now explicitly preserve cancellation semantics with `CancellationException` rethrown before generic catches.
7. **Recording service as side-effect observer**: All recording failures are non-fatal — the agent never stops because recording failed
8. **Checkpoint failure is non-fatal**: `flushIdleReady()` failure keeps the session alive in memory with a user-visible status message
9. **Empty screen capture degrades gracefully**: Returns zero-element snapshot; agent can still navigate
10. **Dead session handling**: `SessionCoordinator` returns `SESSION_DEAD` with auto-reload support
11. **Tool classification**: `ask_user` and `shell` are correctly classified as non-screen-changing with explicit `ToolName` entries
12. **Session file writes are atomic**: `writeSession()` uses temp-file-plus-rename
13. **Stream partial-failure documented**: Design decision to fail the turn (no retry after partial output) is explicitly documented in `doc/main/infra/llm.md`

---

## Findings

### Critical (P0)

#### C1. Task completion declared even when `complete_task` never executed

**Files**: `agent/AgentTurnRunner.kt:95-109, 225-237`, `agent/TurnExecutionPhaseRunner.kt:37-68`, `agent/cognition/policy/TurnToolPolicy.kt:91-107`

`executeActions()` returns `Unit`, so `AgentTurnRunner` decides completion from the planned turn result, not from what actually executed. If an earlier cognitive tool fails and `complete_task` never runs, the turn can still become `TurnOutcome.Complete`.

**Concrete bad path**: Model returns `remember_experience(...)` then `complete_task(status=success)`. `remember_experience` fails. Execution loop breaks. `complete_task` never runs. Turn still completes because `decideCompletion()` only checks planned tool calls.

This is a correctness bug — the agent reports success when it never actually called `complete_task`.

#### C2. Approval notification failure swallowed, re-labeled as user timeout

**Files**: `tool/ToolRouter.kt:148-162`, `agent/TurnExecutionPhaseRunner.kt:164-174`

`ToolRouter` is already prepared to convert approval-dispatch exceptions into `ToolCallResult.Error(...)`. But `TurnExecutionPhaseRunner.emitApprovalRequired()` catches and suppresses those exceptions. The router never sees the failure and falls through to the 60-second timeout path — blaming the user for not responding to a prompt they never saw.

The bug is concentrated in the swallow site inside `TurnExecutionPhaseRunner`.

#### C3. `delegate_task` reports sub-agent failure as tool success

**Files**: `tool/impl/DelegateTaskTool.kt:156-177`

Returns `textToolSuccess(...)` even when `result.success` is false. This affects action cards, history state, and parent turn control flow — not just LLM context. With PRO mode as default, this is a core orchestration bug.

### High

#### H1. `TASK_IMPOSSIBLE` exists in protocol but never produced by runtime

**Files**: `protocol/CompletionReason.kt:14-18`, `agent/AgentRuntimeTypes.kt:5-18`, `agent/Agent.kt:105-123`, `session/AgentSession.kt:418-424`

`CompletionReason.TASK_IMPOSSIBLE` exists and UI/recording layers are wired for it. But there is no `AgentStopReason.TaskImpossible`. `TurnOutcome.Complete(success = false)` still becomes `AgentStopReason.Error`, so the impossible-vs-internal-fault distinction is lost.

#### H2. Shutdown cleanup only partially hardened

**Files**: `session/SessionServices.kt:210-235`

Only `platform.stop()` is guarded with try/catch. `llmClient.cleanup()`, `llmClientFactory.cleanupAll()`, and `traceRecorder.close()` can abort teardown mid-sequence.

Separately, `captureObservationWithSnapshot()` in `TurnExecutionPhaseRunner:176-235` is called without shielding after tool failures. If screen capture throws, a local tool failure escalates to a turn-level failure. Error handling should not itself be fragile.

#### H3. `onDestroy()` blocks main thread with `runBlocking`

**Files**: `app/AgentService.kt:206-236`

`onDestroy()` still blocks the main thread with `runBlocking`, and `Op.Shutdown` performs real cleanup work synchronously inside `AgentSession.submit(...)`. 5-second timeout risks ANR during service restart.

Note: a fire-and-forget approach on the existing `scope` won't work — `onDestroy()` cancels that scope at the end, which would drop shutdown midway. Needs a dedicated shutdown scope or detached coroutine.

#### H4. Action states flattened — denied/cancelled/timed-out/failed all → "failed"

**Files**: `protocol/ActionEvents.kt:13-19`, `agent/TurnExecutionPhaseRunner.kt:151-157`, `app/AgentServiceEventHandler.kt:60-63`, `ui/chat/ChatEventReducer.kt:101-129`

`ActionExecuted(success: Boolean)` collapses all non-success outcomes. `TurnExecutionPhaseRunner` emits `"✓ <tool> executed"` even for failed actions. `ActionState.Skipped` exists in the UI model but is never used.

Note: the fix should use a smaller enum (`SUCCESS / FAILED / SKIPPED`) derived directly from router results without string-matching. Some "skipped" cases already come back as `ToolCallResult.Error` rather than `Cancelled`. Drop the `CANCELLED` concept from action-level — leave cancellation to session lifecycle events.

#### H5. Bootstrap/session failure poorly surfaced

**Files**: `session/AgentSession.kt:304-320`, `app/MainActivity.kt:431-488`, `ui/chat/ChatViewModel.kt:192-199`, `ui/chat/ChatEventReducer.kt:54-57`

Bootstrap and session-start failures surface mostly as toast/status text. The user's input only enters chat on `TaskStarted`, so if startup fails before that event, the input disappears. The important parts: preserve the pending input and surface startup failure through the main chat/session UX.

### Medium

#### M1. Dead typed error protocol surface

**Files**: `protocol/AgentError.kt:11-170`, `protocol/SessionLifecycleEvents.kt:18-23`

`AgentError` defines 11 error variants. `SessionError` is defined as a first-class protocol event. Neither is produced by the live runtime. All error paths route through `TurnErrorClassifier` → strings → `CompletionReason.ERROR`.

Making the full `AgentError` hierarchy authoritative across all layers is too much machinery for the concrete problems that remain. Most user-visible value comes from specific semantic fixes (C1, C3, H1, H4), not from plumbing a large error envelope everywhere. Either delete the dead surface or replace with a much smaller live failure kind used only where structure materially helps.

#### M2. Corrupted session history silently disappears

**Files**: `history/SessionHistoryManager.kt:237-238, 266-297`, `ui/chat/ChatSessionHistoryController.kt:42-48`

Session writes are now atomic (temp+rename). The remaining issue is on the read side: when `extractSessionInfo()` cannot parse a file, it returns `null` and the manager drops the entry silently. Users get no explanation for missing session history.

### Low

#### L1. Context-length errors surface raw API messages

**Files**: `agent/TurnErrorClassifier.kt:32-50`

`TurnErrorClassifier` correctly identifies context-length errors as non-recoverable but surfaces raw provider text. No actionable user guidance. UX polish item.

#### L2. CloudLlmRetry throws cause, losing TransientException wrapper

**Files**: `llm/CloudLlmRetry.kt:38`

Unwrapping `TransientException` causes the same exception to be classified twice in the non-streaming path. Functionally correct but confusing for maintainers.

---

## Synthesis

The highest-value fixes are targeted, not broad refactors:

1. **Fix the three correctness bugs** where the runtime reports the wrong outcome (C1, C2, C3)
2. **Preserve action and completion semantics** so the system can distinguish impossible from errored, and skipped from failed (H1, H4)
3. **Harden shutdown and observation paths** so error handling itself is not fragile (H2, H3)

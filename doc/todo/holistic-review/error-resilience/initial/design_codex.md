# Error Handling & Resilience Review - Codex

## Scope

Reviewed code under `app/src/main/kotlin/com/moonkey/androidagent/` with focus on:

- Agent turn orchestration and error classification
- LLM retries, streaming, and failure shaping
- Protocol error types
- Tool routing and tool implementations
- Platform execution, including virtual display paths
- Session lifecycle and checkpoint/history persistence
- Chat/overlay error communication

I did not read any other `doc/todo/` design files for this review.

## Executive Summary

The codebase has good local fault containment in a few places:

- Many tool and platform operations return result objects instead of throwing.
- Cloud LLM retry policy is isolated cleanly.
- Approval timeout and TOCTOU checks are explicit.
- Checkpointing and Hot Idle are resilience-oriented features.

The main weakness is cross-layer error semantics. The system defines a rich typed error model in `protocol/AgentError.kt`, but the live execution path mostly degrades errors into strings, booleans, and generic completion reasons before session and UI layers see them. That causes four broad problems:

1. Some failures are reported as the wrong thing.
2. Some failures are recoverable locally but become terminal because post-processing is too brittle.
3. Several user-facing states collapse together: denied, cancelled, timed out, impossible, and internal error.
4. History/session durability is weaker than the surrounding checkpoint design implies.

## What Is Already Working

1. `tool/ToolRouter.kt` is structurally sound. Validation, policy, approval wait, execution, and terminal-state cleanup are separated clearly.
2. `llm/CloudLlmRetry.kt` and `llm/CloudStreamRetryRunner.kt` isolate retry mechanics from client code cleanly.
3. `platform/AccessibilityPlatform.kt` and `platform/virtualdisplay/VirtualDisplayPlatform.kt` generally prefer returning `ActionResult` over throwing.
4. `session/SessionCheckpointCoordinator.kt` plus `history/SessionRecordingService.kt` create a real recovery story instead of treating session state as ephemeral.
5. Security-oriented degradation is conservative. Blocked-app masking and approval TOCTOU rechecks fail closed.

## Current Error Propagation Shape

The effective runtime path today is:

- LLM/client exception -> `TurnErrorClassifier` -> `TurnOutcome.Error(message, recoverable)`
- Tool/platform failure -> `ToolCallResult.Error` or `ToolCallResult.Cancelled` -> `ActionExecuted(success: Boolean, result: String?)`
- Agent stop -> `AgentStopReason`
- Session completion -> `CompletionReason`
- UI/history -> generic success/failed/completed states

The typed protocol path is mostly dormant:

- `protocol/AgentError.kt` defines 11 error variants at `app/src/main/kotlin/com/moonkey/androidagent/protocol/AgentError.kt:11-170`
- `protocol/SessionLifecycleEvents.kt` defines `SessionError` at `app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionLifecycleEvents.kt:18-23`
- Within the reviewed execution packages, I found no producer for `SessionError`

That mismatch is the core design problem.

## Findings

### 1. Critical: task completion can be declared even when execution aborted before `complete_task`

`TurnToolPolicy` keeps cognitive tools before `complete_task` when no screen action exists at `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicy.kt:70-106`. `TurnExecutionPhaseRunner` then executes tools sequentially and stops on the first non-success result at `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:51-63`. After that, `AgentTurnRunner` decides completion from the original planning result only, not from actual execution success, at `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:103-122`.

Concrete bad path:

- Model returns `remember_experience(...)` then `complete_task(status=success, answer=...)`
- `remember_experience` fails
- execution loop breaks
- `complete_task` never runs
- turn still completes because `decideCompletion()` looks only at the planned tool calls

This is a correctness bug, not just a reporting bug.

### 2. Critical: approval-notification failure is swallowed and re-labeled as user timeout

`ToolRouter` explicitly expects `onApprovalRequired` to throw if approval dispatch fails, and converts that into `ToolCallResult.Error` at `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:129-153`.

But `TurnExecutionPhaseRunner.emitApprovalRequired()` catches and suppresses any exception from `eventEmitter` at `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:165-178`.

That means:

- approval UI dispatch can fail
- `ToolRouter` never sees the failure
- the tool waits 60 seconds
- the final result becomes `Approval timed out`

The root cause is lost and the user is blamed for not responding to a prompt they never received.

### 3. High: the typed protocol error model is effectively dead code

`AgentError` is detailed and useful at `app/src/main/kotlin/com/moonkey/androidagent/protocol/AgentError.kt:11-170`, including recoverability and category-specific payloads. `SessionError` is also defined as a first-class protocol event at `app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionLifecycleEvents.kt:18-23`.

In the live path, though:

- `Agent.kt` stops with `AgentStopReason.Error(message)` at `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt:117-145`
- `AgentSession` maps all `AgentStopReason.Error` to `CompletionReason.ERROR` at `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:391-397`
- `SessionError` is not emitted by the reviewed runtime packages

The practical result is that LLM errors, policy denials, user denials, platform faults, and malformed tool calls all end up looking structurally similar by the time they reach session/UI boundaries.

### 4. High: `TASK_IMPOSSIBLE` exists in protocol but is not produced by the main runtime

`CompletionReason.TASK_IMPOSSIBLE` exists at `app/src/main/kotlin/com/moonkey/androidagent/protocol/CompletionReason.kt:14-18`, and UI/recording layers are ready to consume it. But the actual runtime maps failed completion to generic error:

- `Agent.kt` maps unsuccessful completion to `AgentStopReason.Error` at `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt:117-123`
- `AgentSession` then maps that to `CompletionReason.ERROR` at `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:391-397`

This throws away an important user-facing distinction:

- "the agent hit an internal/system fault"
- "the agent understood the task and concluded it cannot be completed"

Those should not share the same terminal protocol reason.

### 5. High: cancelled, denied, aborted, and failed actions are flattened into the same UI/history outcome

`TurnExecutionPhaseRunner` emits `ActionExecuted(success = toolResult is ToolCallResult.Success)` and then unconditionally posts the status `"✓ <tool> executed"` at `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:148-162`.

Downstream:

- `AgentServiceEventHandler` stores every non-success action as `"failed"` at `app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceEventHandler.kt:60-63`
- `ChatEventReducer` maps every `success = false` action to `ActionState.Failed` at `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatEventReducer.kt:101-129`
- `ChatEventReducer.handleError()` ignores the actual message entirely at `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatEventReducer.kt:139-140`

Consequences:

- policy denial
- user denial
- approval timeout
- cancellation
- real execution failure

all end up rendered as roughly the same "failed" action, and sometimes the user still sees a success-style status line (`✓ executed`).

The presence of `ActionState.Skipped` in the UI model shows the design already anticipated this distinction, but the runtime never uses it.

### 6. High: `ask_user` is classified as a screen-changing/approval-gated tool

`AskUserTool` is a cognitive/user-bridge tool at `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/AskUserTool.kt:16-140`. But `ToolName` does not include `ask_user`; unknown tools default to `isScreenChanging = true` at `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolName.kt:11-17` and `:70-83`. `PolicyEngine` allows only non-screen-changing tools automatically, and hard-denies screen-changing tools in blocked apps at `app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt:48-57`.

That means `ask_user` can:

- require approval before it is allowed to ask the user for help
- be denied entirely on blocked apps

This is exactly backwards for the cases where `ask_user` is most needed: login, CAPTCHA, biometric handoff, and ambiguous user choice.

The same classification hole also affects other missing tools such as `shell`, though `ask_user` is the more important behavioral bug.

### 7. High: shutdown cleanup is only partially hardened

`SessionServices.cleanup()` only protects `platform.stop()` with `try/catch` at `app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt:221-225`. The rest of teardown is unguarded:

- `llmClient.cleanup()`
- `llmClientFactory.cleanupAll()`
- `traceRecorder.close()`

If any of those throw, cleanup aborts mid-sequence after the session has already transitioned to shutdown. That is the opposite of what teardown code should optimize for.

This matters most for:

- local model unload paths
- cached client cleanup
- trace recorder close/join behavior

### 8. High: `delegate_task` reports sub-agent failure as tool success

`DelegateTaskInvocation.execute()` returns `textToolSuccess(...)` even when `result.success` is false at `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/DelegateTaskTool.kt:161-186`.

That causes two bad outcomes:

- the action card/history layer sees a successful tool execution
- parent turn control flow cannot distinguish a healthy delegation from a failed one except by parsing text

This is another place where structural error information is converted into prose too early.

### 9. Medium: post-action observation capture can turn a local tool failure into a turn-level failure

`TurnExecutionPhaseRunner.resolveObservation()` falls back to `captureObservationWithSnapshot()` for any tool result without an embedded observation at `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:181-206`. `captureObservationWithSnapshot()` then calls `services.platform.captureScreen()` without shielding at `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:214-239`.

So a localized tool failure can escalate into a full turn failure if the follow-up screen capture throws. That is too brittle for a recovery-oriented loop.

### 10. Medium: session persistence durability is inconsistent, and recovery UX is too silent

`SessionStorage.writeSession()` writes the session file directly at `app/src/main/kotlin/com/moonkey/androidagent/history/storage/SessionStorage.kt:77-87`. `writeSnapshot()` uses temp-file plus rename at `app/src/main/kotlin/com/moonkey/androidagent/history/storage/SessionStorage.kt:178-200`.

That asymmetry means:

- checkpoints are crash-hardened
- session transcript files are not

If a session file becomes unreadable:

- `SessionHistoryManager.extractSessionInfo()` returns `null` when `readSession()` fails at `app/src/main/kotlin/com/moonkey/androidagent/history/SessionHistoryManager.kt:237-239`
- the file then silently disappears from the list path at `app/src/main/kotlin/com/moonkey/androidagent/history/SessionHistoryManager.kt:275-297`
- `ChatSessionHistoryController` only logs resume/delete failures at `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatSessionHistoryController.kt:53-71` and `:83-91`

The user gets almost no explanation for broken session history.

### 11. Medium: cancellation is often caught as generic failure instead of being rethrown

Broad `catch (Exception)` blocks appear on critical control paths:

- `AgentTurnRunner.executeTurn()` at `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:82-126`
- `Turn.runStreaming()` at `app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt:78-140`
- `SessionAgentRunner.start()` at `app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt:87-101`

Because `CancellationException` is an `Exception`, these blocks risk converting cooperative cancellation into generic error completion unless every caller is careful. Some paths do handle cancellation intentionally, but the design is not consistently cancellation-safe.

## Overall Assessment

The codebase is already good at local containment:

- result objects instead of unchecked throwing
- retries near the source
- conservative fallbacks

But it is not yet good at preserving meaning across layers. The current architecture can survive many failures, but it often cannot explain them correctly, classify them consistently, or present them truthfully to the user.

The highest-value fixes are therefore not broad refactors. They are:

1. make one typed error envelope actually authoritative across agent/session/UI
2. fix the two correctness bugs where the runtime reports the wrong outcome
3. preserve cancel/deny/skip semantics end to end
4. harden shutdown and persistence so failure handling itself is not fragile

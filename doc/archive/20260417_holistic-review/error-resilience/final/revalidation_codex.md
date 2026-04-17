# Error Resilience Plan Revalidation (Codex)

Reviewed against the current worktree on 2026-04-15.

I re-read:
- `doc/todo/holistic-review/error-resilience/final/review.md`
- `doc/todo/holistic-review/error-resilience/final/improvement_plan.md`

Then I checked the live implementations behind all 17 plan items, including adjacent call sites where the old doc references were no longer enough to judge current behavior.

## Summary

| # | Item | Classification | Priority Call |
|---|------|----------------|---------------|
| 1 | Completion depends on executed tool results | **STILL VALID** | Keep at **P0** |
| 2 | Approval dispatch failures fail fast | **STILL VALID** | Keep at **P0** |
| 3 | `ask_user` non-screen-changing | **ALREADY FIXED** | - |
| 4 | Remove `runBlocking` from `onDestroy()` | **NEEDS RETHINK** | - |
| 5 | Preserve action outcome semantics end to end | **NEEDS RETHINK** | - |
| 6 | Split `TASK_IMPOSSIBLE` from internal `ERROR` | **STILL VALID** | Keep at **P1** |
| 7 | Make typed errors authoritative | **NEEDS RETHINK** | - |
| 8 | `delegate_task` returns structural failure | **STILL VALID** | Raise to **P0** |
| 9 | Harden cleanup and observation fallback | **STILL VALID** | Keep at **P1** |
| 10 | Increase recoverable retry budget | **NOT WORTH IT** | - |
| 11 | User-friendly context-length error message | **STILL VALID** | Drop to **P2 / Low** |
| 12 | Log null-session in `completeSession()` | **NOT WORTH IT** | - |
| 13 | Atomic session writes + corruption UX | **NEEDS RETHINK** | - |
| 14 | CancellationException safety | **ALREADY FIXED** | - |
| 15 | Configurable approval timeout for eval/debug | **NOT WORTH IT** | - |
| 16 | Document stream partial-failure design | **ALREADY FIXED** | - |
| 17 | Improve bootstrap/session failure UX | **STILL VALID** | Raise to **P1** |

## Overall Call

The old plan should not be executed as-is.

What I would keep alive:
- `#1`, `#2`, `#6`, `#8`, `#9`, `#11`, `#17`

What I would cut:
- `#3`, `#14`, `#16` because they are already fixed
- `#10`, `#12`, `#15` because they are not worth carrying as plan items

What I would rewrite before touching code:
- `#4`, `#5`, `#7`, `#13`

## Item-by-Item Revalidation

### 1. Make task completion depend on executed tool results
- Classification: **STILL VALID**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:37-68`, `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:95-109`, `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:225-237`, `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicy.kt:91-107`
- Finding: `executeActions()` still returns `Unit`, so `AgentTurnRunner` still decides completion from the planned turn result, not from what actually executed. If an earlier cognitive tool fails and `complete_task` never runs, the turn can still become `TurnOutcome.Complete`.
- Priority: Keep at **P0**.
- Note: the plan direction is right, but keep the fix minimal. The runtime only needs enough execution data to know whether `complete_task` actually ran and whether execution terminated early.

### 2. Fail fast when approval UI dispatch breaks
- Classification: **STILL VALID**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:148-162`, `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:164-174`
- Finding: `ToolRouter` is already prepared to convert approval-dispatch exceptions into `ToolCallResult.Error(...)`, but `TurnExecutionPhaseRunner.emitApprovalRequired()` still catches and suppresses those exceptions. That means the router never sees the failure and still falls through to the 60-second timeout path.
- Priority: Keep at **P0**.
- Note: this is narrower than the old review implied. The bug is now concentrated in the swallow site inside `TurnExecutionPhaseRunner`.

### 3. Classify `ask_user` as non-screen-changing
- Classification: **ALREADY FIXED**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolName.kt:11-16`, `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolName.kt:64-73`, `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolName.kt:80-95`, `app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt:58-60`
- Finding: `ToolName.AskUser` now exists, `ToolName.Shell` also exists, and both are explicitly marked `isScreenChanging = false`. `PolicyEngine` allows non-screen-changing tools before blocked-app policy is applied.
- Call: remove this item from the plan.

### 4. Remove `runBlocking` from `AgentService.onDestroy()`
- Classification: **NEEDS RETHINK**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:206-236`, `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:254-266`, `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:515-539`
- Finding: the problem is still real. `onDestroy()` still blocks the main thread with `runBlocking`, and `Op.Shutdown` still performs real cleanup work synchronously inside `AgentSession.submit(...)`.
- Why the old fix is outdated: the plan's fire-and-forget example launches shutdown on `scope`, but `onDestroy()` cancels that same `scope` at the end. With current code, that can drop shutdown midway.
- Better direction: move shutdown off the main thread, but do it on a dedicated shutdown path or detached scope that is not immediately cancelled by `onDestroy()`.

### 5. Preserve action outcome semantics end to end
- Classification: **NEEDS RETHINK**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/protocol/ActionEvents.kt:13-19`, `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:151-157`, `app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceEventHandler.kt:60-63`, `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatEventReducer.kt:101-129`, `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/model/ChatMessage.kt:101-115`, `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:116-121`
- Finding: the flattening bug is still real. `ActionExecuted` still carries only `success: Boolean`, failed actions still get a `"✓ ... executed"` status string, and `ActionState.Skipped` still goes unused.
- Why the old fix is outdated: the proposed mapping is too literal for current code. Some "skipped" cases already come back as `ToolCallResult.Error` rather than `Cancelled`, and the UI model does not currently have an action-level `Cancelled` state.
- Better direction: introduce a smaller explicit action outcome such as `SUCCESS / FAILED / SKIPPED`, derive it from the router result without string-matching, and leave task/session cancellation to the higher-level lifecycle events.

### 6. Split `TASK_IMPOSSIBLE` from internal `ERROR`
- Classification: **STILL VALID**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/protocol/CompletionReason.kt:14-18`, `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentRuntimeTypes.kt:5-18`, `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt:105-123`, `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:418-424`
- Finding: `CompletionReason.TASK_IMPOSSIBLE` still exists, but there is still no `AgentStopReason.TaskImpossible`. `TurnOutcome.Complete(success = false)` still becomes `AgentStopReason.Error`, so the impossible-vs-internal-fault distinction is still lost.
- Priority: Keep at **P1**.

### 7. Make typed error envelope authoritative
- Classification: **NEEDS RETHINK**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/protocol/AgentError.kt:11-170`, `app/src/main/kotlin/com/moonkey/androidagent/protocol/TaskLifecycleEvents.kt:16-22`, `app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionLifecycleEvents.kt:18-23`, `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnErrorClassifier.kt:17-50`, `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentRuntimeTypes.kt:14-18`, `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt:126-146`
- Finding: `AgentError` and `SessionError` are still effectively dead protocol surface. The runtime still strips failures to strings plus `CompletionReason.ERROR`.
- Why the old fix is outdated: making the full sealed `AgentError` hierarchy authoritative across agent, session, UI, and history is too much machinery for the concrete problems that remain. Most of the user-visible value is in fixing a few specific semantic leaks, not in plumbing a large error envelope everywhere.
- Better direction: either delete the dead typed-error surface or replace it with a much smaller live failure kind used only where structure materially helps.

### 8. Return structural failure from `delegate_task`
- Classification: **STILL VALID**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/DelegateTaskTool.kt:156-177`, `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:151-160`, `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:61-66`
- Finding: failed sub-agent runs still return `textToolSuccess(...)`. That means the parent turn still treats failed delegation as a successful tool call. Since the default agent mode is `PRO`, this is a core orchestration bug, not just an action-card bug.
- Priority: raise from **P1** to **P0**.

### 9. Harden cleanup and observation fallback
- Classification: **STILL VALID**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt:210-235`, `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:176-235`
- Finding: `SessionServices.cleanup()` still only guards `platform.stop()`. `llmClient.cleanup()`, `llmClientFactory.cleanupAll()`, and `traceRecorder.close()` can still abort teardown. Separately, failed tool calls still fall into `captureObservationWithSnapshot()` with no shielding, so a local tool failure can still become a turn-level failure if post-action capture throws.
- Priority: keep at **P1**.
- Note: implement this as two small fixes. The cleanup hardening and observation fallback are unrelated.

### 10. Increase agent recoverable retry budget
- Classification: **NOT WORTH IT**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt:27-30`, `app/src/main/kotlin/com/moonkey/androidagent/llm/LLMClient.kt:29-32`, `app/src/main/kotlin/com/moonkey/androidagent/llm/CloudLlmRetry.kt:20-47`, `app/src/main/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryPolicy.kt:31-45`
- Finding: the retry budget is still `1`, but each LLM call already gets up to 5 lower-level retries with backoff before the turn fails.
- Why I would cut it: bumping the agent-level budget to `3` would mainly increase tail latency and re-run whole turns after the dedicated LLM retry stack has already exhausted its budget. That is the wrong layer to inflate by default.

### 11. User-friendly context-length error message
- Classification: **STILL VALID**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnErrorClassifier.kt:32-50`
- Finding: context-limit failures are detected, but the final message still uses raw provider text. There is still no user-friendly remediation message.
- Priority: drop from **P1** to **P2 / Low**. This is worth doing, but it is a UX polish item, not a control-flow fix.

### 12. Log null-session guard in `completeSession()`
- Classification: **NOT WORTH IT**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:218-223`
- Finding: the guard is still silent.
- Why I would cut it: this only adds a warning log. It does not improve correctness, recovery, or user-facing behavior, and it is too small to keep alive as a standalone resilience item.

### 13. Make session writes atomic and surface corrupted history
- Classification: **NEEDS RETHINK**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/history/storage/SessionStorage.kt:77-88`, `app/src/main/kotlin/com/moonkey/androidagent/history/SessionHistoryManager.kt:237-238`, `app/src/main/kotlin/com/moonkey/androidagent/history/SessionHistoryManager.kt:266-297`, `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatSessionHistoryController.kt:42-48`
- Finding: the original write-side complaint is mostly stale. `writeSession()` now uses temp-file-plus-rename. The remaining live problem is on the read side: unreadable sessions still disappear from history because `extractSessionInfo()` returns `null` and the manager drops the entry silently.
- Better direction: narrow this item to corrupted-history surfacing and better read-time diagnostics. Do not spend more time reworking write atomicity unless a concrete corruption path is still reproducible.

### 14. Make cancellation exception-safe
- Classification: **ALREADY FIXED**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:111-113`, `app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt:99-114`, `app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt:183-201`, `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunner.kt:110-145`
- Finding: the main outer layers now explicitly preserve cancellation semantics. `CancellationException` is rethrown or handled before generic catches, and the streaming planning path rethrows the original error instead of flattening it to a generic string.
- Note: `Turn.kt` still has a generic catch in the streaming wrapper, but in the current call chain it does not appear to relabel user stop as a generic session error.

### 15. Configurable approval timeout for eval/debug-run
- Classification: **NOT WORTH IT**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:38-40`, `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:166-179`, `app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionConfig.kt:12-55`
- Finding: the approval timeout is still hardcoded at 60 seconds and is still not configurable.
- Why I would cut it: this is an eval/debug-only concern, and timeout is probably the wrong knob anyway. If automation needs non-interactive behavior, the right lever is approval mode, not threading timeout config through the session stack.

### 16. Document stream partial-failure design
- Classification: **ALREADY FIXED**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryPolicy.kt:22-29`, `doc/main/infra/llm.md:101-106`
- Finding: the design is now documented explicitly: no retry after partial output because retries would duplicate irreversible output/actions.
- Call: remove this item from the plan.

### 17. Improve bootstrap/session failure UX
- Classification: **STILL VALID**
- Current code: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:304-320`, `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:431-488`, `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:192-199`, `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatEventReducer.kt:54-57`
- Finding: bootstrap and session-start failures are still surfaced mostly as toast/status text. The user's input still only enters chat on `TaskStarted`, so if startup fails before that event, the input disappears from the main chat history.
- Priority: raise from **P2** to **P1**.
- Note: scope this tightly. The important part is preserving the pending input and surfacing startup failure through the main chat/session UX, not just adding more status strings.

## Recommended Trimmed Plan

If I were rewriting `improvement_plan.md` today, I would keep this shortlist:

1. `#1` completion must depend on executed results
2. `#2` approval-dispatch failures must fail fast
3. `#8` `delegate_task` must return structural failure
4. `#6` emit `TASK_IMPOSSIBLE` distinctly from internal error
5. `#9` harden cleanup and post-failure observation capture
6. `#17` preserve user input and show bootstrap failures in chat/session UX
7. `#11` improve context-limit message opportunistically

Everything else should either be deleted as done, cut as not worth it, or redesigned before implementation.

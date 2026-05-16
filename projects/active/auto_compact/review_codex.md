# Auto-Compact Engineering Plan Review

## Plan summary

The design removes the hard `maxTurns` stop condition and replaces it with token-triggered history compaction: keep recent turns, summarize older history into a structured checkpoint, and continue. The direction is right. The current `HistoryManager.compress()` Phase 2 is lossy eviction, so replacing it with a real summary is the correct product and agent-quality move.

Success should mean:
- No production control flow stops because of a turn count.
- No prompt tells the model it has a finite turn budget.
- Compaction preserves the active user goal, supplements, tool-call consistency, and recent screen state.
- A compaction failure cannot corrupt or drop history.

I agree with the high-level call, but I would not implement the current plan as written. There are two correctness blockers and several integration gaps.

## What already exists

- `HistoryManager` already has a synchronized store, token estimates, `replaceAll()`, `forPrompt()` normalization, output truncation, mutation callbacks, and proactive old-screen downgrade.
- `Agent.kt` has the hard stop at top of loop and a recoverable-error retry gate that still reads `config.maxTurns` (`Agent.kt:83`, `Agent.kt:134`).
- `AgentTurnRunner` / `PromptBuilder` still use `maxTurns` to generate "Turn N/M" and "FINAL TURN" prompt text (`AgentTurnRunner.kt:207`, `PromptBuilder.kt:138`).
- `SessionAgentRunner` resolves the main agent model name but `TurnPlanningPhaseRunner` is the code that actually resolves model-specific `LLMClient` instances through `AgentModelResolver`.
- `SubAgentRunner` creates an isolated child `HistoryManager`, passes `resolvedRoleDef.maxTurns`, and converts `MaxTurnsReached` into a delegation failure summary (`SubAgentRunner.kt:85`, `SubAgentRunner.kt:103`, `SubAgentRunner.kt:134`).
- Hot-idle follow-up tasks reuse the same `SessionServices.historyManager`; `Agent.run()` appends a new `USER_INTENT` for each task instead of clearing prior task history (`Agent.kt:59`, `AgentSession.kt:302-408`).

## Findings

### [blocker] The proposed `[items[0], summary, ...kept]` layout can preserve the wrong user goal

`items[0]` is not a stable "current goal" in the existing session model. `Agent.run()` appends `MessageKind.USER_INTENT` at the start of every task (`Agent.kt:59`), while `AgentSession` keeps history across Hot Idle follow-up tasks. Supplements also append `USER_INTENT` mid-task (`AgentSession.kt:558`). That means `items[0]` can be a stale first task, while the active task or latest user amendment is later in history.

If compaction always keeps only `items[0]` exactly, a long follow-up session can restart with the wrong exact goal and a summarized active goal. The summary prompt also says `## Goal [The user's original task, verbatim.]`, which is actively wrong once there are supplements or follow-up tasks.

Fix before implementation:
- Make the current task goal an explicit, non-compactible prompt input from `AgentExecutionConfig.goal`, or preserve the latest `Goal:` `USER_INTENT` exactly during compaction.
- Treat supplement `USER_INTENT` messages as first-class user constraints. Either keep recent supplements exactly or add a `## User Updates / Constraints` section to the summary prompt.
- Do not assume there is exactly one user-intent item.

### [blocker] A suspending compaction followed by `replaceAll()` can drop concurrent supplements

`HistoryManager` is synchronized per operation, but compaction cannot hold that lock across an LLM call. The design reads a list, awaits summarization, then swaps with `replaceAll()` (`HistoryManager.kt:88`). During that await, `AgentSession.handleSupplement()` can append a `USER_INTENT` on another coroutine. The later `replaceAll()` would overwrite that supplement with a compacted list derived from the stale pre-supplement snapshot.

Fix before implementation:
- Add a mutation revision to `HistoryManager` and a `replaceAllIfRevision(expectedRevision, newItems)` API.
- `Compactor` should read `(revision, items)`, summarize, then CAS the replacement. If the revision changed, discard the summary and return `Skipped/Stale`; retry on the next turn.
- Do not count stale-snapshot skips as compaction failures.

### [major] The trigger estimates only history, not the actual next prompt

`historyManager.estimateTokenCount()` omits the system prompt, tool schemas, working memory, recalled memory, app skill, activated agent skills, current observation, and screenshot/image cost (`HistoryManager.kt:131`; `PromptBuilder.kt:48-54`). `PromptBuilder` adds all of those after history. Running compaction at top of turn, before perception, also misses the current screen observation that will be sent to the model.

That makes `tokens > contextWindow - reserve` a history-size trigger, not a context-window trigger. It can still hit provider context errors on large scratchpads/app skills/current screens, especially with the default 128k fallback.

Fix before implementation:
- Estimate the full next prompt, or use last API usage when available plus a conservative estimate of trailing additions, like pi/Claude/Codex do.
- At minimum, reserve must include static overhead plus current observation, not just next output and summary.
- Add a reactive fallback for provider "prompt too long/context window exceeded": compact/trim and retry once. This is not much code and is the safety net the prior art converges on.

### [major] `SCREEN_OBSERVATION` is not a sufficient safe-boundary model

Every normal turn captures a pre-turn screen, but the history shape is not "every boundary is a screen and every screen is enough":
- Post-action screen state is stored inside `FunctionCallOutput`, not as `MessageKind.SCREEN_OBSERVATION`.
- If the newest turn alone exceeds `keepRecentTokens`, walking backward can cross the threshold after the last `SCREEN_OBSERVATION`; there is no later screen to snap forward to.
- Supplements are `USER_INTENT` boundaries without a screen.
- Error recovery can leave partial turns such as screen-only history, assistant-only text, or a failed planning turn.
- Subagents use the same turn runner, but on a separate child history.

Pi's stronger rule is the one to copy: valid cut points are user messages, assistant messages, and other safe message starts; never cut at a tool result. For ClosePaw, safe cut points should include `USER_INTENT`, `SCREEN_OBSERVATION`, `ASSISTANT_TEXT`, and boundaries that do not separate a `FunctionCall` from its `FunctionCallOutput`. If a single turn is too large, either support split-turn summaries or deliberately keep the whole latest turn and compact older material.

### [major] Subagent wiring needs a compactor factory, not one session-level instance

`SubAgentRunner` creates a new `HistoryManager()` for every child agent and can use a different model (`subagentModel ?: mainModel`). Passing one session-level `Compactor` down "the same way other shared services are" is the wrong shape: it will be bound to the parent model/policy and, depending on implementation, may compact the wrong history.

Fix:
- Construct a compactor per `Agent` instance, using that agent's `config.modelName`, `services.modelCatalog`, and `services.llmClientFactory`/fallback `llmClient`.
- For child agents, build the compactor after `childServices` and `childModelName` are known.
- Remove `AgentRoleDef.maxTurns` / `ResolvedAgentRole.maxTurns`; keep `timeoutMs` as the subagent runaway guard. Convert the old max-turn delegation summary into a timeout/error summary.

### [major] `turnCount` is still a budget in multiple places

Keeping `turnCount` for observability is fine, but the current code still uses it as budget:
- `AgentTurnRunner` imports `isFinalTurn`, computes `finalTurn = isFinalTurn(turnNumber, config.maxTurns)`, and injects `FINAL TURN`.
- `PromptBuilder` renders `Turn $turnNumber/$maxTurns`, explicitly teaching the model a finite budget.
- `TurnBudget.kt` exists only for max-turn behavior and delegation summaries.
- `SubAgentRunner` branches on `AgentStopReason.MaxTurnsReached`.
- Tests assert final-turn and max-turn behavior.

After this change, prompt text should be `Turn N` at most. There should be no denominator and no final-turn warning.

### [major] The removal table misses several live integration points

The table catches many app/session references, but these also need explicit tasks:
- `agent/AgentTurnRunner.kt` final-turn warning path.
- `agent/cognition/prompt/PromptBuilder.kt` `maxTurns` parameter and `Turn N/M` text.
- `agent/cognition/policy/TurnBudget.kt` and `TurnBudgetAndDelegationSummaryTest`.
- `agent/definition/AgentRoleDef.kt` and `ResolvedAgentRole.maxTurns`.
- `trace/AgentTrace.kt`, `trace/TraceModels.kt`, `trace/TraceRecorderFactory.kt` `max_turns` metadata.
- `history/model/SessionRuntimeSnapshot.kt` / `ConversationConfigSnapshot.maxTurns`.
- `session/SessionHistoryBootstrapper.kt` still configures `autoCompress = true` and `maxTokenBudget = 18_000`; if the old call survives, lossy eviction still preempts the new compactor.
- `history/HistoryConfig.kt` fields tied only to old lossy compression: `maxTokenBudget`, `autoCompress`, `autoCompressThreshold`, `compressTargetRatio`, probably `recentWindowSize`.
- `ui/settings/SettingsModels.kt` `MAX_TURNS_OPTIONS`.
- `onboarding/OnboardingDemoController.kt` hard-coded `MAX_TURNS`, `SessionConfig(maxTurns = ...)`, and `TaskOutcome.MAX_TURNS` message.
- Eval bridge/config/tests: `eval/aw_bridge/native_agent_bridge.py`, `eval/aw_bridge/runner.py`, `eval/config/*.yaml`, and completion/trace parser tests still send or expect `max_turns` / `MAX_TURNS`.
- `doc/main/**` protocol, settings, capsule, loop, eval, and state-machine docs.

`scripts/debug-run.sh` has `DEBUG_MAX_TURNS`, but that is only a log monitoring cap for captured turn-start events. Keep it or rename it later to avoid confusion; it is not the production turn budget.

### [major] Compaction failure handling should not swallow cancellation

The design says cancellation during the LLM call surfaces as `Failed(CancellationException)`. That is the wrong Kotlin coroutine behavior. `CancellationException` should be rethrown so stop/interrupt/shutdown exits promptly and is not counted toward "3 compaction failures".

### [minor] Use a user/meta role for compaction summaries

`COMPRESSION_DIGEST` is currently assistant-role because it was a breadcrumb. A real handoff summary is closer to pi/Codex's "context checkpoint" user/meta message. Keeping it as assistant can make the model treat the summary as prior assistant output rather than system-provided context.

I would either:
- map `COMPACTION_SUMMARY` to user role with a clear prefix, or
- add a dedicated conversion path in `PromptBuilder` that wraps it as "Context checkpoint from previous work".

### [minor] Model catalog details need tightening

Adding `contextWindow` is straightforward, but:
- The catalog currently has 12 entries, not 11.
- Validate `context_window > 0`.
- Handle `contextWindow <= reserveTokens` explicitly instead of producing a negative threshold.
- Update the fallback catalog JSON.
- Decide how local backend contexts are represented. If local models can have smaller windows, the 128k default is unsafe.

## Prior Art To Adopt

- From pi-mono: valid cut points beyond user messages, split-turn handling, iterative previous-summary updates, and "first kept boundary" reasoning. We do not need file-operation tracking for Android, but the boundary logic is worth copying.
- From Claude Code: circuit breaker, no-tools summarizer preamble, prompt-too-long retry that drops oldest summarizer input when compaction itself exceeds context, and post-compact observability.
- From Codex: reactive compaction on context-window errors and filtering/reinjecting canonical context instead of trusting raw compactor output. For ClosePaw, this mainly means re-inject current goal/app skill/current observation canonically rather than only relying on summary text.

## Test plan

- `CompactorTest`: hot-idle history with multiple `USER_INTENT` goals preserves the active/current goal exactly.
- `CompactorTest`: supplement inserted before compaction cut is preserved in summary or kept exact; recent supplement is not lost.
- `HistoryManager`/`Compactor` concurrency test: fake summarizer suspends, supplement mutates history, replacement CAS fails or retries without data loss.
- Cut-point tests: threshold crosses inside `FunctionCallOutput`, after the last `SCREEN_OBSERVATION`, before a paired output, at a supplement `USER_INTENT`, and in a screen-only failed turn.
- Previous-summary tests: old summary replaced, not duplicated; update prompt gets old summary and only new prefix events.
- Agent loop tests: no `MaxTurnsReached`; recoverable error can retry even after turn 1; cancellation during compaction returns user stop, not compaction failure.
- Prompt tests: observation text contains `Turn N` or no turn text, never `Turn N/M` or `FINAL TURN`.
- Subagent tests: child agent compacts its isolated history with the child model policy; timeout still returns a useful delegation failure.
- Integration tests: checkpoint config reload ignores legacy `maxTurns`; traces no longer require `max_turns`; onboarding failure mapping compiles without `MAX_TURNS`; eval bridge no longer sends `--ei max_turns`.
- Device smoke: force a tiny context window/reserve to trigger compaction during `scripts/debug-run.sh "Open Settings"` and verify no max-turn UI/status appears.

## Not in scope

- Mode-specific compaction prompts.
- Branch/tree summarization.
- Long-term memory retention changes.
- Reworking the full trace schema beyond removing max-turn metadata and adding useful compaction events.

## Next step

Revise the design before `/implement`. The minimum changes I would require are:
1. Add a history revision/CAS replacement API.
2. Make current goal and supplements canonical instead of relying on `items[0]`.
3. Replace the `SCREEN_OBSERVATION` cut rule with generic safe cut points and split/latest-turn behavior.
4. Change the trigger to estimate the full prompt or add a reactive context-error retry.
5. Expand the removal tasks to include `TurnBudget`, prompt budget text, role definitions, trace, onboarding, eval, and `HistoryConfig`/bootstrap cleanup.

After those changes, the implementation should be feasible and smaller than the current lossy compression machinery it replaces.

# Codex review — auto-compact implementation (v2)

Reviewed at commit eda15044d968c5502f57de44cf03c4cbdd030630

## Blocker

### [B1] Reactive prompt-too-long recovery is not wired into the real turn path

`app/src/main/kotlin/ai/closepaw/agent/TurnPlanningPhaseRunner.kt:55-60,98-135` and `app/src/main/kotlin/ai/closepaw/agent/Turn.kt:92-118`

`Turn.runStreaming()` has optional compactor wiring, but the production `TurnPlanningPhaseRunner` constructs `Turn` with only `toolRegistry`, `llmClient`, and `allowedToolNames`. A real `ContextWindowExceededException` therefore hits the `cap == null` branch and surfaces as an error instead of compacting. Even after wiring those constructor args, the retry loop would still call `streamOnce(systemPrompt, inputItems, model)` with the same immutable `inputItems` that already exceeded the context window; `forceCompactNow()` mutates `HistoryManager`, but the prompt is not rebuilt from that history before retry. The `forceCompactNow()` outcome is also ignored, so `Failed`, `Stale`, or `NothingToCompact` still get an identical retry.

This means the design's Layer 2 safety net is effectively absent. Static overhead drift, large app skills, screenshots, and small-window local models can still fail the task instead of doing the required one-shot compact-and-retry.

Suggested fix: move the context-window catch to `TurnPlanningPhaseRunner`, or pass `Turn` a request factory that can rebuild prompt input after compaction. On first `ContextWindowExceededException`, run `forceCompactNow()`, rebuild `PromptBuilder.buildInputItems(...)` from the updated history, record the retry trace, and retry once. If compaction does not actually reduce history, propagate the original exception with a clear message rather than retrying the same payload.

**Fixed in 35c0ccda.** `Agent` → `AgentTurnRunner` → `TurnPlanningPhaseRunner` now plumb the `Compactor` into `Turn`. `Turn.runStreaming` accepts a `rebuildInputItems` lambda; `TurnPlanningPhaseRunner` supplies one that re-runs `PromptBuilder.buildInputItems(...)` against the now-shorter history. On `Compacted`, the retry uses the rebuilt prompt. `Failed`/`Stale`/`NothingToCompact` propagate `ContextWindowExceededException("Compaction could not reduce history below context window …")` instead of silently retrying the same payload. `TurnReactiveCompactionTest` was extended with assertions that the rebuilt payload differs from the original and that the four non-Compacted outcomes do not retry.

Touched: `app/src/main/kotlin/ai/closepaw/agent/Turn.kt`, `app/src/main/kotlin/ai/closepaw/agent/TurnPlanningPhaseRunner.kt`, `app/src/main/kotlin/ai/closepaw/agent/AgentTurnRunner.kt`, `app/src/main/kotlin/ai/closepaw/agent/Agent.kt`, `app/src/test/kotlin/ai/closepaw/agent/TurnReactiveCompactionTest.kt`.

### [B2] Compaction summaries use the catalog key instead of the provider model id

`app/src/main/kotlin/ai/closepaw/history/Compactor.kt:213-218`, `app/src/main/assets/llm_models.json:23-55`, and `app/src/main/kotlin/ai/closepaw/protocol/SessionConfig.kt:34-37`

`Compactor.summarize()` calls `llmClient.chatWithTools(..., model = model.name)`. For the default `mainModel = "glm-5"`, the catalog key is `"glm-5"` but the OpenRouter model id is `"z-ai/glm-5"`. Several other catalog entries also differ (`minimax/minimax-m2.5`, `moonshotai/kimi-k2.5`, `zai-org/autoglm-phone-9b-multilingual`). Normal turn execution uses the resolved `modelId`; compaction does not.

On the default model, the first proactive compaction summary call is likely sent with an invalid provider model string. That turns compaction into repeated `Failed(...)` outcomes and eventually trips the three-failure circuit breaker in `Agent`.

Suggested fix: send `model.modelId` from `Compactor.summarize()`, and add a unit test with `ModelEntry(name = "alias", modelId = "provider/model")` asserting the summarizer receives `provider/model`.

**Fixed in 253b7b18.** `Compactor.summarize` now sends `model.modelId`. `CompactorTest` gained `summarize is sent with provider modelId not the catalog alias`, which uses an `alias` / `provider/model` `ModelEntry` and asserts the captured model string.

Touched: `app/src/main/kotlin/ai/closepaw/history/Compactor.kt`, `app/src/test/kotlin/ai/closepaw/history/CompactorTest.kt`.

## Major

### [M1] The cut-point rule still fails common FunctionCallOutput tail cases

`app/src/main/kotlin/ai/closepaw/history/Compactor.kt:157-178` and `app/src/test/kotlin/ai/closepaw/history/CompactorTest.kt:124-137`

`findSafeCutPoint()` scans forward from the token hit and refuses to cut when the retained tail ends in a large `FunctionCallOutput`. The test even documents that "the only safe boundary is the FC before it", then expects `items.size`. That is too conservative: with old history before the call, cutting at the paired `FunctionCall` would keep the call/output pair intact while still compacting the older prefix.

The current item-type rule is also not group-aware. If history ever contains multiple function calls followed by consecutive outputs, treating any `FunctionCall` as safe can keep an output whose corresponding call was summarized away.

Suggested fix: choose cut points over normalized chunks, not individual items. Treat a function call plus its output as an atomic group; when the threshold lands in a terminal output, snap to the paired call if that preserves the pair. Return `NothingToCompact` only when the newest atomic group itself is all that can be kept.

**Fixed in 374fa120.** `findSafeCutPoint` is now group-aware via a precomputed `unpairedFcoAt[]` (callId-matched FC/FCO grouping). When the threshold lands inside an FC/FCO group the cut snaps backward to the start of the group, so the pair is never split and an older prefix that *is* summarizable is no longer thrown away as `NothingToCompact`. The grouping is by `callId`, so interleaved multi-call sequences are handled correctly. New tests in `CompactorTest` cover: snap-back at a paired FC, snap-back when only the paired FC is the summarizable prefix, multi-pair tails staying intact, the existing supplement-boundary case (now snapping to FC instead of forward to USER_INTENT), and the "nothing summarizable remains" case still returning `items.size`.

Touched: `app/src/main/kotlin/ai/closepaw/history/Compactor.kt`, `app/src/test/kotlin/ai/closepaw/history/CompactorTest.kt`.

### [M2] Context-window classification is not applied to failed stream events or local failures

`app/src/main/kotlin/ai/closepaw/agent/Turn.kt:176-179,119-122`, `app/src/main/kotlin/ai/closepaw/llm/CloudStreamRetryRunner.kt:129-150`, and `app/src/main/kotlin/ai/closepaw/llm/LFMLLMClient.kt:237-261`

The context-window classifier is private to cloud `streamWithRetry()`. If a client surfaces overflow as `LLMStreamEvent.Failed("prompt_too_long" / "exceeded max context length" / similar)`, `Turn.streamOnce()` wraps it in a plain `RuntimeException`, and `runStreaming()` takes the generic error catch instead of the compaction catch. The local LFM client catches generation exceptions and emits `Failed(...)`, so local overflow cannot currently become `ContextWindowExceededException`. The classifier also misses common wording like "maximum context length" even though `TurnErrorClassifier` already recognizes it.

Suggested fix: extract a shared context-window classifier and apply it in `Turn.streamOnce()` before throwing from `LLMStreamEvent.Failed`, in the generic `runStreaming()` catch before emitting `Error`, and in non-streaming retry classification if that path remains supported. Add tests for `prompt_too_long`, HTTP 413 text, `request_too_large`, `context_length_exceeded`, "maximum context length", and local/Ollama-style overflow strings.

**Fixed in 31eef64f.** Extracted `classifyContextWindowExceeded(message, cause?, httpCode?)` plus a `Throwable` overload into `app/src/main/kotlin/ai/closepaw/llm/ContextWindowClassifier.kt`. `CloudStreamRetryRunner` switched to the shared helper. `Turn.streamOnce` now classifies the `LLMStreamEvent.Failed` error string before throwing the generic `RuntimeException`, and `Turn.runStreaming`'s generic catch reclassifies arbitrary exceptions and routes overflow to the compaction path. The expanded pattern set adds `context_length_exceeded`, `maximum context length`, and `payload too large`, plus an explicit `httpCode == 413` short-circuit. `ContextWindowClassifierTest` covers all patterns plus the cause-chain unwrap, the explicit `httpCode` argument, and false-positive avoidance for unrelated `413` substrings. LFM-side change unnecessary — its `Failed` emission already carries the original exception message, which the new `Turn` reclassification handles.

Touched: `app/src/main/kotlin/ai/closepaw/llm/ContextWindowClassifier.kt`, `app/src/main/kotlin/ai/closepaw/llm/CloudStreamRetryRunner.kt`, `app/src/main/kotlin/ai/closepaw/agent/Turn.kt`, `app/src/test/kotlin/ai/closepaw/llm/ContextWindowClassifierTest.kt`.

## Minor

### [m1] Dead max-turn policy code and tests remain in source

`app/src/main/kotlin/ai/closepaw/agent/cognition/policy/TurnBudget.kt:5-15,40-42` and `app/src/test/kotlin/ai/closepaw/agent/cognition/policy/TurnBudgetAndDelegationSummaryTest.kt:8-60`

The old `isFinalTurn(maxTurns)` helper and turn-limit delegation formatter still compile and are still tested. They do not appear reachable from the new agent loop, so this is not prompt leakage, but it violates the design task that deletes `TurnBudget.kt` and leaves a misleading "turn limit" abstraction available for future re-use.

Suggested fix: delete `TurnBudget.kt` and the test, or rewrite only the delegation summary helper around timeout/error without a `maxTurns` parameter or "turn limit" language.

**Fixed in 4593ff50.** Both files deleted (only their own unit test referenced them).

### [m2] `maxSummaryTokens` is declared but never enforced

`app/src/main/kotlin/ai/closepaw/history/Compactor.kt:65,213-218`

`Compactor` exposes `maxSummaryTokens = 5_000`, matching the design defaults, but the LLM call has no max-token/output cap. A verbose summarizer can return an oversized checkpoint and make compaction ineffective.

Suggested fix: either plumb an output-token limit through `LLMClient.chatWithTools()` for summary calls, or remove the unused parameter and document that summary length is prompt-only until the client API supports it.

**Deferred (see ac-compactor-output-cap).** Tracked as a follow-up under parent `auto-compact` in `projects/tasks.json` because enforcing the cap requires extending the shared `LLMClient.chatWithTools` API surface across every provider implementation, not just `Compactor`.

**Fixed in ac-compactor-output-cap.** `LLMClient.chatWithTools` gained an optional `maxOutputTokens: Long? = null` parameter. `OpenAIResponseClient` forwards it as `max_output_tokens` on the Responses API; `ChatCompletionClient` forwards it as `max_completion_tokens`. `CodexResponseClient` accepts the parameter but cannot forward it (the Codex backend forbids `max_output_tokens`, see `CodexRequestBuilder`). `LFMLLMClient` has no native SDK knob, so it enforces the cap client-side by truncating the streamed text at `maxOutputTokens * 4` chars (consistent with `HistoryManager.estimateTokenCount`). `Compactor.summarize` now passes `maxSummaryTokens`. Tests: `CompactorTest.summarize forwards maxSummaryTokens as maxOutputTokens to the LLM call` covers the Compactor → abstract `LLMClient` surface; `ChatCompletionClientTest.buildParams forwards maxOutputTokens as maxCompletionTokens` plus a null-cap counterpart cover the cloud path; `LFMLLMClientTest.chatWithTools truncates streamed text once maxOutputTokens cap is hit` plus a no-cap counterpart cover the local path.

Touched: `app/src/main/kotlin/ai/closepaw/llm/LLMClient.kt`, `app/src/main/kotlin/ai/closepaw/llm/OpenAIResponseClient.kt`, `app/src/main/kotlin/ai/closepaw/llm/ChatCompletionClient.kt`, `app/src/main/kotlin/ai/closepaw/llm/CodexResponseClient.kt`, `app/src/main/kotlin/ai/closepaw/llm/LFMLLMClient.kt`, `app/src/main/kotlin/ai/closepaw/history/Compactor.kt`, `app/src/test/kotlin/ai/closepaw/history/CompactorTest.kt`, `app/src/test/kotlin/ai/closepaw/llm/ChatCompletionClientTest.kt`, `app/src/test/kotlin/ai/closepaw/llm/LFMLLMClientTest.kt`, plus signature updates to existing `LLMClient` test doubles.

### [m3] `HistoryManager.compress()` still mutates without moving the CAS revision

`app/src/main/kotlin/ai/closepaw/history/HistoryManager.kt:190-207`

The design says all history mutations must bump the revision. `compress()` still normalizes items, clears/re-adds them, downgrades screens, and invokes `onMutation`, but it never increments `_revision`. There are no production callers in the current grep, so the risk is mostly future-facing, but it weakens the CAS invariant that `Compactor` relies on.

Suggested fix: delete `compress()` if it is now test-only legacy API, or bump `_revision` whenever it mutates `items`.

**Fixed in 4593ff50.** `compress()` now bumps `_revision` after the normalize/replace pass. Kept the method itself (and its tests) rather than deleting because the unit tests use it as a black-box exercise of normalization/downgrade.

### [m4] Legacy `COMPRESSION_DIGEST` records are not migrated to `COMPACTION_SUMMARY`

`app/src/main/kotlin/ai/closepaw/history/model/HistoryItemConverter.kt:51-56`

Unknown persisted `kind` strings fall back to `USER_INTENT`. A pre-v2 record with `kind = "COMPRESSION_DIGEST"` will reload as user intent, despite docs saying the rename is handled.

Suggested fix: explicitly map `"COMPRESSION_DIGEST"` to `MessageKind.COMPACTION_SUMMARY` before the enum lookup fallback.

**Fixed in 4593ff50.** Explicit early-return for `"COMPRESSION_DIGEST"` added in `resolveMessageKind`, plus a new `HistoryItemConverterTest` case `fromRecord migrates legacy COMPRESSION_DIGEST kind to COMPACTION_SUMMARY`.

## Praise

- The supplement CAS race is materially closed in the main compaction path: `HistoryManager.snapshot()` and `replaceAllIfRevision()` are synchronized, and `Compactor` discards stale summaries when the revision moves (`HistoryManager.kt:115-132`, `Compactor.kt:99,139-145`).
- `PromptBuilder` no longer renders `Turn N/M` or `FINAL TURN`, and `COMPACTION_SUMMARY` is serialized as user-role checkpoint context with a clear prefix (`PromptBuilder.kt:135-137,172-179`, `ResponseItem.kt:18-23`).
- `SubAgentRunner` constructs a fresh child `HistoryManager` and child `Compactor` after resolving the child model name, so child compaction is not accidentally sharing the parent's history or compactor instance (`SubAgentRunner.kt:90-104,156-181`).
- `evalTurnBudget` is plumbed from debug intent to `SessionConfig`, then into `AgentExecutionConfig`, and the stop message is clear and eval-scoped (`MainActivityIntentPayload.kt:126-152`, `MainActivity.kt:682-705`, `SessionAgentRunner.kt:87-99`, `Agent.kt:115-122`).

## Verification

After all fixes applied:

- `./gradlew :app:testDebugUnitTest --tests "ai.closepaw.history.CompactorTest"` — green.
- `./gradlew :app:testDebugUnitTest --tests "ai.closepaw.agent.TurnReactiveCompactionTest" --tests "ai.closepaw.agent.TurnPlanningPhaseRunnerTest"` — green.
- `./gradlew :app:testDebugUnitTest --tests "ai.closepaw.llm.ContextWindowClassifierTest" --tests "ai.closepaw.llm.*"` — green.
- `./gradlew :app:testDebugUnitTest --tests "ai.closepaw.history.model.HistoryItemConverterTest" --tests "ai.closepaw.history.HistoryManagerTest"` — green.
- `./gradlew :app:testDebugUnitTest` (full app unit test suite) — green.


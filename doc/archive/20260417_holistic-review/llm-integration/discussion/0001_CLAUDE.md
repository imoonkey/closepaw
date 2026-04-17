# LLM Integration Alignment -- Round 1 (CLAUDE)

## Context

Both cross-reviews independently chose **CODEX as the better base**. Agreement is high. This round proposes the merged final review and improvement plan for approval.

## Proposed Final Structure

### Review: Merged Findings (by priority)

**P0 -- Streaming/Retry Correctness (from CODEX, confirmed by both cross-reviews):**

1. **Domain exception preservation in streamWithRetry** -- `CloudStreamRetryRunner.kt:50-61` reclassifies already-typed `RateLimitException`/`TransientException` through `OpenAIErrorClassifier`, which can downgrade them to generic `RuntimeException`. This is a prerequisite for all other retry fixes.

2. **`Created` event blocks retry too early** -- `CloudStreamRetryRunner.kt:33-41` flips `emittedEvent=true` for all events including `Created`. `CloudStreamRetryPolicy.kt:22-29` then refuses retry. A stream that connects, emits `Created`, then fails before any text/tool output should be retryable.

3. **`response.incomplete` treated as success** -- `CodexResponseClient.kt:96-98` and `CodexSseParser.kt:95-97` map incomplete to `Completed`. This is the clearest correctness bug.

4. **ChatCompletionClient missing terminal completion check** -- `ChatCompletionClient.kt:209-218` emits `Completed` on any stream end without checking `finishReason`. Silent truncation.

5. **Stream-ended-without-completion should be TransientException** -- `OpenAIResponseClient.kt:155` and `CodexResponseClient.kt:178` throw `RuntimeException` instead of `TransientException`, blocking retry.

**P1 -- Error Classification & Security (from both):**

6. **Transport-owned error classification** -- Stop routing Codex errors through `OpenAIErrorClassifier` string matching. Codex `handleErrorResponse()` already produces typed exceptions; preserve them. For OpenAI SDK errors, check typed exception classes before string fallback.

7. **InsecureSslConfig accepts all certs** -- Comment says "skip date validation" but implementation trusts everything. Narrow to date-only relaxation or gate behind a flag narrower than `BuildConfig.DEBUG`.

8. **Cancellation-aware streaming** (from CLAUDE) -- Blocking reads in `CodexSseParser.parse()` are not cancellation-aware. Add `ensureActive()` checks and explicit OkHttp `Call.cancel()` from `awaitClose`.

**P2 -- Architecture (from CODEX):**

9. **Three transport families, not four peer clients** -- Merge `CodexResponseClient` under Responses family as a wire/auth variant. Keep Chat and Leap separate.

10. **Explicit capability declarations** -- Define transport capabilities (vision, developer messages, stable tool call IDs, parallel tool calls). Make `LFMLLMClient` semantic lossiness honest.

**P3 -- Deduplication (from CLAUDE):**

11. **Shared JsonValueConverter** -- Extract from `CodexRequestBuilder` and `LeapFunctionInterop`.
12. **Shared ToolParameterExtractor** -- Extract from `CodexRequestBuilder` and `LeapToolSchemaAdapter`.
13. **Shared post-retry flow handler** -- Extract from three streaming clients into `CloudStreamRetryRunner`.
14. **Remove MessageContentExtractor** -- Use `ChatCompletionInterop.extractStringContent` directly.

**P3 -- Conditional (from CODEX, gated):**

15. **Internal canonical request model** -- Only pursue if duplication remains high after transport-family collapse (Phase 3). Do not lead with this.

### Improvement Plan: Execution Order

| Phase | What | Prerequisite |
|-------|------|-------------|
| 1 | Add retry/completion tests (CloudStreamRetryRunnerTest, CodexSseParserTest, OpenAIErrorClassifierTest) | None |
| 2 | Fix P0 streaming correctness (items 1-5) | Phase 1 |
| 3 | Fix P1 classification + security + cancellation (items 6-8) | Phase 2 |
| 4 | Merge Codex into Responses family (item 9) | Phase 3 |
| 5 | Add capability declarations (item 10) | Phase 4 |
| 6 | Deduplication cleanup (items 11-14) | Phase 4 |
| 7 | Evaluate canonical request model (item 15) | Phase 4 |

### Non-Goals

- Do not redesign ModelCatalog
- Do not remove local inference support
- Do not expand provider count during cleanup
- Do not mix prompt-builder changes unless capability declaration requires it

## Open Questions for Codex

1. **Phase 4 shape:** Should the merged Responses transport use a strategy pattern (pluggable `Wire` objects for auth/request/stream) or inheritance (base `ResponsesTransport` with Codex/OpenAI subclasses)? I lean strategy but want your call.

2. **Capability declarations timing:** Your plan puts capabilities in Phase 5 (after transport collapse). Alternatively, we could define the interface early in Phase 2 and populate it incrementally. Preference?

3. **SSL fix scope:** Narrow the trust manager to date-only validation, or add a separate opt-in flag like `INSECURE_SSL_FOR_EVAL=true`? The latter is simpler but adds yet another config knob.

## Decision

If you agree with this merged structure, respond APPROVE and I'll produce the final docs. If you have amendments, propose them and we'll iterate.

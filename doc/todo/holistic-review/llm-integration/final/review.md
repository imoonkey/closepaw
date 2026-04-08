# LLM Integration Module -- Final Review

**Source:** Double-design review (Claude + Codex), cross-reviewed and aligned.
**Date:** 2026-04-08
**Status:** APPROVED
**Base design:** CODEX (with CLAUDE hardening additions)

---

## Executive Summary

The `llm/` module works but has correctness bugs in its highest-risk area: streaming completion and retry semantics. The architecture is close to solid -- clean model catalog, good factory pattern, correct thread safety -- but the client taxonomy is one level too flat (four peer clients instead of three transport families), and the failure paths are fragile.

The immediate priority is fixing streaming correctness. After that, the module should be simplified by merging the Codex client into the Responses transport family and making capability differences explicit.

---

## Architecture Assessment

**What works well:**
- `ModelCatalog` is clean, immutable, and extensible (A grade)
- `LLMClientFactory` keeps provider/base-url/api selection out of call sites
- `LLMClient` gives callers one surface for streaming and non-streaming calls
- Thread safety is correct throughout (ConcurrentHashMap, Mutex, volatile)
- `LlmLogger` is properly gated behind `BuildConfig.DEBUG`

**What needs fixing:**
- Streaming completion semantics are split across four clients, a retry runner, a retry policy, an SSE parser, and a classifier -- too many places for "what counts as success/failure/retry"
- Request normalization is duplicated in three places (`ChatCompletionInterop`, `CodexRequestBuilder`, `LFMLLMClient`/`LeapFunctionInterop`)
- Error classification relies on fragile string matching and doesn't preserve pre-typed exceptions

---

## Findings by Priority

### P0: Streaming/Retry Correctness

**1. Domain exception preservation in streamWithRetry** (HIGH)
- **Files:** `CloudStreamRetryRunner.kt:50-61`, `OpenAIErrorClassifier.kt:11-52`
- `streamWithRetry()` reclassifies every caught exception through `OpenAIErrorClassifier`, even when the exception is already a domain-level `RateLimitException` or `TransientException`. The classifier does not preserve these types, so a Codex `RateLimitException` from `handleErrorResponse()` can be downgraded to a generic `RuntimeException`, silently disabling retry.
- This is a **prerequisite** for all other retry fixes.

**2. `Created` event blocks retry too early** (HIGH)
- **Files:** `CloudStreamRetryRunner.kt:33-41`, `CloudStreamRetryPolicy.kt:22-29`
- `streamWithRetry()` flips `emittedEvent=true` for all events including `Created`. The retry policy then refuses retry after any emitted event. A stream that connects, emits `Created`, then fails before any text/tool-call payload will not retry, even though retry is safe (no user-visible duplication).
- Fix: Distinguish between metadata events (`Created`) and irreversible output events (`TextDelta`, `ToolCallDone`).

**3. `response.incomplete` treated as success** (HIGH)
- **Files:** `CodexResponseClient.kt:96-98`, `CodexSseParser.kt:95-97`
- Both streaming and non-streaming Codex paths map `response.incomplete` to `Completed`. An explicitly incomplete response should never surface as success.
- Fix: Map to `Failed` with the backend-provided reason. Log partial text but do not mark the turn successful.

**4. ChatCompletionClient missing terminal completion check** (HIGH)
- **File:** `ChatCompletionClient.kt:209-218`
- Emits `Completed` whenever the SDK stream loop ends normally, without tracking whether a terminal `finishReason` was seen. A prematurely-closed stream produces an incomplete response that looks complete.
- Fix: Track `sawFinishReason` flag. If stream ends without one, throw `TransientException`.

**5. Stream-ended-without-completion should be TransientException** (HIGH)
- **Files:** `OpenAIResponseClient.kt:155`, `CodexResponseClient.kt:178`
- Throws `RuntimeException("Stream ended without completion event")` instead of `TransientException`. This prevents retry of the most common transient failure: connection drop before any events.

### P1: Error Classification, Security, Cancellation

**6. Transport-owned error classification** (MEDIUM)
- **Files:** `OpenAIErrorClassifier.kt`, `CodexResponseClient.kt:242-260`
- Stop routing Codex errors through `OpenAIErrorClassifier` string matching. Codex `handleErrorResponse()` already produces typed exceptions -- preserve them. For OpenAI SDK errors, check typed exception classes (`RateLimitException`, `InternalServerException`) before falling through to string matching.
- The current string matching has false-positive risk: "14291" matches "429", "5002" matches "500".

**7. InsecureSslConfig accepts all certificates** (MEDIUM)
- **File:** `InsecureSslConfig.kt:36-40`
- Comment says "skip certificate date validation" but implementation trusts every server certificate. Debug builds carry real API keys and OAuth tokens.
- Fix: Gate behind an explicit eval-only flag narrower than `BuildConfig.DEBUG`. Optionally pursue date-only trust relaxation later.

**8. Cancellation-aware streaming** (MEDIUM)
- **Files:** All streaming clients, `CodexSseParser.kt`
- Blocking reads in `CodexSseParser.parse()` are not cancellation-aware. Cancelled flows can hang until HTTP read timeout (120 seconds).
- Fix: Add `coroutineContext.ensureActive()` in stream iteration loops. For Codex, store OkHttp `Call` reference and cancel from `awaitClose`.

### P2: Architecture

**9. Three transport families, not four peer clients** (STRUCTURAL)
- `CodexResponseClient` is a transport/auth variant of the Responses family, not a genuinely different protocol. The differences (auth, request encoding, stream decoding) are wire concerns, not semantic transport concerns. Treating Codex as a peer client preserves fragmentation in request building, stream accumulation, and retry handling.
- Fix: Merge into a Responses-family transport with pluggable strategy objects (request encoder, stream decoder, auth/header provider, error classifier). Use composition, not inheritance.

**10. Explicit capability declarations** (STRUCTURAL)
- `LFMLLMClient` is semantically lossy relative to cloud transports: drops non-user/non-assistant roles, flattens content through untyped `Any` helper, generates random tool call IDs, replays tool outputs without call-ID correlation. This capability loss is implicit.
- Fix: Define `LlmCapabilities` data class (vision, developer messages, stable tool call IDs, parallel tool calls, streaming). Make transports declare capabilities. Enforce or degrade explicitly.

### P3: Deduplication

**11. Shared JsonValueConverter** -- Extract from `CodexRequestBuilder` and `LeapFunctionInterop` (~40 lines duplication).

**12. Shared ToolParameterExtractor** -- Extract from `CodexRequestBuilder.convertToolParameters()` and `LeapToolSchemaAdapter.parseToolParameters()` (~30 lines duplication).

**13. Shared post-retry flow handler** -- Extract 10-line post-retry cleanup block repeated in three streaming clients into `CloudStreamRetryRunner.handleRetryResult()`.

**14. Remove MessageContentExtractor** -- Make `ChatCompletionInterop.extractStringContent` internal and use directly from `LFMLLMClient` and `LlmLogger`.

### P3: Conditional

**15. Internal canonical request model** -- Only pursue if duplication remains high after the Responses-family collapse. This is a good tool but should not become mandatory architecture before simpler cleanup has landed.

---

## Scorecard

| Area | Grade | Notes |
|------|-------|-------|
| Architecture | B+ | Clean abstractions, one level too flat |
| Streaming Correctness | C | Multiple completion/retry bugs |
| Error Handling | C+ | Works in happy path, fragile classification |
| Retry Logic | B- | Correct policy design, implementation gaps |
| Thread Safety | A | Correct throughout |
| Code Duplication | C+ | Three-way request normalization, streaming boilerplate |
| Cancellation | C | No explicit support in stream loops |
| Test Coverage | C | Only catalog/factory/local tested; streaming/retry untested |
| Config/Catalog | A | Clean, immutable, extensible |
| Security | B- | Debug SSL too broad, otherwise appropriate |

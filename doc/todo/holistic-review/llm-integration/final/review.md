# LLM Integration Module -- Final Review

**Source:** Double-design review (Claude + Codex), cross-reviewed, aligned, and verified.
**Date:** 2026-04-08 (verified 2026-04-10)
**Status:** VERIFIED
**Base design:** CODEX (with CLAUDE hardening additions)
**Verification:** 13 confirmed real, 2 dropped as false positives, KISSer alternatives adopted

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

**6. Error classification fragile and leaks cross-transport** (MEDIUM)
- **Files:** `OpenAIErrorClassifier.kt`, `CodexResponseClient.kt:242-260`
- Codex `handleErrorResponse()` already produces typed exceptions -- preserve them (done by fix #1). For OpenAI SDK errors, add typed exception branches before string fallback.
- Do NOT build a separate transport-classifier abstraction. Keep one `OpenAIErrorClassifier` with fast-paths for domain exceptions and typed SDK exceptions. That's KISS enough.

**7. InsecureSslConfig accepts all certificates** (MEDIUM)
- **File:** `InsecureSslConfig.kt:36-40`
- Comment says "skip certificate date validation" but implementation trusts every server certificate. Debug builds carry real API keys and OAuth tokens.
- Fix: Gate behind an explicit eval-only flag narrower than `BuildConfig.DEBUG`. Optionally pursue date-only trust relaxation later.

**8. Cancellation-aware streaming** (MEDIUM)
- **Files:** `CodexResponseClient.kt`, `CodexSseParser.kt`
- Blocking reads in `CodexSseParser.parse()` are not cancellation-aware. Cancelled flows can hang until HTTP read timeout (120 seconds).
- Fix: Store OkHttp `Call` reference in `CodexResponseClient`, cancel from `awaitClose`. This is the primary fix -- `ensureActive()` alone only helps once the loop regains control after a blocking read.

### P2: Architecture

**9. Codex and OpenAI Responses clients share duplicated logic** (STRUCTURAL)
- `CodexResponseClient` is a transport/auth variant of the Responses family, not a genuinely different protocol. Stream accumulation, completion checks, and retry epilogues are duplicated.
- Fix: Extract shared Responses helpers first (request/result accumulation, completion checks, retry epilogue). Only collapse into a single transport class if meaningful duplication remains after helpers are extracted. Don't over-engineer the strategy pattern upfront.

**10. Local capability loss is implicit** (STRUCTURAL)
- `LFMLLMClient` drops non-user/non-assistant roles, flattens content through untyped `Any` helper, generates random tool call IDs, replays tool outputs without call-ID correlation.
- Fix: Add a narrow `LocalLlmSemantics` object declaring the specific limitations the rest of the app needs to reason about. Don't build a broad generic `LlmCapabilities` framework until there's a second consumer.

### P3: Deduplication

**11. Shared ToolParameterExtractor** -- Extract from `CodexRequestBuilder.convertToolParameters()` and `LeapToolSchemaAdapter.parseToolParameters()` (~30 lines duplication). One helper returning `JSONObject?`, callers decide fallback.

**12. Shared post-retry flow handler** -- Extract 10-line post-retry cleanup block repeated in three streaming clients into `CloudStreamRetryRunner.handleRetryResult()`. May be subsumed by Responses helper extraction (#9).

### P0 (Bug): Content Extraction

**13. MessageContentExtractor feeds garbage to Leap** (HIGH)
- **Files:** `MessageContentExtractor.kt`, `LFMLLMClient.kt:299`, `LlmLogger.kt:33`, `LlmInputItemsTraceSerializer.kt:25`
- `MessageContentExtractor.extractMessageContent(Any)` receives `EasyInputMessage.Content` but falls through to `toString()`, feeding wrapper strings like `Content{textInput=...}` into Leap instead of the actual text. This is a **functional bug**, not just deduplication.
- Fix: Create a typed `extractStringContent(content: EasyInputMessage.Content): String` utility. Update all four call sites. Delete `MessageContentExtractor.kt`.

### Dropped (false positives from pre-verification review)

**~~JsonValueConverter extraction~~** -- Codex and Leap implementations have only partial overlap (Codex preserves `JSONObject.NULL`, Leap returns Kotlin `null` and reparses raw JSON strings). Not a clean shared utility.

**~~Internal canonical request model~~** -- No present defect proves this is needed. Defer entirely; evaluate only after concrete cleanup lands.

---

## Scorecard

| Area | Grade | Notes |
|------|-------|-------|
| Architecture | B+ | Clean abstractions, one level too flat |
| Streaming Correctness | B+ | P0 bugs fixed: domain exception preservation, retry policy, incomplete handling, finishReason check |
| Error Handling | B+ | Typed SDK exceptions first, domain preserved, string fallback last |
| Retry Logic | B+ | Domain exceptions preserved, metadata vs output distinguished |
| Thread Safety | A | Correct throughout |
| Code Duplication | C+ | Three-way request normalization, streaming boilerplate |
| Cancellation | B+ | Codex stream cancelled via OkHttp Call on flow close; SDK clients not yet covered |
| Test Coverage | B | 63 tests; 3 known-bug remaining (classifier false-positives, Phase 3) |
| Config/Catalog | A | Clean, immutable, extensible |
| Security | B+ | Debug SSL gated behind INSECURE_SSL_FOR_EVAL build flag |

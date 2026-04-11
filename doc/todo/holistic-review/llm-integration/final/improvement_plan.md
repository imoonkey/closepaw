# LLM Integration -- Final Improvement Plan

**Source:** Double-design review (Claude + Codex), cross-reviewed, aligned, and verified.
**Date:** 2026-04-08 (verified 2026-04-10)
**Status:** VERIFIED
**Changes from verification:** 2 false positives dropped, KISSer alternatives adopted, #14 elevated to P0 bug

---

## Principles

1. Fix correctness before refactoring shape
2. One semantic contract for stream completion and retry
3. Transport differences belong below the shared interface
4. Unsupported capabilities must be explicit, not silently degraded
5. Keep the model catalog simple and data-driven

---

## Phase 1: Add Streaming/Retry Tests — DONE

**Prerequisite:** None
**Status:** DONE (11d76d0f, 2026-04-10)

Added 4 test classes (62 tests) locking down current behavior including 6 KNOWN BUG tests that will flip when fixes land:

| Test | Target | Status |
|------|--------|--------|
| `CloudStreamRetryRunnerTest` | Retry semantics, domain exception preservation, emittedEvent tracking, virtual-time backoff assertions | DONE |
| `CloudStreamRetryPolicyTest` | Policy decisions for metadata vs irreversible events | DONE |
| `CodexSseParserTest` | Event mapping, incomplete handling, malformed input resilience, tool call accumulation | DONE |
| `OpenAIErrorClassifierTest` | Classification accuracy, false-positive scenarios | DONE |
| `ChatCompletionClientStreamingTest` | Terminal completion detection | Skipped — requires SDK mocking; covered indirectly by runner tests |

Test style: small state-machine tests proving when retry happens, when it must stop, when a stream is complete, when a response is incomplete, how tool calls are reconstructed.

---

## Phase 2: Fix P0 Streaming Correctness — DONE

**Prerequisite:** Phase 1
**Status:** DONE (6c821852, 2026-04-10)

All 6 fixes implemented plus review-round fix for Failed-terminal handling:

### 2.1 Preserve domain exceptions in streamWithRetry — DONE
**File:** `CloudStreamRetryRunner.kt`
Short-circuit: `RateLimitException`/`TransientException` bypass `OpenAIErrorClassifier.classify()`.

### 2.2 Distinguish metadata vs irreversible output for retry — DONE
**File:** `CloudStreamRetryRunner.kt`
Only `TextDelta`/`ToolCallDone` set `emittedEvent`. `Created` no longer blocks retry.

### 2.3 Fail on `response.incomplete` — DONE
**Files:** `CodexResponseClient.kt`, `CodexSseParser.kt`
`response.incomplete` maps to `Failed` with `incomplete_reason`. Streaming loop breaks on Failed event.

### 2.4 Require terminal completion in ChatCompletionClient — DONE
**File:** `ChatCompletionClient.kt`
Tracks `sawFinishReason`; throws `TransientException("Stream ended without finish_reason")` if missing.

### 2.5 Make stream-ended-without-completion retryable — DONE
**Files:** `OpenAIResponseClient.kt`, `CodexResponseClient.kt`
Changed to `TransientException("Stream ended without completion event")`.

### 2.6 MessageContentExtractor deleted — DONE
**Files:** `ChatCompletionInterop.kt`, `LFMLLMClient.kt`, `LlmLogger.kt`, `LlmInputItemsTraceSerializer.kt`
Promoted `ChatCompletionInterop.extractStringContent()` to `internal`; replaced all call sites; deleted `MessageContentExtractor.kt`.

**Acceptance criteria — all met:**
- A stream that fails after `Created` but before text/tool output retries ✓
- A stream that fails after text/tool output does not retry ✓
- Codex `response.incomplete` is never surfaced as success ✓
- Chat streaming does not emit `Completed` on clean EOF without terminal completion ✓
- `EasyInputMessage.Content` is extracted via typed API, not `toString()` ✓

---

## Phase 3: Fix P1 Classification, Security, Cancellation

**Prerequisite:** Phase 2

### 3.1 Harden error classification (keep it simple)
**File:** `OpenAIErrorClassifier.kt`

Keep one classifier. Add fast-paths for domain exceptions and typed SDK exceptions before string fallback:

```kotlin
fun classify(e: Exception): Exception = when (e) {
    is RateLimitException, is TransientException -> e  // preserve existing domain exceptions
    is com.openai.errors.RateLimitException -> RateLimitException(e.message ?: "Rate limited")
    is com.openai.errors.InternalServerException -> TransientException("Server error", e)
    else -> classifyByMessage(e)  // existing string-matching fallback
}
```

Do NOT build a separate transport-classifier abstraction. This is enough.

### 3.2 Narrow InsecureSslConfig
**File:** `InsecureSslConfig.kt`

Gate behind an explicit eval-only flag narrower than `BuildConfig.DEBUG`. The fastest safe move is a config flag like `INSECURE_SSL_FOR_EVAL`. Date-only trust relaxation can be pursued later if needed.

### 3.3 Cancel underlying stream on flow cancellation
**File:** `CodexResponseClient.kt`

Store OkHttp `Call` reference, cancel from `awaitClose`. This is the primary fix -- `ensureActive()` alone only helps after a blocking read returns.

```kotlin
// In streaming callbackFlow:
val call = httpClient.newCall(request)
awaitClose { call.cancel() }
```

---

## Phase 4: Extract Shared Responses Helpers

**Prerequisite:** Phase 3

### 4.1 Extract shared helpers from Responses-family clients

Extract common logic from `OpenAIResponseClient` and `CodexResponseClient`:
- Request/result accumulation
- Completion checks
- Retry epilogue handling

Keep both client classes for now. Only collapse into a single transport class if meaningful duplication remains after helpers are extracted.

### 4.2 Keep Chat and Leap separate
- `ChatCompletionClient` -- genuinely different wire protocol
- `LFMLLMClient` -- different backend family and lifecycle

**Acceptance criteria:**
- Shared Responses helpers reduce code duplication
- Both Responses-family clients use the same completion/retry logic
- No over-engineered strategy pattern unless duplication demands it

---

## Phase 5: Declare Local Capability Gaps

**Prerequisite:** Phase 4

### 5.1 Add narrow `LocalLlmSemantics` object
Declare the specific limitations the rest of the app needs to reason about:

```kotlin
object LocalLlmSemantics {
    val dropsNonUserAssistantRoles = true
    val generatesRandomToolCallIds = true
    val noToolResultCorrelation = true
    val flattensContentToString = true
}
```

Don't build a broad generic `LlmCapabilities` framework until there's a second consumer.

### 5.2 Gate or transform explicitly
If Leap cannot preserve a feature, reject or transform in one documented place. No silent drops.

---

## Phase 6: Deduplication Cleanup

**Prerequisite:** Phase 4

### 6.1 Extract shared ToolParameterExtractor
Create `ToolParameterExtractor.kt` (~25 lines). Merge logic from `CodexRequestBuilder.convertToolParameters()` and `LeapToolSchemaAdapter.parseToolParameters()`. One helper returning `JSONObject?`, callers decide fallback. Saves ~30 lines.

### 6.2 Fix MessageContentExtractor (P0 bug)
**This is a functional bug, not just deduplication.** `MessageContentExtractor.extractMessageContent(Any)` receives `EasyInputMessage.Content` but falls through to `toString()`, feeding wrapper strings like `Content{textInput=...}` into Leap.

Fix: Create typed `extractStringContent(content: EasyInputMessage.Content): String`. Update all call sites (`LFMLLMClient`, `LlmLogger`, `LlmInputItemsTraceSerializer`). Delete `MessageContentExtractor.kt`.

> Note: This should be done in Phase 2 alongside other P0 fixes if it's easy to slot in. Listed here because it's dedup-adjacent.

### 6.3 Shared post-retry flow handler (if still needed)
If the three retry epilogues survive Phases 2-4, extract a tiny helper. Otherwise the duplication is removed naturally by the Responses helper extraction.

---

## ~~Phase 7~~ Dropped

Internal canonical request model dropped as false positive. No present defect proves it's needed. Re-evaluate only after all concrete fixes land.

---

## Non-Goals

- Do not redesign `ModelCatalog`
- Do not remove local inference support
- Do not expand provider count during cleanup
- Do not mix prompt-builder changes unless capability declaration requires it

---

## Execution Summary

| Phase | What | Effort | Impact | Status |
|-------|------|--------|--------|--------|
| 1 | Add streaming/retry tests | Medium | Enables safe fixes | **DONE** |
| 2 | Fix P0 streaming correctness (5 items) + MessageContentExtractor bug | Small (~35 lines) | High -- eliminates silent truncation, lost retries, garbage Leap input | **DONE** |
| 3 | Harden classification + SSL + cancellation | Small (~40 lines) | Medium -- eliminates fragile heuristics and hangs | |
| 4 | Extract shared Responses helpers | Medium | Medium -- reduces duplication without over-engineering | |
| 5 | Declare local capability gaps | Small (~20 lines) | Low -- makes implicit lossiness explicit | |
| 6 | Deduplication cleanup | Small (net -30 lines) | Low -- reduces code without behavior change | |

**Dropped:** JsonValueConverter extraction (false positive -- partial overlap only), internal canonical request model (no present defect proves need).

**Expected net result:** Correct streaming semantics, honest local capabilities, less duplication -- without introducing new abstractions that don't yet earn their keep.

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

## Phase 3: Fix P1 Classification, Security, Cancellation — DONE

**Prerequisite:** Phase 2
**Status:** DONE (855d9fc4, 2026-04-10)

### 3.1 Harden error classification — DONE
**File:** `OpenAIErrorClassifier.kt`

Restructured as `when(e)` with fast-paths: domain exceptions preserved → typed SDK exceptions (`com.openai.errors.RateLimitException` with Retry-After header extraction, `InternalServerException`) → string-matching fallback via `classifyByMessage()`.

### 3.2 Narrow InsecureSslConfig — DONE
**Files:** `InsecureSslConfig.kt`, `build.gradle.kts`

Gated behind `BuildConfig.INSECURE_SSL_FOR_EVAL` (default `false`). Build with `-PinsecureSslForEval=true` to enable. Eval runner (`runner_preflight.py`) updated to pass this flag.

### 3.3 Cancel underlying stream on flow cancellation — DONE
**File:** `CodexResponseClient.kt`

`streamWithRetry` runs inside `launch{}` within `callbackFlow`. `awaitClose { activeCall?.cancel(); job.cancel() }` registered before any blocking I/O, so flow cancellation immediately terminates the HTTP connection.


---

## Phase 4: Extract Shared Responses Helpers — DONE

**Prerequisite:** Phase 3
**Status:** DONE (73916643, 2026-04-10)

### 4.1 Extract `StreamRetryRunResult.closeFlow()` — DONE

Extracted the identical post-retry epilogue block (check completed, emit Failed if needed, close flow) into `StreamRetryRunResult.closeFlow()` in `CloudStreamRetryRunner.kt`. Used by all three streaming clients: `OpenAIResponseClient`, `CodexResponseClient`, `ChatCompletionClient`.

### 4.2 Keep Chat and Leap separate — confirmed
- `ChatCompletionClient` -- genuinely different wire protocol (but also benefits from `closeFlow()`)
- `LFMLLMClient` -- different backend family and lifecycle

### Not extracted (intentionally)
- Streaming loop internals differ fundamentally (SDK events vs parsed SSE vs Chat Completions)
- Accumulation variables are trivial initialization — not worth a shared abstraction
- Result logging is 4 lines of identical pattern but not worth a separate function

---

## Phase 5: Declare Local Capability Gaps — DONE

**Prerequisite:** Phase 4
**Status:** DONE (3d6b52ae, 2026-04-10)

### 5.1 Add narrow `LocalLlmSemantics` object — DONE
**File:** `LFMLLMClient.kt`

Declared 4 limitations with doc comments, each cross-referenced at its occurrence site:
- `dropsNonUserAssistantRoles` — role mapping in `convertInputItemsToChatMessages`
- `generatesRandomToolCallIds` — UUID generation in `convertFunctionCalls`
- `noToolResultCorrelation` — `isFunctionCallOutput()` branch
- `flattensContentToString` — `extractStringContent` call

### 5.2 Gate or transform explicitly — deferred
No additional gating needed; existing drops are now documented.

---

## Phase 6: Deduplication Cleanup — DONE

**Prerequisite:** Phase 4
**Status:** DONE (5003fc5c, 2026-04-10)

### 6.1 Extract shared ToolParameterExtractor — DONE
**File:** `ToolParameterExtractor.kt`

Merged `CodexRequestBuilder.convertToolParameters()` and `LeapToolSchemaAdapter.parseToolParameters()` into a single `ToolParameterExtractor.extract(schema: FunctionTool): JSONObject?` helper. Both callers updated.

### 6.2 Fix MessageContentExtractor — done in Phase 2
Completed as part of Phase 2 fix 2.6 (typed `extractStringContent`, `MessageContentExtractor.kt` deleted).

### 6.3 Shared post-retry flow handler — done in Phase 4
Completed as `StreamRetryRunResult.closeFlow()` in Phase 4.

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
| 3 | Harden classification + SSL + cancellation | Small (~40 lines) | Medium -- eliminates fragile heuristics and hangs | **DONE** |
| 4 | Extract shared Responses helpers | Medium | Medium -- reduces duplication without over-engineering | **DONE** |
| 5 | Declare local capability gaps | Small (~20 lines) | Low -- makes implicit lossiness explicit | **DONE** |
| 6 | Deduplication cleanup | Small (net -30 lines) | Low -- reduces code without behavior change | **DONE** |

**Dropped:** JsonValueConverter extraction (false positive -- partial overlap only), internal canonical request model (no present defect proves need).

**Expected net result:** Correct streaming semantics, honest local capabilities, less duplication -- without introducing new abstractions that don't yet earn their keep.

# LLM Integration -- Final Improvement Plan

**Source:** Double-design review (Claude + Codex), cross-reviewed and aligned.
**Date:** 2026-04-08
**Status:** APPROVED

---

## Principles

1. Fix correctness before refactoring shape
2. One semantic contract for stream completion and retry
3. Transport differences belong below the shared interface
4. Unsupported capabilities must be explicit, not silently degraded
5. Keep the model catalog simple and data-driven

---

## Phase 1: Add Streaming/Retry Tests

**Prerequisite:** None

Add targeted tests before fixing bugs. These tests lock down current behavior and prove fixes.

| Test | Target |
|------|--------|
| `CloudStreamRetryRunnerTest` | Retry semantics, domain exception preservation, emittedEvent tracking |
| `CloudStreamRetryPolicyTest` | Policy decisions for metadata vs irreversible events |
| `CodexSseParserTest` | Event mapping, incomplete handling, malformed input resilience |
| `OpenAIErrorClassifierTest` | Classification accuracy, false-positive scenarios |
| `ChatCompletionClientStreamingTest` | Terminal completion detection |

Test style: small state-machine tests proving when retry happens, when it must stop, when a stream is complete, when a response is incomplete, how tool calls are reconstructed.

---

## Phase 2: Fix P0 Streaming Correctness

**Prerequisite:** Phase 1

### 2.1 Preserve domain exceptions in streamWithRetry
**File:** `CloudStreamRetryRunner.kt`

```kotlin
val classified = when (e) {
    is RateLimitException, is TransientException -> e
    else -> OpenAIErrorClassifier.classify(e)
}
```

### 2.2 Distinguish metadata vs irreversible output for retry
**File:** `CloudStreamRetryRunner.kt`

Track "semantic output emitted" instead of "any event emitted":
- `Created` -> does NOT block retry
- `TextDelta`, `ToolCallDone` -> blocks retry

### 2.3 Fail on `response.incomplete`
**Files:** `CodexResponseClient.kt`, `CodexSseParser.kt`

Remove `response.incomplete -> Completed` mapping. Surface as `Failed` with backend reason.

### 2.4 Require terminal completion in ChatCompletionClient
**File:** `ChatCompletionClient.kt`

```kotlin
var sawFinishReason = false
// In stream loop: set sawFinishReason = true when finishReason != null
// After loop:
if (!sawFinishReason) {
    throw TransientException("Stream ended without finish_reason")
}
emitter.emit(LLMStreamEvent.Completed)
```

### 2.5 Make stream-ended-without-completion retryable
**Files:** `OpenAIResponseClient.kt`, `CodexResponseClient.kt`

Change `RuntimeException("Stream ended without completion event")` to `TransientException("Stream ended without completion event")`.

**Acceptance criteria:**
- A stream that fails after `Created` but before text/tool output retries
- A stream that fails after text/tool output does not retry
- Codex `response.incomplete` is never surfaced as success
- Chat streaming does not emit `Completed` on clean EOF without terminal completion

---

## Phase 3: Fix P1 Classification, Security, Cancellation

**Prerequisite:** Phase 2

### 3.1 Transport-owned error classification
**Files:** `OpenAIErrorClassifier.kt`, `CloudStreamRetryRunner.kt`

- Check typed SDK exception classes before string fallback:
  ```kotlin
  fun classify(e: Exception): Exception = when (e) {
      is com.openai.errors.RateLimitException -> RateLimitException(e.message ?: "Rate limited")
      is com.openai.errors.InternalServerException -> TransientException("Server error", e)
      else -> classifyByMessage(e)
  }
  ```
- Stop routing Codex exceptions through OpenAI classifier (Codex `handleErrorResponse()` already produces typed exceptions)

### 3.2 Narrow InsecureSslConfig
**File:** `InsecureSslConfig.kt`

Gate behind an explicit eval-only flag narrower than `BuildConfig.DEBUG`. The fastest safe move is a config flag like `INSECURE_SSL_FOR_EVAL`. Date-only trust relaxation can be pursued later if needed.

### 3.3 Cancellation-aware streaming
**Files:** All streaming clients, `CodexSseParser.kt`

- Add `coroutineContext.ensureActive()` in stream iteration loops (~5 lines per client)
- For Codex: store OkHttp `Call` reference, cancel from `awaitClose` callback (~15 lines)
- For OpenAI/Chat: the `use {}` block on SDK streams handles cleanup, but `ensureActive()` prevents spin-wait

---

## Phase 4: Collapse Cloud Client Taxonomy

**Prerequisite:** Phase 3

### 4.1 Merge Codex into Responses transport family

Replace `OpenAIResponseClient` and `CodexResponseClient` with one Responses-family transport using strategy/composition:

```kotlin
class ResponsesTransport(
    private val requestEncoder: ResponsesRequestEncoder,
    private val streamDecoder: ResponsesStreamDecoder,
    private val authProvider: ResponsesAuthProvider,
    private val errorClassifier: ResponsesErrorClassifier,
) : LlmTransport { ... }
```

Strategies:
- `OpenAiResponsesWire` -- SDK-native streaming, API key auth
- `CodexResponsesWire` -- OkHttp + custom SSE, OAuth + custom headers

### 4.2 Keep Chat and Leap separate
- `ChatCompletionsTransport` -- genuinely different wire protocol
- `LeapLocalTransport` -- different backend family and lifecycle

### 4.3 Shared retry/completion in one place
Move all stream retry and completion logic into the transport base or a shared runner. No more per-client post-retry boilerplate.

**Acceptance criteria:**
- Factory chooses among three transport families, not four peer clients
- Codex-specific code lives under Responses family
- Shared stream/retry behavior implemented once

---

## Phase 5: Explicit Capability Declarations

**Prerequisite:** Phase 4

### 5.1 Define transport capabilities
```kotlin
data class LlmCapabilities(
    val supportsVision: Boolean,
    val supportsDeveloperMessages: Boolean,
    val supportsParallelToolCalls: Boolean,
    val supportsStableToolCallIds: Boolean,
    val supportsStreaming: Boolean,
)
```

### 5.2 Make local semantics honest
- Declare Leap limitations: no stable call IDs, no developer-role history, content flattening
- Enforce or degrade explicitly in one documented place
- Rest of the app can reason about backend differences without special-casing concrete classes

---

## Phase 6: Deduplication Cleanup

**Prerequisite:** Phase 4

### 6.1 Extract shared JsonValueConverter
Create `JsonValueConverter.kt` (~20 lines). Remove duplicates from `CodexRequestBuilder` and `LeapFunctionInterop`. Saves ~40 lines.

### 6.2 Extract shared ToolParameterExtractor
Create `ToolParameterExtractor.kt` (~25 lines). Merge logic from `CodexRequestBuilder.convertToolParameters()` and `LeapToolSchemaAdapter.parseToolParameters()`. Use Leap version's better logging. Saves ~30 lines.

### 6.3 Shared post-retry flow handler
Add `ProducerScope<LLMStreamEvent>.handleRetryResult()` to `CloudStreamRetryRunner`. Replace 10-line blocks in three clients. Saves ~20 lines. (May be subsumed by Phase 4 transport collapse.)

### 6.4 Remove MessageContentExtractor
Delete `MessageContentExtractor.kt`. Make `ChatCompletionInterop.extractStringContent` internal. Update `LFMLLMClient` and `LlmLogger` call sites.

---

## Phase 7: Evaluate Internal Canonical Request Model (CONDITIONAL)

**Prerequisite:** Phase 4 complete and evaluated

**Gate:** Only pursue if duplication remains high after Responses-family merge.

If justified:
- Add internal data classes: `LlmRequest`, `LlmMessage`, `LlmContentPart`, `LlmToolDefinition`, `LlmToolCallRecord`
- Convert `ResponseInputItem` and `FunctionTool` once at module edge
- Transports consume internal model, not SDK-specific unions

This is a good tool but should not become mandatory architecture before simpler cleanup has landed.

---

## Non-Goals

- Do not redesign `ModelCatalog`
- Do not remove local inference support
- Do not expand provider count during cleanup
- Do not mix prompt-builder changes unless capability declaration requires it

---

## Execution Summary

| Phase | What | Effort | Impact |
|-------|------|--------|--------|
| 1 | Add streaming/retry tests | Medium | Enables safe fixes |
| 2 | Fix P0 streaming correctness (5 items) | Small (~30 lines changed) | High -- eliminates silent truncation and lost retries |
| 3 | Fix P1 classification + security + cancellation | Medium (~60 lines) | Medium -- eliminates fragile heuristics and hangs |
| 4 | Collapse to 3 transport families | Large (restructure) | High -- eliminates structural duplication |
| 5 | Capability declarations | Small (~30 lines) | Medium -- makes implicit lossiness explicit |
| 6 | Deduplication cleanup | Small (net -70 lines) | Low -- reduces code without behavior change |
| 7 | Internal request model (conditional) | Large | Depends on Phase 4 outcome |

**Expected net result:** Fewer lines, fewer clients, correct streaming semantics, explicit capabilities, and the module's simplification story right-side-up: hard semantic parts are clean, not just the easy catalog/factory parts.

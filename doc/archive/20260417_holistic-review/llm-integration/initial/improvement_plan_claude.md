# LLM Integration -- Improvement Plan

Ordered by impact/effort ratio. Each item references the review finding.

---

## Phase 1: Bug Fixes (reliability, no design changes)

### 1.1 Fix stream-ended-without-completion to be retryable [A1]

**Files:** `OpenAIResponseClient.kt`, `CodexResponseClient.kt`

Change `RuntimeException("Stream ended without completion event")` to `TransientException("Stream ended without completion event")` in:
- `OpenAIResponseClient.chatWithToolsStreaming` line 155
- `CodexResponseClient.chatWithToolsStreaming` line 178

This enables retry for the most common transient failure (connection drop before any events).

**Effort:** 2 lines changed. **Impact:** High -- unretried connection drops are a production issue.

### 1.2 Fix ChatCompletionClient stream completion detection [A8]

**File:** `ChatCompletionClient.kt`

Add a `sawFinishReason` flag. Only emit `LLMStreamEvent.Completed` if a `finish_reason` was received. If the stream ends without one, throw `TransientException`.

```
// After the forEach loop:
if (!sawFinishReason) {
    throw TransientException("Stream ended without finish_reason")
}
emitter.emit(LLMStreamEvent.Completed)
```

**Effort:** 5 lines. **Impact:** Medium -- prevents silent truncation.

### 1.3 Use defensive `optString`/`optJSONObject` in CodexSseParser.mapToStreamEvent [A3]

**File:** `CodexSseParser.kt`

Replace `getJSONObject("response").getString("id")` with `optJSONObject("response")?.optString("id", "unknown") ?: "unknown"` in the `response.created` branch (line 72-73).

**Effort:** 1 line. **Impact:** Low but zero-cost resilience improvement.

### 1.4 Eliminate double classification in streamWithRetry [A7, C1]

**File:** `CloudStreamRetryRunner.kt`

Skip `OpenAIErrorClassifier.classify()` if the caught exception is already `RateLimitException` or `TransientException`:

```kotlin
val classified = when (e) {
    is RateLimitException, is TransientException -> e
    else -> OpenAIErrorClassifier.classify(e)
}
```

**Effort:** 3 lines. **Impact:** Low -- correctness guard against future re-classification bugs.

---

## Phase 2: Deduplication (reduce code, no behavior change)

### 2.1 Extract shared JsonValue-to-JSONObject converter [B3]

Create `JsonValueConverter.kt`:

```kotlin
internal object JsonValueConverter {
    fun toJSONObject(map: Map<String, JsonValue>): JSONObject { ... }
    fun convert(value: JsonValue): Any? { ... }
}
```

Then replace the duplicate implementations in:
- `CodexRequestBuilder.kt` (remove `jsonValueMapToJsonObject` + `convertJsonValue`)
- `LeapFunctionInterop.kt` (remove `jsonValueMapToJsonObject` + `jsonValueToAny`)

**Effort:** Create 1 new file (~20 lines), simplify 2 existing files. **Impact:** Removes ~40 lines of duplication.

### 2.2 Extract shared tool parameter extraction [B4]

Create `ToolParameterExtractor.kt`:

```kotlin
internal object ToolParameterExtractor {
    fun extract(tool: FunctionTool): JSONObject? { ... }
}
```

Merge the logic from `CodexRequestBuilder.convertToolParameters()` and `LeapToolSchemaAdapter.parseToolParameters()`. Use the Leap version's better logging.

Then simplify both callers to: `val params = ToolParameterExtractor.extract(tool) ?: JSONObject()`.

**Effort:** Create 1 new file (~25 lines), simplify 2 existing files. **Impact:** Removes ~30 lines of duplication, single source of truth for parameter extraction.

### 2.3 Extract streaming post-retry boilerplate [B6]

Add to `CloudStreamRetryRunner.kt`:

```kotlin
internal fun ProducerScope<LLMStreamEvent>.handleRetryResult(
    result: StreamRetryRunResult,
    tag: String
) {
    if (!result.completed && !result.failureEmitted) {
        val msg = result.lastError?.message ?: "Unknown error"
        trySend(LLMStreamEvent.Failed(msg))
    }
    close()
}
```

Then each streaming client replaces its 10-line block with:

```kotlin
handleRetryResult(retryResult, TAG)
awaitClose { Log.d(TAG, "Streaming flow closed") }
```

**Effort:** Add ~10 lines to runner, remove ~30 lines across 3 clients. **Impact:** Eliminates the most visible copy-paste in the module.

### 2.4 Consolidate MessageContentExtractor [B5]

Delete `MessageContentExtractor.kt`. Make `ChatCompletionInterop.extractStringContent` `internal` (not `private`). Update `LFMLLMClient` and `LlmLogger` to call `ChatCompletionInterop.extractStringContent(msg.content())` directly.

**Effort:** Delete 1 file, change 2 call sites. **Impact:** Removes an untyped `Any`-accepting function in favor of the type-safe version.

---

## Phase 3: Robustness Improvements (hardening)

### 3.1 Improve OpenAIErrorClassifier with SDK exception types [A2]

Check for the OpenAI SDK's typed exceptions before falling through to string matching:

```kotlin
fun classify(e: Exception): Exception = when (e) {
    is com.openai.errors.RateLimitException -> RateLimitException(e.message ?: "Rate limited")
    is com.openai.errors.InternalServerException -> TransientException("Server error", e)
    is com.openai.errors.BadRequestException -> RuntimeException("Bad request: ${e.message}", e)
    // ... existing string-matching fallback for non-SDK exceptions
    else -> classifyByMessage(e)
}
```

**Effort:** ~15 lines. Requires checking what exception types the OpenAI Kotlin SDK exposes. **Impact:** Medium -- eliminates the false-positive risk from substring matching.

### 3.2 Add cancellation support to streaming loops [C4]

In each streaming client, add `ensureActive()` checks inside stream iteration loops:

```kotlin
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

// Inside the forEach/for loop:
coroutineContext.ensureActive()
```

For `CodexResponseClient`, the blocking `readLine()` in `CodexSseParser.parse()` is harder to interrupt. Options:
- Use OkHttp's `Call.cancel()` from the `awaitClose` callback
- Store the `Call` reference and cancel it when the flow collector cancels

**Effort:** ~5 lines per client for `ensureActive()`, ~15 lines for OkHttp call cancellation. **Impact:** Prevents 120-second hangs on cancelled flows.

### 3.3 Remove redundant debug guards around InsecureSslConfig [B8]

Since `InsecureSslConfig.trustManager` and `sslSocketFactory` already `check(BuildConfig.DEBUG)`, the outer `if (BuildConfig.DEBUG)` in the three client constructors is redundant. Removing it simplifies the apply blocks.

However, keeping the outer guard means the insecure config object is never even referenced in release builds, which is cleaner for ProGuard/R8 stripping. **Decision: Keep as-is.** This is not worth changing.

---

## Phase 4: Future Considerations (not urgent)

### 4.1 SSE reconnection support

If Codex ever supports `Last-Event-ID` for stream recovery, the `CodexSseParser` would need to track event IDs. This is not needed today.

### 4.2 Client cache invalidation on auth method change

If the user switches from API key auth to OAuth within a session, stale clients remain in `LLMClientFactory.clientCache`. The `isOAuth` flag in the cache key partially handles this, but the old client is not evicted. Since `cleanupAll()` runs at session end, this is acceptable. If mid-session auth switching becomes common, add eviction.

### 4.3 Structured error types

The current mix of `RateLimitException`, `TransientException`, `IllegalStateException`, and `RuntimeException` is workable but informal. A sealed `LLMError` hierarchy could make error handling in callers more exhaustive. This is a larger refactor with questionable ROI given the current codebase size.

---

## Execution Priority

| Item | Effort | Impact | Priority |
|------|--------|--------|----------|
| 1.1 Stream-ended retryable | 2 lines | High | P0 |
| 1.2 Chat stream completion | 5 lines | Medium | P0 |
| 1.3 Defensive SSE parsing | 1 line | Low | P0 |
| 1.4 Skip double classification | 3 lines | Low | P1 |
| 2.3 Streaming boilerplate | Net -20 lines | Medium | P1 |
| 2.1 JsonValue converter | Net -20 lines | Medium | P1 |
| 2.2 Tool param extraction | Net -10 lines | Medium | P2 |
| 2.4 MessageContentExtractor | Delete 1 file | Low | P2 |
| 3.1 SDK exception types | ~15 lines | Medium | P2 |
| 3.2 Cancellation support | ~25 lines | Medium | P2 |

**Total estimated net line change:** Phase 1 reduces ~10 lines, Phase 2 reduces ~70 lines, Phase 3 adds ~55 lines. Net: roughly 25 fewer lines with better reliability.

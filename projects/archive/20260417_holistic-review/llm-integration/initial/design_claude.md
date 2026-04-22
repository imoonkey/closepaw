# LLM Integration Module -- Ultra-Thorough Code Review

**Scope:** 19 files in `app/src/main/kotlin/com/moonkey/androidagent/llm/` + `llm_models.json`
**Date:** 2026-04-08

---

## Perspective A: Reliability & Correctness

### A1. Streaming Retry -- Partial-Output Hazard (HIGH)

**Files:** `CloudStreamRetryRunner.kt`, `CloudStreamRetryPolicy.kt`

The retry framework correctly refuses to retry after events have been emitted to the flow (`emittedEvent` flag). This is essential -- replaying partial text would corrupt the UI. However, there is a subtle gap:

- In `OpenAIResponseClient.chatWithToolsStreaming` (line 154), if the stream ends without a `Completed` event, it throws `RuntimeException("Stream ended without completion event")`. This exception is **not** a `TransientException`, so `OpenAIErrorClassifier.classify()` wraps it into a generic `RuntimeException("LLM error: ...")`. The policy then treats it as **non-retryable** (`StreamRetryAction.Stop`), even though this is the most common transient failure (connection drop mid-stream before any data).

- The same pattern exists in `CodexResponseClient` streaming (line 178) and `ChatCompletionClient` (implicit: stream ends cleanly without `Completed` is less likely for Chat API but still possible).

**Risk:** Legitimate connection drops that happen before any events are emitted will not be retried because they throw a generic `RuntimeException` rather than `TransientException`.

**Fix:** Throw `TransientException("Stream ended without completion event")` instead of `RuntimeException`.

### A2. Error Classifier -- Fragile String Matching (MEDIUM)

**File:** `OpenAIErrorClassifier.kt`

Rate limit detection relies on the message containing "429" or "rate limit". Server error detection checks for "500", "502", etc. as substrings. This creates false positives:

- A message like "Request failed with error code 14291" would match `contains("429")`.
- A message like "Token count: 5002" would match `contains("500")`.

The classifier also only examines `e.message` and `e.cause?.message`. The OpenAI SDK wraps HTTP errors in its own exception types that may carry the status code as a field rather than in the message string. If the SDK changes message formatting, detection breaks silently.

**Fix:** Check for the OpenAI SDK's specific exception types first (e.g., `com.openai.errors.RateLimitException`, `com.openai.errors.InternalServerException`) and fall through to string matching only as a last resort.

### A3. Codex SSE Parser -- Robustness Gaps (MEDIUM)

**File:** `CodexSseParser.kt`

1. **Multi-line data fields**: The SSE spec says multiple `data:` lines before a blank line should be joined with `\n`. The parser does this (`dataBuilder.append('\n')`), but then calls `trim()` which strips leading/trailing whitespace from JSON payloads. This is safe for JSON but worth noting.

2. **`event:` field ignored**: The SSE spec uses `event:` to name events. The parser ignores this entirely and reads the type from the JSON `type` field. This works for Codex's wire format today but makes the parser non-standard.

3. **No `id:` tracking**: If the connection drops, SSE spec allows reconnecting with `Last-Event-ID`. The parser discards `id:` lines, so reconnection-based recovery is impossible.

4. **`getString` vs `optString` inconsistency**: `mapToStreamEvent` uses `getJSONObject("response").getString("id")` (throws on missing key) for `response.created`, but `optString` elsewhere. A malformed `response.created` event crashes the entire stream instead of degrading gracefully.

### A4. CloudLlmRetry -- Non-Retryable Exceptions Pass Through (LOW)

**File:** `CloudLlmRetry.kt`

`executeWithRetry` only catches `RateLimitException` and `TransientException`. Any other exception immediately propagates. This is correct behavior, but it means `OpenAIErrorClassifier.classify()` is the sole gatekeeper. If `classify()` fails to tag a retryable error (e.g., a new HTTP 529 status that OpenAI starts using), the retry framework will never see it.

### A5. OkHttpClient Shutdown in CodexResponseClient (LOW)

**File:** `CodexResponseClient.kt`, line 204

`cleanup()` calls `httpClient.dispatcher.executorService.shutdown()`. This is a hard shutdown of the executor. If a streaming response is still in-flight, it will be interrupted. Since the client is cached by `LLMClientFactory`, and `cleanupAll()` is called at session teardown, this is probably fine -- but a `shutdownNow()` with a timeout would be more defensive.

### A6. ConcurrentHashMap Race in LLMClientFactory (LOW)

**File:** `LLMClientFactory.kt`

`computeIfAbsent` on `ConcurrentHashMap` is thread-safe for computing the value, but `resolveApiKey` and `isOAuth` are called **before** `computeIfAbsent`. If the api key resolver is stateful or has side effects, multiple threads could compute different cache keys for the same model. In practice, this is unlikely since the factory is called from a single coroutine, but the code is not obviously correct under concurrent access despite the doc claiming thread-safety.

### A7. Double Error Classification in Streaming (LOW)

**File:** `CloudStreamRetryRunner.kt`, line 51

The `streamWithRetry` function classifies **every** caught exception through `OpenAIErrorClassifier.classify()`. But `CodexResponseClient.handleErrorResponse()` already throws pre-classified exceptions (`RateLimitException`, `TransientException`, `IllegalStateException`). Re-classifying these is wasted work and could theoretically re-wrap them incorrectly -- `classify()` receives a `RateLimitException`, checks `message.contains("429")`, and depending on the message text might or might not produce the same classification.

In practice this works because `RateLimitException` messages do contain "rate limit", and `classify()` checks that first. But it is a fragile coupling.

### A8. ChatCompletionClient Stream -- No Explicit Completion Check (LOW)

**File:** `ChatCompletionClient.kt`

Unlike `OpenAIResponseClient` and `CodexResponseClient`, the `ChatCompletionClient` streaming path does **not** check for a completion signal. It just falls through after the stream ends (line 218: `emitter.emit(LLMStreamEvent.Completed)`). This means a prematurely-closed stream without a `finish_reason` will emit `Completed` anyway. The consumer will get an incomplete response that looks complete.

---

## Perspective B: Simplicity & Design

### B1. Three Client Types -- Is This Justified? (STRUCTURAL)

The module has four `LLMClient` implementations:
1. `OpenAIResponseClient` -- OpenAI Responses API (SDK-native)
2. `ChatCompletionClient` -- OpenAI Chat Completions API (SDK-native)
3. `CodexResponseClient` -- Codex backend (raw OkHttp + custom SSE)
4. `LFMLLMClient` -- Local inference (Leap SDK)

**Verdict: Justified.** The three cloud clients serve genuinely different protocols:
- Responses API and Chat API have different wire formats, streaming event shapes, and tool call semantics
- Codex uses a completely different endpoint with OAuth + custom headers
- Local LLM has a different lifecycle (model loading/unloading)

Merging any two would add complexity, not remove it.

### B2. ChatCompletionInterop -- Necessary Bridge (STRUCTURAL)

**File:** `ChatCompletionInterop.kt` (275 lines)

This file exists solely to convert Responses API types to Chat Completions types. The alternative would be to have callers produce generic types that both APIs can consume, but that would mean inventing a third type system and converting in both directions.

**Verdict: Necessary, but could be slimmer.** The `convertInputItems` function handles four cases (EasyInputMessage, FunctionCall, FunctionCallOutput, unknown). The adjacent-function-call merging logic (lines 56-71) is genuinely required by the Chat API spec. This is an honest bridge.

**Possible reduction:** The `convertUserMessage` private function handles three branches (text, multimodal, fallback). The multimodal path is only exercised by models that support vision over Chat API. If no Chat API models currently support vision (checking `llm_models.json`: AutoGLM, Qwen 3 VL, etc. are all Chat API + vision-capable), this is needed.

### B3. Duplicated `jsonValueMapToJsonObject` (DESIGN SMELL)

Three separate files implement nearly identical `JsonValue -> JSONObject` conversion:
1. `CodexRequestBuilder.kt` lines 176-192 (`convertJsonValue` + `jsonValueMapToJsonObject`)
2. `LeapFunctionInterop.kt` lines 193-230 (`jsonValueToAny` + `jsonValueMapToJsonObject`)
3. `ChatCompletionInterop.kt` does not have this, but transfers `_additionalProperties` via the SDK's own builder

The `CodexRequestBuilder` and `LeapFunctionInterop` implementations are **functionally identical** except `LeapFunctionInterop` has additional fallback parsing for raw strings starting with `{` or `[`.

**Fix:** Extract to a shared `JsonValueConverter` utility object (5-10 lines of code).

### B4. Duplicated Tool Parameter Parsing (DESIGN SMELL)

Both `CodexRequestBuilder.convertToolParameters()` and `LeapToolSchemaAdapter.parseToolParameters()` do nearly the same thing: extract parameters from `FunctionTool`, trying known -> unknown -> string fallback. The Codex version is 22 lines; the Leap version is 40 lines (with better logging).

**Fix:** Extract a shared `ToolParameterExtractor.extract(tool: FunctionTool): JSONObject` utility.

### B5. `MessageContentExtractor.kt` -- Orphaned Abstraction (DESIGN SMELL)

**File:** `MessageContentExtractor.kt` (17 lines)

This file contains a single top-level function `extractMessageContent(content: Any): String` used by `LFMLLMClient` and `LlmLogger`. It accepts `Any` and does runtime type-checking. Meanwhile, `ChatCompletionInterop` has its own `extractStringContent(content: EasyInputMessage.Content)` that is type-safe.

The `Any`-accepting version in `MessageContentExtractor` is used because `LlmLogger` receives `EasyInputMessage.Content` but calls `extractMessageContent(msg.content())` where `content()` returns `EasyInputMessage.Content` (which is `Any` at the call site through the SDK's type system).

**This is an artifact.** The function should be typed to `EasyInputMessage.Content` or the callers should use `ChatCompletionInterop.extractStringContent`. Having two extraction strategies for the same data is confusing.

### B6. Streaming Boilerplate -- Three Identical Wrappers (DESIGN SMELL)

`OpenAIResponseClient`, `ChatCompletionClient`, and `CodexResponseClient` all have identical post-retry handling:

```kotlin
if (retryResult.completed) {
    close()
} else {
    val error = retryResult.lastError ?: RuntimeException("...")
    if (!retryResult.failureEmitted) {
        trySend(LLMStreamEvent.Failed(error.message ?: "Unknown error"))
    }
    close()
}
awaitClose { Log.d(TAG, "Streaming flow closed") }
```

This 10-line block is copy-pasted three times.

**Fix:** Move into `CloudStreamRetryRunner.kt` as a higher-order function that takes the `callbackFlow`'s `ProducerScope` and handles post-retry cleanup.

### B7. Non-Streaming via Streaming in CodexResponseClient (ACCEPTABLE)

`CodexResponseClient.chatWithTools()` (non-streaming) internally consumes a streaming response because "the Codex backend always requires `stream: true`". This is an unavoidable constraint. The implementation is clean -- it collects events into accumulators and returns a `ResponsesResult`.

### B8. InsecureSslConfig -- Guard is Correct but Scoped Wrong (LOW)

**File:** `InsecureSslConfig.kt`

The debug guard (`check(BuildConfig.DEBUG)`) is correct, but the config is applied in three places:
- `OpenAIResponseClient` (line 45)
- `ChatCompletionClient` (line 41)
- `CodexResponseClient` (line 229)

Each applies the same `if (BuildConfig.DEBUG)` check before using the config. Since the config's getters already guard with `check(BuildConfig.DEBUG)`, the outer `if` is redundant. ProGuard will strip the debug paths in release, so this is harmless but adds noise.

### B9. Model Catalog Design -- Clean (POSITIVE)

**File:** `ModelCatalog.kt`

This is well-designed: immutable after construction, clean separation between wire format (`JsonModelEntry`) and domain model (`ModelEntry`), provider defaults with per-entry overrides. The `withBaseUrlOverrides` returns a new instance rather than mutating. `LLMProvider` enum carries its own defaults -- adding a provider is genuinely a two-line change.

### B10. LlmLogger -- Appropriate Scope (POSITIVE)

**File:** `LlmLogger.kt`

Gated behind `BuildConfig.DEBUG`, truncates long content, structured output format. No performance concerns.

---

## Cross-Cutting Concerns

### C1. Error Propagation Path

The error flow is: SDK/HTTP error -> `OpenAIErrorClassifier.classify()` -> `CloudLlmRetry.executeWithRetry()` or `streamWithRetry()` -> caller.

For non-streaming: `classify()` is called in each client's `executeChatWithTools` catch block, then `executeWithRetry` catches the classified exceptions.

For streaming: `streamWithRetry` calls `classify()` on its own (line 51). But `CodexResponseClient.handleErrorResponse()` throws pre-classified exceptions. This means Codex errors get double-classified (see A7).

**Recommendation:** Either have `streamWithRetry` skip classification if the exception is already `RateLimitException` or `TransientException`, or have `handleErrorResponse` throw raw `RuntimeException` and let the framework classify.

### C2. Thread Safety

- `LLMClientFactory.clientCache` uses `ConcurrentHashMap` -- correct.
- `LFMLLMClient.modelRunner` is `@Volatile` + guarded by `Mutex` -- correct.
- `CodexSseParser.ToolCallAccumulator` uses mutable maps but is only accessed within a single coroutine -- correct.
- All cloud clients are stateless after construction (no mutable fields) -- correct.

### C3. Memory Leaks

- `LLMClientFactory` caches clients indefinitely until `cleanupAll()`. The cache key includes `(provider, baseUrl, api, oauth)`. If the user switches auth methods (API key -> OAuth -> API key), stale clients accumulate. `cleanupAll()` is called at session teardown, so this is bounded by session lifetime. Acceptable.
- `CodexResponseClient` owns an `OkHttpClient` with its own connection pool and dispatcher. The `cleanup()` method handles this.
- `LFMLLMClient` owns a `ModelRunner` which holds native memory. The `cleanup()` method calls `unload()`. If cleanup is skipped (e.g., crash), native memory leaks until process death. Android's process model handles this.

### C4. Cancellation

- `OpenAIResponseClient.chatWithToolsStreaming` and `ChatCompletionClient.chatWithToolsStreaming` use `callbackFlow` with `awaitClose`. If the flow collector cancels, `awaitClose` fires and the coroutine ends. But the `withContext(Dispatchers.IO)` block inside may still be iterating on the stream. The `use { }` block on the stream should close it, but there is no explicit cancellation check inside the `forEach` loop.

- `CodexResponseClient` has the same issue: the OkHttp response body stream will block on `readLine()` inside `CodexSseParser.parse()` until data arrives. If the coroutine is cancelled, the blocking read will not be interrupted (OkHttp's `ResponseBody.byteStream()` blocking reads are not cancellation-aware).

**Risk:** Cancelled flows may hang until the HTTP read timeout (120 seconds for Codex).

---

## Summary Scorecard

| Area | Grade | Notes |
|------|-------|-------|
| Architecture | A- | Clean abstraction, right number of client types |
| Error Handling | B | Works in practice, fragile string matching |
| Retry Logic | B+ | Correct policy, partial-output safety, minor gaps |
| SSE Parsing | B | Works for Codex, non-standard, some brittleness |
| Thread Safety | A | Correct use of ConcurrentHashMap, Mutex, volatile |
| Code Duplication | C+ | 3 instances of JsonValue conversion, 3x streaming boilerplate |
| Cancellation | C+ | No explicit cancellation support in stream loops |
| Testability | B | Factory has test hook, most classes are injectable |
| Config/Catalog | A | Clean, immutable, extensible |

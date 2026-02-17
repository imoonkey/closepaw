# Design: LLM Client Consolidation

**Priority**: P2 — DRY
**Estimated savings**: ~200 duplicated lines eliminated
**Files affected**: `llm/OpenAIResponseClient.kt`, `llm/ChatCompletionClient.kt`, new `llm/CloudLLMClient.kt`

---

## Problem

`OpenAIResponseClient` (326 lines) and `ChatCompletionClient` (303 lines) share ~200 lines of nearly identical code:

1. **Retry loop with exponential backoff** — both `chatWithTools()` methods have identical try/catch/retry structure for `RateLimitException` and `TransientException` (lines 57-103 in Response, 44-72 in Chat)
2. **Streaming retry + error handling** — both `chatWithToolsStreaming()` methods have identical outer retry loop, `emittedEvent` tracking, `failureEmitted` flag, and close/awaitClose cleanup (lines 110-242 in Response, 118-274 in Chat)
3. **`advanceBackoff()`** — identical one-liner in both files
4. **Client construction** — identical `OpenAIOkHttpClient.builder().apiKey().baseUrl().build()` pattern

The only differences:
- **Response client**: calls `client.responses().create()` / `createStreaming()`, parses `ResponseOutputItem`
- **Chat client**: calls `client.chat().completions().create()` / `createStreaming()`, converts `ResponseInputItem` → `ChatCompletionCreateParams` via `ChatCompletionInterop`, parses streamed tool call deltas

## Solution

Extract a `CloudLLMClient` abstract base class that owns the retry/streaming/backoff infrastructure. Subclasses implement only API-specific request building and response parsing.

### New class hierarchy

```
LLMClient (abstract)
├── CloudLLMClient (abstract) — retry, backoff, streaming scaffold
│   ├── OpenAIResponseClient — Responses API specifics
│   └── ChatCompletionClient — Chat Completions API specifics
└── LFMLLMClient — local model, unchanged
```

### CloudLLMClient outline

```kotlin
abstract class CloudLLMClient(
    apiKey: String,
    baseUrl: String? = null
) : LLMClient() {

    protected val client: OpenAIClient = OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { baseUrl?.let { baseUrl(it) } }
        .build()

    // Subclasses implement these:
    protected abstract fun executeRequest(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): ResponsesResult

    protected abstract fun executeStreamingRequest(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String,
        emitter: StreamEmitter
    )

    // Shared retry loop for non-streaming:
    final override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): ResponsesResult = withContext(Dispatchers.IO) {
        retryWithBackoff { executeRequest(systemPrompt, inputItems, tools, model) }
    }

    // Shared streaming scaffold:
    final override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): Flow<LLMStreamEvent> = callbackFlow {
        streamWithRetry(this) {
            executeStreamingRequest(systemPrompt, inputItems, tools, model, it)
        }
        awaitClose { Log.d(TAG, "Streaming flow closed") }
    }

    private suspend fun <T> retryWithBackoff(block: () -> T): T { ... }
    private suspend fun streamWithRetry(scope: ProducerScope<LLMStreamEvent>, block: ...) { ... }
}
```

### StreamEmitter interface

```kotlin
interface StreamEmitter {
    fun emitCreated(responseId: String)
    fun emitTextDelta(delta: String)
    fun emitToolCallDone(toolCall: LLMToolCall)
    fun emitCompleted()
    fun emitFailed(message: String)
}
```

Each subclass calls these within `executeStreamingRequest`. The base class handles retry decisions, `emittedEvent` tracking, and flow lifecycle.

## Steps

1. Create `CloudLLMClient.kt` with:
   - Shared `client` construction
   - `retryWithBackoff()` private function
   - `streamWithRetry()` private function with emittedEvent/failureEmitted tracking
   - `StreamEmitter` interface
2. Refactor `OpenAIResponseClient` to extend `CloudLLMClient`, implement `executeRequest()` and `executeStreamingRequest()` only
3. Refactor `ChatCompletionClient` similarly
4. Delete `advanceBackoff()` from both subclasses
5. Verify no behavioral change: same retry counts, same backoff multiplier, same error classification

## Risks

- **Low**: Both clients already use `OpenAIErrorClassifier.classify()`, so error handling is unified.
- **Medium**: Streaming lifecycle is subtle (the `emittedEvent` / partial-output bailout logic). Must be tested carefully.

## Success criteria

- `OpenAIResponseClient` < 120 lines
- `ChatCompletionClient` < 120 lines
- `CloudLLMClient` ~150 lines
- Total: ~390 lines (down from 629)
- All existing behavior preserved

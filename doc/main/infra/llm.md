# LLM Integration

> LLM clients, model catalog, streaming, and retry infrastructure.
> Last updated: 2026-02-20 (commit: 2493be6)

## Overview

The agent supports multiple LLM backends through a unified abstract class (`LLMClient`), with both streaming and non-streaming support. The system is catalog-driven: models are defined in `assets/llm_models.json`, and the `LLMClientFactory` creates or reuses the correct client implementation based on model name.

---

## LLMClient

> See: `llm/LLMClient.kt`

Abstract base class. Uses OpenAI Responses API types (`ResponseInputItem`, `FunctionTool`) as input to minimize changes to callers. Uses custom `LLMStreamEvent` for output since OpenAI's `ResponseStreamEvent` cannot be constructed outside the SDK.

```kotlin
abstract class LLMClient {
    abstract suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String = DEFAULT_MODEL
    ): ResponsesResult

    abstract fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String = DEFAULT_MODEL
    ): Flow<LLMStreamEvent>

    open fun isReady(): Boolean = true
    open suspend fun cleanup() {}
}
```

Constants: `DEFAULT_MODEL = "glm-5"`, `MAX_RETRIES = 5`, `INITIAL_BACKOFF_MS = 1000`, `MAX_BACKOFF_MS = 60000`, `BACKOFF_MULTIPLIER = 2.0`.

### Stream Event Types

```kotlin
sealed interface LLMStreamEvent {
    data class Created(val responseId: String) : LLMStreamEvent
    data class TextDelta(val delta: String) : LLMStreamEvent
    data class ToolCallDone(val toolCall: LLMToolCall) : LLMStreamEvent
    data object Completed : LLMStreamEvent
    data class Failed(val error: String) : LLMStreamEvent
}
```

### Result Types

```kotlin
data class ResponsesResult(
    val textContent: String?,
    val toolCalls: List<LLMToolCall>,
    val responseId: String
)

data class LLMToolCall(
    val callId: String,
    val name: String,
    val arguments: String  // JSON string
)
```

### Exception Types

| Exception | Purpose |
|-----------|---------|
| `RateLimitException(message, retryAfterMs?)` | API rate limit (429). Carries optional retry-after hint. |
| `TransientException(message, cause?)` | Transient errors (timeout, server 5xx) that may succeed on retry. |

---

## Implementations

### OpenAIResponseClient

> See: `llm/OpenAIResponseClient.kt`

Cloud client using OpenAI Responses API with native function calling.

- Non-streaming: `client.responses().create(params)` wrapped in `CloudLlmRetry.executeWithRetry()`
- Streaming: `client.responses().createStreaming(params)` wrapped in `streamWithRetry()`
- Parses `ResponseStreamEvent` variants: `isCreated()`, `isOutputTextDelta()`, `isOutputItemDone()` (for function calls), `isCompleted()`, `isFailed()`
- Errors classified via `OpenAIErrorClassifier.classify()` before retry decisions
- Constructor: `OpenAIResponseClient(apiKey, baseUrl?)` — builds `OpenAIOkHttpClient`

### ChatCompletionClient

> See: `llm/ChatCompletionClient.kt`

Cloud client using OpenAI Chat Completions API. Works with any OpenAI-compatible endpoint (OpenRouter, Novita, vLLM, etc.).

- Non-streaming: `client.chat().completions().create(params)` with retry
- Streaming: `client.chat().completions().createStreaming(params)` with retry
- Converts ResponseInputItem/FunctionTool to Chat Completions types via `ChatCompletionInterop`
- Tool call deltas arrive incrementally (indexed by `tcDelta.index()`), accumulated in `toolCallBuilders` map, emitted on `finishReason`
- Constructor: `ChatCompletionClient(apiKey, baseUrl?)` — builds `OpenAIOkHttpClient`

### ChatCompletionInterop

> See: `llm/ChatCompletionInterop.kt`

Converts between Responses API types and Chat Completions API types:

| Method | Purpose |
|--------|---------|
| `systemMessage(prompt)` | System prompt → `ChatCompletionSystemMessageParam` |
| `convertInputItems(items)` | `ResponseInputItem` list → `ChatCompletionMessageParam` list |
| `convertTools(tools)` | `FunctionTool` list → `ChatCompletionTool` list |

Groups adjacent function calls into a single assistant message (Chat API requires all `tool_calls` from one turn in one message). Handles multimodal content (text + images) for user messages.

### LFMLLMClient

> See: `llm/LFMLLMClient.kt`

On-device inference using LiquidAI Leap SDK. No network required after model download.

- Model loading: `LeapDownloader.loadModel()` with progress callbacks
- Tool registration: `conversation.registerFunction()` via `LeapToolSchemaAdapter`
- Streaming: Leap's `conversation.generateResponse()` returns `Flow<MessageResponse>`
- Model state tracked via `ModelLoadingState` sealed interface: `NotLoaded → Downloading(progress) → Loading → Ready` or `Error`
- Thread-safe: model loading protected by `Mutex`
- Cleanup: `modelRunner?.unload()`

Configuration via `LocalLLMConfig`:

```kotlin
data class LocalLLMConfig(
    val modelSlug: String = "LFM2.5-1.2B-Instruct",
    val quantizationSlug: String = "Q4_K_M",
    val generationOptions: GenerationOptions? = null
)
```

---

## Model Catalog

> See: `llm/ModelCatalog.kt`

Catalog-driven model resolution. Models are defined in `assets/llm_models.json` and parsed at startup.

### ModelEntry

```kotlin
data class ModelEntry(
    val name: String,          // JSON key, stable identifier (e.g. "gpt-5.2")
    val displayName: String,   // Shown in UI dropdowns
    val provider: LLMProvider, // Determines API key and base URL
    val api: ApiType,          // Determines which LLMClient subclass
    val modelId: String,       // Model string sent to the API
    val baseUrl: String? = null,
    val apiKeyEnv: String? = null,
    val supportsVision: Boolean = true
) {
    val effectiveApiKeyEnv: String  // Entry override or provider default
    val effectiveBaseUrl: String?   // Entry override or provider default
}
```

### LLMProvider

```kotlin
enum class LLMProvider(val defaultApiKeyEnv: String, val defaultBaseUrl: String?) {
    OPENAI(defaultApiKeyEnv = "OPENAI_API_KEY", defaultBaseUrl = null),
    OPENROUTER(defaultApiKeyEnv = "OPENROUTER_API_KEY", defaultBaseUrl = "https://openrouter.ai/api/v1"),
    NOVITA(defaultApiKeyEnv = "NOVITA_API_KEY", defaultBaseUrl = "https://api.novita.ai/openai/v1")
}
```

### ApiType

```kotlin
enum class ApiType {
    RESPONSE,  // OpenAI Responses API → OpenAIResponseClient
    CHAT       // Chat Completions API → ChatCompletionClient
}
```

### ModelCatalog Class

```kotlin
class ModelCatalog private constructor(entries: Map<String, ModelEntry>) {
    fun resolve(name: String): ModelEntry           // Throws if not found
    fun resolveOrNull(name: String): ModelEntry?
    fun all(): List<ModelEntry>
    fun names(): Set<String>
    val size: Int
    operator fun contains(name: String): Boolean

    companion object {
        fun fromJson(jsonString: String): ModelCatalog
    }
}
```

Thread-safe after construction — the entry map is immutable.

---

## LLMClientFactory

> See: `llm/LLMClientFactory.kt`

Creates `LLMClient` instances from model names using the `ModelCatalog`.

```kotlin
class LLMClientFactory(
    catalog: ModelCatalog,
    apiKeyResolver: (String) -> String?,   // Env var name → API key value
    clientOverride: LLMClient? = null      // For testing
)
```

- **Caching**: Clients cached by `(provider, baseUrl, api)` tuple — multiple models from the same provider share a connection pool
- **Resolution**: `create(modelName)` → catalog lookup → API key resolution → client instantiation or cache hit
- **Thread-safe**: `ConcurrentHashMap` for cache
- **Test support**: `LLMClientFactory.forTest(catalog, client)` returns a factory that always uses the injected client

## Session Bootstrap

> See: `session/SessionLlmBootstrapper.kt`

Session startup creates the LLM stack in this order:
1. Load `llm_models.json` into `ModelCatalog` (cached per `AssetManager`)
2. Build `LLMClientFactory` with env-key resolver
3. Create runtime client from `SessionConfig.llm.backendType`

Behavior details:
- If `llm_models.json` is missing/malformed, bootstrap falls back to a minimal built-in catalog (`glm-5`)
- For cloud backend, required API keys are validated for `mainModel` (and `executorModel` in `PRO` mode) before client creation
- For local backend, bootstrap returns `LFMLLMClient` using `SessionConfig.llm.localConfig`

---

## Retry Infrastructure

### CloudLlmRetry

> See: `llm/CloudLlmRetry.kt`

Shared retry/backoff for non-streaming calls:

```kotlin
object CloudLlmRetry {
    suspend fun <T> executeWithRetry(tag, operationName, block: suspend () -> T): T
}
```

- Retries on `RateLimitException` and `TransientException`
- Exponential backoff: 1s → 2s → 4s → 8s → 16s (capped at 60s)
- Honors `retryAfterMs` from rate limit headers
- Max 5 retries

### CloudStreamRetryRunner

> See: `llm/CloudStreamRetryRunner.kt`

Shared retry scaffold for streaming calls:

```kotlin
suspend fun streamWithRetry(
    tag: String,
    emitToFlow: (LLMStreamEvent) -> Unit,
    attemptBlock: suspend (attempt: Int, emitter: StreamAttemptEmitter) -> Unit
): StreamRetryRunResult
```

- **No retry after partial output**: If events have already been emitted to the flow, retrying would produce duplicate output. Fails immediately with descriptive message.
- **Retry before output**: Same backoff policy as non-streaming
- Decision logic in `CloudStreamRetryPolicy.decide()`

### OpenAIErrorClassifier

> See: `llm/OpenAIErrorClassifier.kt`

Classifies raw exceptions into retry-relevant types:

| Pattern | Result |
|---------|--------|
| HTTP 429 or "rate limit" | `RateLimitException` (extracts retry-after from message) |
| `SocketTimeoutException` | `TransientException` |
| `UnknownHostException` / "Unable to resolve host" | `RuntimeException` (non-retryable network error) |
| HTTP 500/502/503/504 | `TransientException` |
| Other `IOException` | `TransientException` or `RuntimeException` (connectivity) |
| Everything else | `RuntimeException` |

---

## Supporting Files

### LlmLogger

> See: `llm/LlmLogger.kt`

Debug logging for LLM input/output. Logs system prompt, input items, tools, and response results at `Log.d` level.

### MessageContentExtractor

> See: `llm/MessageContentExtractor.kt`

Utility for extracting text content from various message content wrapper types.

### LeapFunctionInterop

> See: `llm/LeapFunctionInterop.kt`

Adapters for tool schema and argument conversion between OpenAI format and Leap SDK format. Contains `LeapToolSchemaAdapter` and `LeapJsonAdapter`.

---

## File Structure

```
llm/
├── LLMClient.kt              # Abstract base class + stream events + result types
├── OpenAIResponseClient.kt   # OpenAI Responses API client (streaming + non-streaming)
├── ChatCompletionClient.kt   # Chat Completions API client (OpenRouter, Novita, vLLM, etc.)
├── ChatCompletionInterop.kt  # ResponseInputItem ↔ ChatCompletion type conversion
├── LFMLLMClient.kt           # Local LFM client (LiquidAI Leap SDK)
├── LLMClientFactory.kt       # Catalog-driven client creation with caching
├── ModelCatalog.kt            # Model definitions (from llm_models.json)
├── CloudLlmRetry.kt          # Non-streaming retry with exponential backoff
├── CloudStreamRetryPolicy.kt # Streaming retry decision policy
├── CloudStreamRetryRunner.kt # Streaming retry scaffold
├── OpenAIErrorClassifier.kt  # Exception → retryable/non-retryable classification
├── LlmLogger.kt              # Debug logging for LLM I/O
├── LocalLLMConfig.kt         # Local model configuration (slug, quantization, options)
├── MessageContentExtractor.kt # Text extraction from message content wrappers
└── LeapFunctionInterop.kt    # OpenAI ↔ Leap tool schema adapters
```

---

## Related Docs

- [Session](session.md) - SessionServices creates LLMClient via LLMClientFactory
- [Loop](../agent/loop.md) - LLM calls during Turn execution
- [Protocol](../protocol/protocol.md) - SessionConfig with model and backend settings
- [Settings](../app/settings.md) - LLM backend and API key settings
- [Turn Prompt Anatomy](../agent/turn_prompt_anatomy.md) - Prompt composition sent to LLM

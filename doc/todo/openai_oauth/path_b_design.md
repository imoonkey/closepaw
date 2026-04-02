status: draft

# Path B Design: CodexResponseClient

Date: 2026-04-02
Ref: `doc/todo/openai_oauth/findings.md`, `.reference/code_agent/pi-mono/packages/ai/src/providers/openai-codex-responses.ts`
Depends on: OAuth infrastructure (implemented in `auth/`)

---

## 1. Goal

Implement a new `CodexResponseClient` that talks to `chatgpt.com/backend-api/codex/responses` using the OAuth access_token. This is the only working API path for OAuth users — the access_token has no platform API scopes (`api.responses.write`, `model.request`), so it cannot use `api.openai.com`.

After this work, OAuth users can run the full agent loop (onboarding demo and normal sessions) through their ChatGPT subscription.

---

## 2. Architecture Overview

```
                    ┌─────────────────────┐
                    │  LLMClientFactory    │
                    │                     │
                    │  api == RESPONSE    │
                    │  + isOAuth?         │
                    └────┬───────────┬────┘
                         │           │
              OAuth=true │           │ OAuth=false
                         ▼           ▼
              ┌──────────────┐ ┌──────────────────┐
              │ CodexResponse│ │ OpenAIResponse    │
              │ Client       │ │ Client            │
              │              │ │                   │
              │ OkHttp raw   │ │ OpenAI SDK        │
              │ SSE parsing  │ │ native streaming  │
              │ chatgpt.com  │ │ api.openai.com    │
              └──────────────┘ └──────────────────┘
```

Key design decision: **raw OkHttp + manual SSE parsing**, not the OpenAI SDK. The SDK hardcodes `api.openai.com` behavior and cannot target `chatgpt.com/backend-api`. The Codex endpoint also requires custom headers (`chatgpt-account-id`, `originator`) that the SDK doesn't support.

---

## 3. New Files

### 3.1 `llm/CodexResponseClient.kt`

The core client. Implements `LLMClient` abstract class.

```kotlin
package com.moonkey.androidagent.llm

class CodexResponseClient(
    accessToken: String
) : LLMClient() {

    companion object {
        private const val TAG = "CodexResponseClient"
        private const val CODEX_URL = "https://chatgpt.com/backend-api/codex/responses"
    }

    private val accessToken: String = accessToken
    private val accountId: String = extractAccountId(accessToken)
    private val httpClient: OkHttpClient = buildHttpClient()

    // --- LLMClient interface ---

    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): ResponsesResult

    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): Flow<LLMStreamEvent>

    override suspend fun cleanup() { /* no-op */ }

    // --- Internals ---

    private fun buildHttpClient(): OkHttpClient
    private fun buildRequest(body: String): Request
    private fun extractAccountId(token: String): String
}
```

**Non-streaming (`chatWithTools`)**: The Codex backend requires `stream: true`. Internally sends a streaming request, collects all SSE events, and returns the assembled `ResponsesResult`. Reuses `CloudLlmRetry.executeWithRetry` for retry logic.

**Streaming (`chatWithToolsStreaming`)**: Returns `Flow<LLMStreamEvent>` via `callbackFlow`. Reuses `streamWithRetry` for retry/backoff. Parses SSE line-by-line from OkHttp response body.

**Account ID extraction**: Parses the access_token JWT to get `https://api.openai.com/auth.chatgpt_account_id`. Reuses `OAuthCodexValidator.extractAccountId()` logic (move to a shared util or call directly).

### 3.2 `llm/CodexRequestBuilder.kt`

Serializes `ResponseInputItem` + `FunctionTool` lists to the JSON body format expected by the Codex endpoint.

```kotlin
package com.moonkey.androidagent.llm

/**
 * Builds JSON request bodies for chatgpt.com/backend-api/codex/responses.
 *
 * Converts OpenAI SDK types (ResponseInputItem, FunctionTool) to JSON
 * since the SDK's built-in serialization targets api.openai.com, not
 * the ChatGPT backend.
 */
object CodexRequestBuilder {

    fun buildRequestBody(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): String

    // --- Internal converters ---

    internal fun convertInputItems(items: List<ResponseInputItem>): JSONArray
    internal fun convertTools(tools: List<FunctionTool>): JSONArray
}
```

**Output JSON shape** (matches pi-mono reference):

```json
{
  "model": "gpt-5.4",
  "stream": true,
  "store": false,
  "instructions": "<system prompt>",
  "input": [ ... ],
  "tool_choice": "auto",
  "parallel_tool_calls": true,
  "tools": [ ... ]
}
```

**Critical constraints** (discovered through testing, documented in `findings.md`):
- `stream` MUST be `true` (400 if false)
- `instructions` MUST be present (400 if missing)
- `max_output_tokens` MUST NOT be present (400 if included)
- `input` must be an array of objects, not a string
- Message content must be `[{"type": "input_text", "text": "..."}]`, not a bare string

### 3.3 `llm/CodexSseParser.kt`

Parses SSE events from an OkHttp response body and maps Codex-specific event types to standard Responses API events.

```kotlin
package com.moonkey.androidagent.llm

/**
 * Parses Server-Sent Events from a raw byte stream.
 *
 * SSE protocol: lines prefixed with "data:" separated by blank lines.
 * The event type is embedded in the JSON payload's "type" field,
 * not in the SSE "event:" field.
 */
object CodexSseParser {

    data class SseEvent(val type: String, val json: JSONObject)

    /**
     * Parse SSE events from an OkHttp response body source.
     * Yields parsed JSON events, skipping [DONE] markers.
     */
    fun parse(source: BufferedSource): Sequence<SseEvent>

    /**
     * Map a Codex SSE event to an LLMStreamEvent.
     *
     * Handles Codex-specific differences:
     * - response.done → Completed (standard API uses response.completed)
     * - response.incomplete → Completed
     * - response.failed → Failed
     * - error → Failed
     */
    fun mapToStreamEvent(
        event: SseEvent,
        toolCallAccumulator: ToolCallAccumulator
    ): LLMStreamEvent?

    /**
     * Accumulates function_call_arguments.delta events into complete tool calls.
     */
    class ToolCallAccumulator {
        fun onItemAdded(item: JSONObject)
        fun onArgumentsDelta(delta: String)
        fun onItemDone(item: JSONObject): LLMToolCall?
    }
}
```

---

## 4. Modified Files

### 4.1 `llm/LLMClientFactory.kt`

Add OAuth routing logic. When the model uses `ApiType.RESPONSE` and the caller signals OAuth auth, create `CodexResponseClient` instead of `OpenAIResponseClient`.

```kotlin
class LLMClientFactory(
    private val catalog: ModelCatalog,
    private val apiKeyResolver: (String) -> String?,
    private val clientOverride: LLMClient? = null
) {
    fun create(modelName: String): LLMClient {
        clientOverride?.let { return it }

        val entry = catalog.resolve(modelName)
        val cacheKey = "${entry.provider}|${entry.effectiveBaseUrl ?: "default"}|${entry.api}|${isOAuth(entry)}"

        return clientCache.computeIfAbsent(cacheKey) {
            val apiKey = resolveApiKey(entry)
            when (entry.api) {
                ApiType.RESPONSE -> {
                    if (isOAuth(entry)) {
                        CodexResponseClient(apiKey)          // NEW
                    } else {
                        OpenAIResponseClient(apiKey, entry.effectiveBaseUrl)
                    }
                }
                ApiType.CHAT -> ChatCompletionClient(apiKey, entry.effectiveBaseUrl)
            }
        }
    }

    /**
     * Detect OAuth auth method via the __AUTH_METHOD_OPENAI signal key.
     * Only applies to OPENAI provider models.
     */
    private fun isOAuth(entry: ModelEntry): Boolean {
        if (entry.provider != LLMProvider.OPENAI) return false
        return apiKeyResolver("__AUTH_METHOD_OPENAI") == "oauth"
    }
}
```

**Design rationale**: The `apiKeys` map already uses the `__` prefix convention for metadata (`__BASE_URL_OPENAI`). Adding `__AUTH_METHOD_OPENAI` follows the same pattern. No changes to `ModelCatalog`, `ModelEntry`, `ApiType`, or `SessionConfig` needed.

### 4.2 `app/AppSettingsState.kt`

Update `buildApiKeys()` to include the OAuth signal when auth method is OAuth.

```kotlin
fun buildApiKeys(): Map<String, String> = buildMap {
    if (apiKey.isNotBlank()) put("OPENAI_API_KEY", apiKey)
    if (openRouterApiKey.isNotBlank()) put("OPENROUTER_API_KEY", openRouterApiKey)
    if (novitaApiKey.isNotBlank()) put("NOVITA_API_KEY", novitaApiKey)
    if (openaiBaseUrl.isNotBlank()) put("__BASE_URL_OPENAI", openaiBaseUrl)
    // Signal OAuth auth method so LLMClientFactory creates CodexResponseClient
    if (authMethod == "oauth") put("__AUTH_METHOD_OPENAI", "oauth")    // NEW
}
```

The `authMethod` property reads from `OnboardingStore` (already persisted during OAuth onboarding as `"oauth"` or `"manual"`).

### 4.3 No changes needed

| File | Why unchanged |
|---|---|
| `ModelCatalog.kt` | No new ApiType or provider. OAuth routing is in the factory, not the catalog. |
| `SessionLlmBootstrapper.kt` | Already passes `apiKeys` map through to factory. The `__AUTH_METHOD_OPENAI` key flows through without changes. |
| `DefaultOnboardingDemoController.kt` | Already calls `settingsState.buildApiKeys()` which will include the OAuth signal. Session creation and agent loop work unchanged — they call `LLMClientFactory.create()` which handles routing. |
| `OnboardingViewModel.kt` | Already saves auth method and access token during OAuth flow. |

---

## 5. SSE Event Mapping

### 5.1 Codex → LLMStreamEvent

| Codex SSE event type | LLMStreamEvent | Notes |
|---|---|---|
| `response.created` | `Created(responseId)` | Extract `response.id` |
| `response.output_text.delta` | `TextDelta(delta)` | Extract `delta` string |
| `response.output_item.done` (function_call) | `ToolCallDone(toolCall)` | Build from `item.call_id`, `item.name`, `item.arguments` |
| `response.done` | `Completed` | **Codex-specific**: standard API uses `response.completed` |
| `response.completed` | `Completed` | Also accepted (normalize both) |
| `response.incomplete` | `Completed` | Treat as completed |
| `response.failed` | `Failed(message)` | Extract `response.error.message` |
| `error` | `Failed(message)` | Extract `message` or `code` |
| `response.output_item.added` | (no emit) | Track state for function call accumulation |
| `response.function_call_arguments.delta` | (no emit) | Accumulate into tool call builder |
| `response.content_part.added` | (no emit) | Ignored — text comes via output_text.delta |
| `response.output_item.done` (message) | (no emit) | Text already emitted via deltas |

### 5.2 Key Difference from Standard API

The pi-mono reference (`mapCodexEvents`) normalizes these terminal events:

```
response.done       → response.completed
response.completed  → response.completed
response.incomplete → response.completed
```

Our `CodexSseParser.mapToStreamEvent()` does the same normalization, mapping all three to `LLMStreamEvent.Completed`.

### 5.3 Status Normalization

The Codex backend may return non-standard `response.status` values. Valid statuses (from pi-mono):

```
completed, incomplete, failed, cancelled, queued, in_progress
```

We only care about `failed` (→ `LLMStreamEvent.Failed`). All others map to `Completed` when they appear in a terminal event.

---

## 6. Request Serialization Details

### 6.1 ResponseInputItem → JSON

The `CodexRequestBuilder` walks the `ResponseInputItem` list and produces JSON using `org.json.JSONObject`/`JSONArray`. This is the same pattern used in `OAuthCodexValidator.validate()`.

| ResponseInputItem variant | JSON output |
|---|---|
| User message (easy input) | `{"role": "user", "content": [{"type": "input_text", "text": "..."}]}` |
| User message with image | `{"role": "user", "content": [{"type": "input_text", ...}, {"type": "input_image", "image_url": "data:...", "detail": "auto"}]}` |
| Function call (history) | `{"type": "function_call", "call_id": "...", "name": "...", "arguments": "..."}` |
| Function call output | `{"type": "function_call_output", "call_id": "...", "output": "..."}` |

### 6.2 FunctionTool → JSON

```json
{
  "type": "function",
  "name": "click_element",
  "description": "Click on a UI element",
  "parameters": { ... JSON schema ... }
}
```

### 6.3 Type Introspection

The OpenAI Java SDK's `ResponseInputItem` exposes type-check and cast methods:

```kotlin
item.isEasyInputMessage() → item.asEasyInputMessage()
    .role()    → String
    .content() → Content (union: text string | content part list)

item.isFunctionCall() → item.asFunctionCall()
    .callId()    → String
    .name()      → String
    .arguments() → String
    .id()        → String (item ID, optional in request)

item.isFunctionCallOutput() → item.asFunctionCallOutput()
    .callId() → String
    .output() → Output (string or structured)
```

For `FunctionTool`:
```kotlin
tool.name()        → String
tool.description() → Optional<String>
tool.parameters()  → FunctionTool.Parameters (JSON schema)
```

The `parameters()` method returns an SDK wrapper around the JSON schema. We serialize it via its `toString()` or internal JSON representation.

---

## 7. HTTP Request Details

### 7.1 Headers

```http
POST https://chatgpt.com/backend-api/codex/responses
Authorization: Bearer {access_token}
chatgpt-account-id: {extracted from JWT}
originator: pi
OpenAI-Beta: responses=experimental
Accept: text/event-stream
Content-Type: application/json
```

All headers are required. Missing `chatgpt-account-id` → 401. Missing `originator` → may work but matches the pi-mono reference for compatibility.

### 7.2 Account ID Extraction

From the access_token JWT payload:

```json
{
  "https://api.openai.com/auth": {
    "chatgpt_account_id": "acct_abc123..."
  }
}
```

Extract path: `payload["https://api.openai.com/auth"]["chatgpt_account_id"]`

This logic already exists in `OAuthCodexValidator.extractAccountId()`. Refactor to shared utility or call directly.

### 7.3 OkHttp Client Configuration

```kotlin
private fun buildHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)  // long timeout for streaming
        .writeTimeout(30, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                sslSocketFactory(InsecureSslConfig.sslSocketFactory)
                trustManager(InsecureSslConfig.trustManager)
            }
        }
        .build()
```

Follows the same debug SSL pattern as `OpenAIResponseClient` and `ChatCompletionClient`.

---

## 8. Streaming Implementation

### 8.1 Flow Structure

```kotlin
override fun chatWithToolsStreaming(
    systemPrompt: String,
    inputItems: List<ResponseInputItem>,
    tools: List<FunctionTool>,
    model: String
): Flow<LLMStreamEvent> = callbackFlow {
    val retryResult = streamWithRetry(
        tag = TAG,
        emitToFlow = { event -> trySend(event) }
    ) { attempt, emitter ->
        val body = CodexRequestBuilder.buildRequestBody(
            systemPrompt, inputItems, tools, model
        )
        val request = buildRequest(body)

        withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    handleErrorResponse(response)  // throws classified exception
                }

                val source = response.body?.source()
                    ?: throw RuntimeException("No response body")

                val accumulator = CodexSseParser.ToolCallAccumulator()
                var sawCompletion = false

                for (sseEvent in CodexSseParser.parse(source)) {
                    val streamEvent = CodexSseParser.mapToStreamEvent(
                        sseEvent, accumulator
                    )
                    if (streamEvent != null) {
                        emitter.emit(streamEvent)
                        if (streamEvent is LLMStreamEvent.Completed) {
                            sawCompletion = true
                        }
                    }
                }

                if (!sawCompletion) {
                    throw RuntimeException("Stream ended without completion")
                }
            }
        }
    }

    // Terminal handling (same pattern as OpenAIResponseClient)
    if (retryResult.completed) {
        close()
    } else {
        if (!retryResult.failureEmitted) {
            trySend(LLMStreamEvent.Failed(
                retryResult.lastError?.message ?: "Unknown error"
            ))
        }
        close()
    }

    awaitClose { Log.d(TAG, "Streaming flow closed") }
}
```

### 8.2 SSE Line Parsing

```kotlin
fun parse(source: BufferedSource): Sequence<SseEvent> = sequence {
    val dataBuilder = StringBuilder()

    while (!source.exhausted()) {
        val line = source.readUtf8Line() ?: break
        when {
            line.startsWith("data:") -> {
                val data = line.substring(5).trim()
                if (dataBuilder.isNotEmpty()) dataBuilder.append("\n")
                dataBuilder.append(data)
            }
            line.isEmpty() -> {
                if (dataBuilder.isNotEmpty()) {
                    val raw = dataBuilder.toString().trim()
                    dataBuilder.clear()
                    if (raw == "[DONE]") continue
                    val json = JSONObject(raw)
                    val type = json.optString("type", "")
                    if (type.isNotEmpty()) {
                        yield(SseEvent(type, json))
                    }
                }
            }
            // Ignore event:, id:, retry: lines
        }
    }
}
```

### 8.3 Non-streaming Implementation

```kotlin
override suspend fun chatWithTools(
    systemPrompt: String,
    inputItems: List<ResponseInputItem>,
    tools: List<FunctionTool>,
    model: String
): ResponsesResult = withContext(Dispatchers.IO) {
    CloudLlmRetry.executeWithRetry(tag = TAG, operationName = "codex chatWithTools") {
        val body = CodexRequestBuilder.buildRequestBody(
            systemPrompt, inputItems, tools, model
        )
        val request = buildRequest(body)

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) handleErrorResponse(response)

            val source = response.body?.source()
                ?: throw RuntimeException("No response body")

            // Collect streaming events into a single result
            val textContent = StringBuilder()
            val toolCalls = mutableListOf<LLMToolCall>()
            var responseId = "unknown"
            val accumulator = CodexSseParser.ToolCallAccumulator()

            for (event in CodexSseParser.parse(source)) {
                when (event.type) {
                    "response.created" -> {
                        responseId = event.json.optJSONObject("response")
                            ?.optString("id", "unknown") ?: "unknown"
                    }
                    "response.output_text.delta" -> {
                        textContent.append(event.json.optString("delta", ""))
                    }
                    "response.output_item.done" -> {
                        val item = event.json.optJSONObject("item")
                        if (item?.optString("type") == "function_call") {
                            val tc = accumulator.onItemDone(item)
                            if (tc != null) toolCalls.add(tc)
                        }
                    }
                }
                // Delegate remaining event handling to accumulator
                CodexSseParser.mapToStreamEvent(event, accumulator)
            }

            ResponsesResult(
                textContent = textContent.toString().takeIf { it.isNotEmpty() },
                toolCalls = toolCalls,
                responseId = responseId
            )
        }
    }
}
```

---

## 9. Error Handling

### 9.1 HTTP Error Classification

Reuse the existing `OpenAIErrorClassifier` pattern but handle Codex-specific errors:

```kotlin
private fun handleErrorResponse(response: okhttp3.Response) {
    val body = response.body?.string() ?: ""
    val status = response.code

    // Parse Codex error format
    val parsed = try { JSONObject(body) } catch (_: Exception) { null }
    val error = parsed?.optJSONObject("error")
    val code = error?.optString("code", "") ?: ""
    val message = error?.optString("message", "") ?: body

    when {
        status == 429 || code.contains("rate_limit") || code.contains("usage_limit") -> {
            // ChatGPT usage limit — user-facing message
            val planType = error?.optString("plan_type", "")
            val resetsAt = error?.optLong("resets_at", 0) ?: 0
            val mins = if (resetsAt > 0)
                maxOf(0, (resetsAt * 1000 - System.currentTimeMillis()) / 60000)
            else null
            val friendly = buildString {
                append("ChatGPT usage limit reached")
                if (planType?.isNotEmpty() == true) append(" ($planType plan)")
                append(".")
                if (mins != null) append(" Try again in ~$mins min.")
            }
            throw RateLimitException(friendly)
        }
        status == 401 || status == 403 -> {
            throw IllegalStateException("Token rejected: $message")
        }
        status in 500..599 -> {
            throw TransientException("Server error: HTTP $status", null)
        }
        else -> {
            throw RuntimeException("Codex API error: HTTP $status $message")
        }
    }
}
```

### 9.2 Usage Limit UX

The ChatGPT backend returns structured usage limit errors:

```json
{
  "error": {
    "code": "usage_limit_reached",
    "message": "...",
    "plan_type": "plus",
    "resets_at": 1712100000
  }
}
```

The `RateLimitException` message is user-facing and should be displayed in the chat UI (e.g., "ChatGPT usage limit reached (plus plan). Try again in ~15 min."). The existing retry logic in `CloudLlmRetry` handles `RateLimitException` with backoff.

---

## 10. Factory Routing — Full Flow

```
User completes OAuth onboarding
  → OnboardingViewModel saves auth_method = "oauth"
  → OnboardingStore persists auth_method
  → access_token saved to AppSettingsState.apiKey

User starts session (demo or normal)
  → MainActivity.refreshOAuthIfNeeded()   // ensure token is fresh
  → AppSettingsState.buildApiKeys()
      returns: {
        "OPENAI_API_KEY": "<access_token>",
        "__AUTH_METHOD_OPENAI": "oauth"        // signal
      }
  → SessionLlmBootstrapper.create(config, context, apiKeys)
  → LLMClientFactory(catalog, apiKeyResolver = { apiKeys[it] })
  → factory.create("gpt-5.4")
      → entry.api == RESPONSE, entry.provider == OPENAI
      → isOAuth(entry) checks apiKeyResolver("__AUTH_METHOD_OPENAI") == "oauth" → true
      → CodexResponseClient(apiKey)
          → extracts accountId from JWT
          → ready to call chatgpt.com/backend-api/codex/responses
```

For manual API key users, `__AUTH_METHOD_OPENAI` is absent, so `isOAuth()` returns false, and the factory creates `OpenAIResponseClient` as before. Zero behavior change for existing users.

---

## 11. Demo Controller Integration

The demo controller (`DefaultOnboardingDemoController`) needs no code changes. The routing is transparent:

1. Demo calls `settingsState.buildApiKeys()` → includes `__AUTH_METHOD_OPENAI` for OAuth users
2. `AgentSession.create()` → `SessionLlmBootstrapper` → `LLMClientFactory`
3. Factory creates `CodexResponseClient` for OAuth users
4. Agent loop calls `chatWithToolsStreaming()` → hits Codex endpoint
5. SSE events are parsed and mapped to `LLMStreamEvent` → agent loop proceeds normally

**Token freshness**: OAuth tokens are obtained moments before the demo runs (during onboarding step 4). The 1-hour expiry window is sufficient. For post-onboarding sessions, `MainActivity.refreshOAuthIfNeeded()` runs before session creation.

---

## 12. Edge Cases

| Case | Behavior |
|---|---|
| Account ID missing from JWT | `CodexResponseClient` constructor throws. Factory propagates error. Session creation fails with actionable message. |
| Token expired mid-stream | Codex returns 401 mid-SSE. OkHttp read fails. `streamWithRetry` classifies as non-retryable (401). `LLMStreamEvent.Failed` emitted. Agent loop terminates with error. |
| ChatGPT usage limit hit | 429 with structured error. Parsed into `RateLimitException` with user-friendly message including reset time. Retry logic backs off. |
| Codex backend 500/502/503 | `TransientException` → `streamWithRetry` retries with exponential backoff (up to 5 retries). |
| Network timeout | OkHttp throws `SocketTimeoutException`. Classified as `TransientException`. Retried. |
| User switches from OAuth to manual API key | `OnboardingStore.auth_method` changes to `"manual"`. `buildApiKeys()` no longer includes `__AUTH_METHOD_OPENAI`. Factory creates `OpenAIResponseClient`. Clean switchover. |
| Model uses `ApiType.CHAT` with OAuth | `isOAuth()` only triggers for `RESPONSE` API type. Chat Completions models are unaffected by OAuth. If an OAuth user selects a CHAT model, it fails with missing scopes (expected — Chat Completions requires `model.request` scope). |
| `response.done` vs `response.completed` | Both mapped to `LLMStreamEvent.Completed`. The parser handles either form. |
| Empty tool arguments in SSE | `ToolCallAccumulator` handles empty/missing arguments gracefully. Returns `"{}"` as default. |

---

## 13. What This Design Does NOT Change

- **No new `ApiType` enum value** — OAuth routing is in the factory, not the catalog
- **No `llm_models.json` changes** — same model entries for both OAuth and API key users
- **No `SessionConfig` changes** — auth method is signaled through `apiKeys` map
- **No `LLMClient` interface changes** — `CodexResponseClient` implements the same abstract methods
- **No new dependencies** — uses OkHttp (already in project) and `org.json` (Android stdlib)
- **No `ChatCompletionInterop` equivalent** — `CodexRequestBuilder` serializes directly to JSON since the Codex endpoint uses the same Responses API input format

---

## 14. Implementation Tasks

### Task 1: `codex-request-builder`
**Files**: `llm/CodexRequestBuilder.kt`
**Scope**: ResponseInputItem → JSON serialization, FunctionTool → JSON serialization, request body assembly.
**Test**: Unit test with hand-crafted ResponseInputItem lists, verify JSON output matches expected format.
**Deps**: none

### Task 2: `codex-sse-parser`
**Files**: `llm/CodexSseParser.kt`
**Scope**: SSE line parsing, event type mapping (response.done → Completed), ToolCallAccumulator.
**Test**: Unit test with recorded SSE byte streams from Codex backend.
**Deps**: none

### Task 3: `codex-response-client`
**Files**: `llm/CodexResponseClient.kt`
**Scope**: OkHttp integration, streaming + non-streaming implementations, error classification, account ID extraction.
**Test**: Integration test against Codex backend with a real OAuth token (manual/CI).
**Deps**: Task 1, Task 2

### Task 4: `factory-oauth-routing`
**Files**: `llm/LLMClientFactory.kt`, `app/AppSettingsState.kt`
**Scope**: `isOAuth()` detection, `__AUTH_METHOD_OPENAI` signal in `buildApiKeys()`, cache key update.
**Test**: Unit test: factory creates `CodexResponseClient` when signal present, `OpenAIResponseClient` when absent.
**Deps**: Task 3

### Task 5: `end-to-end-validation`
**Scope**: Run onboarding demo with OAuth user. Verify full agent loop works (submit goal → LLM call → tool execution → completion).
**Deps**: Task 4

---

## 15. Trade-offs

- **Raw OkHttp vs OpenAI SDK**: More code to write (SSE parser, request builder) but necessary — the SDK cannot target chatgpt.com. The SSE parser is ~60 lines; the request builder is ~100 lines. Acceptable complexity for a clean separation.
- **`__AUTH_METHOD_OPENAI` signal vs constructor parameter**: Using the apiKeys map avoids changing `LLMClientFactory` constructor, `SessionLlmBootstrapper`, `AgentSession.create()`, and `SessionConfig`. The `__` prefix convention is already established. Minimal blast radius.
- **No separate `ApiType.CODEX_RESPONSE`**: Adding an enum value would require changes to `ModelCatalog`, `JsonModelEntry.toModelEntry()`, and `llm_models.json`. The runtime routing approach keeps the catalog model-centric (what model) vs auth-centric (how to auth). The same model entry works for both OAuth and API key users.
- **Account ID extracted per-client, not per-request**: The JWT is decoded once in the constructor. If the token is refreshed, a new `CodexResponseClient` instance is created (the factory cache is cleared on session teardown). No stale account ID risk.
- **No WebSocket transport**: The pi-mono reference supports both SSE and WebSocket. We implement SSE only — simpler, sufficient for mobile, and validated on device. WebSocket can be added later if needed for latency optimization.

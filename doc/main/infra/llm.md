# LLM Integration

> LLM clients, model catalog, streaming, and retry infrastructure.
> Last updated: 2026-03-06 (uncommitted)

## Overview

Multiple LLM backends through a unified `LLMClient` abstract class, with streaming and non-streaming support. Catalog-driven: models defined in `assets/llm_models.json`, `LLMClientFactory` creates/reuses the correct client based on model name.

---

## LLMClient

> See: `llm/LLMClient.kt`

Abstract base class. Uses OpenAI Responses API types as input, custom `LLMStreamEvent` for output.

- `chatWithTools(systemPrompt, inputItems, tools, model)` → `ResponsesResult`
- `chatWithToolsStreaming(...)` → `Flow<LLMStreamEvent>`

**Stream events:** `Created(responseId)`, `TextDelta(delta)`, `ToolCallDone(toolCall)`, `Completed`, `Failed(error)`.

**Result types:** `ResponsesResult(textContent, toolCalls, responseId)`, `LLMToolCall(callId, name, arguments)`.

**Exceptions:** `RateLimitException(message, retryAfterMs?)` (429), `TransientException(message, cause?)` (timeout, 5xx).

Constants: `MAX_RETRIES = 5`, backoff 1s → 60s with 2× multiplier.

---

## Implementations

### OpenAIResponseClient

> See: `llm/OpenAIResponseClient.kt`

Cloud client using OpenAI Responses API with native function calling. Non-streaming via `CloudLlmRetry.executeWithRetry()`; streaming via `streamWithRetry()`. Errors classified via `OpenAIErrorClassifier`.

### ChatCompletionClient

> See: `llm/ChatCompletionClient.kt`

Cloud client using Chat Completions API. Works with any OpenAI-compatible endpoint (OpenRouter, Novita, vLLM). Converts types via `ChatCompletionInterop`. Tool call deltas accumulated incrementally by index.

### ChatCompletionInterop

> See: `llm/ChatCompletionInterop.kt`

Converts between Responses API and Chat Completions API types. Groups adjacent function calls into a single assistant message (Chat API requirement). Handles multimodal content.

### LFMLLMClient

> See: `llm/LFMLLMClient.kt`

On-device inference using LiquidAI Leap SDK. No network after model download. Model state: `NotLoaded → Downloading(progress) → Loading → Ready` or `Error`. Thread-safe via `Mutex`.

Config: `LocalLLMConfig(modelSlug = "LFM2.5-1.2B-Instruct", quantizationSlug = "Q4_K_M")`.

---

## Model Catalog

> See: `llm/ModelCatalog.kt`

Catalog-driven model resolution from `assets/llm_models.json`.

**ModelEntry**: `name` (stable ID), `displayName`, `provider: LLMProvider`, `api: ApiType`, `modelId` (sent to API), optional `baseUrl`/`apiKeyEnv` overrides, `supportsVision`.

**LLMProvider**: `OPENAI` (default key: `OPENAI_API_KEY`), `OPENROUTER` (base: `openrouter.ai`), `NOVITA` (base: `api.novita.ai`).

**ApiType**: `RESPONSE` (→ OpenAIResponseClient), `CHAT` (→ ChatCompletionClient).

**ModelCatalog**: `resolve(name)`, `resolveOrNull(name)`, `all()`, `names()`, `withBaseUrlOverrides(overrides)`. Thread-safe after construction.

---

## LLMClientFactory

> See: `llm/LLMClientFactory.kt`

Creates `LLMClient` instances from model names. Cached by `(provider, baseUrl, api)` tuple — models from the same provider share a connection pool. Thread-safe via `ConcurrentHashMap`.

## Session Bootstrap

> See: `session/SessionLlmBootstrapper.kt`

Startup: load catalog → extract provider base URL overrides (`__BASE_URL_<PROVIDER>`) → apply via `withBaseUrlOverrides()` → build factory → create client.

Fallback: if `llm_models.json` missing/malformed, uses built-in catalog (`glm-5`). For local backend, returns `LFMLLMClient`.

---

## Retry Infrastructure

**CloudLlmRetry** (`llm/CloudLlmRetry.kt`): Non-streaming retry with exponential backoff (1s → 60s). Retries on `RateLimitException` and `TransientException`. Honors `retryAfterMs`.

**CloudStreamRetryRunner** (`llm/CloudStreamRetryRunner.kt`): Streaming retry. **No retry after partial output** (would produce duplicates). Same backoff policy pre-output.

**OpenAIErrorClassifier** (`llm/OpenAIErrorClassifier.kt`): Classifies raw exceptions: HTTP 429 → `RateLimitException`, timeout/5xx → `TransientException`, `UnknownHostException` → non-retryable.

---

## Supporting Files

**InsecureSslConfig** (`llm/InsecureSslConfig.kt`): SSL bypass for debug builds when emulator clock is frozen (AndroidWorld eval). Trust-all manager + no-op certificate validation.

**LlmLogger**: Debug logging for LLM I/O. **MessageContentExtractor**: Text extraction from message wrappers. **LeapFunctionInterop**: OpenAI ↔ Leap tool schema adapters.

---

## File Structure

```
llm/
├── LLMClient.kt              # Abstract base + stream events + result types
├── OpenAIResponseClient.kt   # OpenAI Responses API
├── ChatCompletionClient.kt   # Chat Completions API
├── ChatCompletionInterop.kt  # Type conversion
├── LFMLLMClient.kt           # Local LFM (Leap SDK)
├── InsecureSslConfig.kt      # SSL bypass (debug only)
├── LLMClientFactory.kt       # Catalog-driven client creation
├── ModelCatalog.kt            # Model definitions
├── CloudLlmRetry.kt          # Non-streaming retry
├── CloudStreamRetryPolicy.kt # Streaming retry policy
├── CloudStreamRetryRunner.kt # Streaming retry scaffold
├── OpenAIErrorClassifier.kt  # Exception classification
├── LlmLogger.kt              # Debug logging
├── LocalLLMConfig.kt         # Local model config
├── MessageContentExtractor.kt # Text extraction
└── LeapFunctionInterop.kt    # OpenAI ↔ Leap adapters
```

## Related Docs

- [Session](session.md) - SessionServices creates LLMClient
- [Loop](../agent/loop.md) - LLM calls during Turn execution
- [Config](../protocol/config.md) - SessionConfig with model/backend settings
- [Turn Prompt Anatomy](../agent/turn_prompt_anatomy.md) - Prompt composition

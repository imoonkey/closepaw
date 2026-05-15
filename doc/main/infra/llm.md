# LLM Integration

> LLM clients, model catalog, streaming, and retry infrastructure.
> Last updated: 2026-04-18 (auth-setting-cleanup: flat LLMProvider, AuthStore, header-supplier Codex)

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

Cloud client using OpenAI Responses API with native function calling. Non-streaming via `CloudLlmRetry.executeWithRetry()`; streaming via `streamWithRetry()`. Errors classified via `OpenAIErrorClassifier`. The streaming path holds the active `StreamResponse` in an `AtomicReference<AutoCloseable>` and closes it from `awaitClose` so flow cancellation stops an in-flight network read (mirrors the pattern below).

### ChatCompletionClient

> See: `llm/ChatCompletionClient.kt`

Cloud client using Chat Completions API. Works with any OpenAI-compatible endpoint (OpenRouter, Novita, vLLM). Converts types via `ChatCompletionInterop`. Tool call deltas accumulated incrementally by index. Like `OpenAIResponseClient`, holds the active stream in an `AtomicReference<AutoCloseable>` and cancels it on flow close.

### CodexResponseClient

> See: `llm/CodexResponseClient.kt`

Cloud client for OAuth users, targeting `chatgpt.com/backend-api/codex/responses`. OAuth access tokens lack platform API scopes, so they cannot use `api.openai.com`. Uses raw OkHttp + manual SSE parsing via `CodexRequestBuilder` (JSON serialization) and `CodexSseParser` (SSE parsing with parallel-safe `ToolCallAccumulator`). Routed by `LLMClientFactory` when `entry.provider == OPENAI_CODEX`. Constructor takes a `suspend () -> CodexHeaders` supplier; every request reads fresh `accessToken`/`chatgptAccountId`/`email` from `AuthStore.codexHeaders()` so account switches and token rotations work without invalidating the cached client. The active OkHttp `Call` is stored in an `AtomicReference` and cancelled from `awaitClose`.

Wire format note: Codex requires wrapped content arrays where user messages use `"type": "input_text"` and assistant messages use `"type": "output_text"` (not `input_text` — the API rejects it with HTTP 400).

All three streaming clients skip the `StringBuilder` text accumulator and the tool-call echo list when `LlmLogger.isVerboseEnabled` is `false` — those buffers exist only to feed `LlmLogger.logOutput()`, which returns immediately in release builds.

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

**LLMProvider** (flat enum, mode encoded per entry):
- `OPENAI_API` (mode=ApiKey, default key: `OPENAI_API_KEY`)
- `OPENAI_CODEX` (mode=OAuth, target: `chatgpt.com/backend-api/codex/responses`)
- `OPENROUTER` (mode=ApiKey, base: `openrouter.ai`)
- `NOVITA` (mode=ApiKey, base: `api.novita.ai`)
- `LOCAL_LFM` (mode=Local, on-device LFMLLMClient)

`provider.mode: AuthMode` accessor drives the UI's three-tab grouping (OAuth / API Key / Local). The split between `OPENAI_API` and `OPENAI_CODEX` is what lets the factory route purely on provider — no `__AUTH_METHOD_OPENAI` signal key, no `isOAuth` sniff.

**ApiType**: `RESPONSE` (→ OpenAIResponseClient), `CHAT` (→ ChatCompletionClient).

**ModelCatalog**: `resolve(name)`, `resolveOrNull(name)`, `all()`, `names()`, `withBaseUrlOverrides(overrides)`. Thread-safe after construction.

---

## LLMClientFactory

> See: `llm/LLMClientFactory.kt`

Creates `LLMClient` instances from model names. Constructor takes the catalog, an `AuthStore` (single credential source), and a base-URL override map. Cached as `ConcurrentHashMap<modelName, Entry(generation, client)>` — atomic `compute()` for lookup+rebuild guarantees that a credential rotation never returns a stale client (factory consults `authStore.generation(provider)` and rebuilds when it changes). Routes purely on `entry.provider`: `OPENAI_API`/`OPENROUTER`/`NOVITA` → `OpenAIResponseClient`/`OpenAIChatClient` with `authStore.requireApiKey(provider)`; `OPENAI_CODEX` → `CodexResponseClient` with `headerSupplier = { authStore.codexHeaders() }`; `LOCAL_LFM` → `LFMLLMClient`. `requireApiKey` throws typed `MissingCredential` / `WrongCredentialType` errors that runtime surfaces as a startup-failure banner deep-link.

## Session Bootstrap

> See: `session/SessionLlmBootstrapper.kt`

Startup: load catalog → apply base-URL overrides → build factory with the app-scoped `AuthStore` (from `AuthStoreHolder.get(context)`) → `ensureRequiredCredentials` checks `authStore.has(provider)` for the selected main and subagent models → create client.

Fallback: if `llm_models.json` missing/malformed, uses built-in catalog (`glm-5`). For local backend, returns `LFMLLMClient`.

---

## Retry Infrastructure

**CloudLlmRetry** (`llm/CloudLlmRetry.kt`): Non-streaming retry with exponential backoff (1s → 60s). Retries on `RateLimitException` and `TransientException`. Honors `retryAfterMs`.

**CloudStreamRetryRunner** (`llm/CloudStreamRetryRunner.kt`): Streaming retry. **No retry after partial output** (would produce duplicates). Same backoff policy pre-output.

**OpenAIErrorClassifier** (`llm/OpenAIErrorClassifier.kt`): Classifies raw exceptions: HTTP 429 → `RateLimitException`, timeout/5xx → `TransientException`, `UnknownHostException` → non-retryable.

---

## Supporting Files

**InsecureSslConfig** (`debug/llm/InsecureSslConfig.kt`): SSL bypass for debug builds when emulator clock is frozen (AndroidWorld eval). Trust-all manager + no-op certificate validation. Compile-time debug-only — release source set has a no-op stub that returns null.

**LlmLogger**: Debug logging for LLM I/O. **LeapFunctionInterop**: OpenAI ↔ Leap tool schema adapters. **ChatCompletionInterop.extractStringContent**: Typed text extraction from `EasyInputMessage.Content`. **ToolParameterExtractor**: Shared tool parameter extraction from FunctionTool schema. **LocalLlmSemantics**: Declares Leap backend limitations (role dropping, random IDs, no correlation, content flattening).

---

## File Structure

```
llm/
├── LLMClient.kt              # Abstract base + stream events + result types
├── OpenAIResponseClient.kt   # OpenAI Responses API
├── CodexResponseClient.kt    # ChatGPT Codex backend (OAuth)
├── CodexRequestBuilder.kt    # Codex JSON serialization
├── CodexSseParser.kt         # Codex SSE parsing
├── ChatCompletionClient.kt   # Chat Completions API
├── ChatCompletionInterop.kt  # Type conversion
├── LFMLLMClient.kt           # Local LFM (Leap SDK)
├── InsecureSslConfig.kt      # SSL bypass (debug source set only; release stub returns null)
├── LLMClientFactory.kt       # Catalog-driven client creation
├── ModelCatalog.kt            # Model definitions
├── CloudLlmRetry.kt          # Non-streaming retry
├── CloudStreamRetryPolicy.kt # Streaming retry policy
├── CloudStreamRetryRunner.kt # Streaming retry scaffold
├── OpenAIErrorClassifier.kt  # Exception classification
├── LlmLogger.kt              # Debug logging
├── LocalLLMConfig.kt         # Local model config
├── ToolParameterExtractor.kt # Shared tool param extraction
└── LeapFunctionInterop.kt    # OpenAI ↔ Leap adapters
```

## Related Docs

- [Session](session.md) - SessionServices creates LLMClient
- [Loop](../agent/loop.md) - LLM calls during Turn execution
- [Config](../protocol/config.md) - SessionConfig with model/backend settings
- [Turn Prompt Anatomy](../agent/turn_prompt_anatomy.md) - Prompt composition

# Multi-LLM Implementation Review

> Review by Antigravity · 2026-02-09
> Scope: All 6 commits from `a0ea4925` to `05a2328` (42 files, ~2300 lines added)

## Overall Assessment

**Rating: Strong implementation with one critical runtime bug.**

The implementation faithfully follows the design doc. Code is clean, idiomatic Kotlin, and well-structured. Tests are thorough for the new foundation classes. The phased commit strategy (6 sequential commits, each compiling) is excellent.

---

## 🐛 Critical Bug: One-Turn Termination

The `debug-run.sh --main-model glm-4.7` run terminates after 1 turn with:

```
Turn 1: Stream error
UnauthorizedException - 401: No cookie auth credentials found
```

### Root Cause

`SessionServices.create()` hardcodes the `apiKeyResolver` to return the same API key for all providers:

```kotlin
// SessionServices.kt:111-118
val llmClientFactory = LLMClientFactory(
    catalog = modelCatalog,
    apiKeyResolver = { _ ->  // ← ignores the env var name entirely
        apiKey  // ← always returns OPENAI_API_KEY
    }
)
```

When `glm-4.7` (provider=OPENROUTER) is resolved, `LLMClientFactory.resolveApiKey()` correctly asks for `"OPENROUTER_API_KEY"`, but the lambda ignores that and returns the OpenAI key. OpenRouter rejects it with a 401.

**Contributing factor**: `debug-run.sh` only passes `OPENAI_API_KEY` via intent extra `api_key` (line 242). `OPENROUTER_API_KEY` is in `.env` but never transmitted.

### Fix Required (two parts)

**Part 1 — `debug-run.sh`**: Pass per-provider keys via intent:
```bash
# After existing api_key extra
if [[ -n "${OPENROUTER_API_KEY:-}" ]]; then
    SAFE_OR_KEY=$(escape_shell_arg "$OPENROUTER_API_KEY")
    INTENT_EXTRAS="$INTENT_EXTRAS --es openrouter_api_key '$SAFE_OR_KEY'"
fi
```

**Part 2 — `SessionServices.create()`**: Make the `apiKeyResolver` provider-aware. Accept a `Map<String, String>` of keys, or read from intent extras by env var name:
```kotlin
val llmClientFactory = LLMClientFactory(
    catalog = modelCatalog,
    apiKeyResolver = { envVar ->
        apiKeys[envVar]  // look up per-provider
            ?: apiKey    // fallback to legacy single key
    }
)
```

---

## 🟢 Strengths

### Architecture
- **Clean separation**: `ModelCatalog` (data), `LLMClientFactory` (construction), `ChatCompletionClient` (protocol) each have a single responsibility.
- **Option A was the right call**: Keeping `ResponseInputItem` as lingua franca contained the change surface — callers don't change.
- **`ConcurrentHashMap`** in `LLMClientFactory` — correct thread-safety for client caching.
- **`LinkedHashMap`** in `ModelCatalog` — preserves insertion order for UI display.

### Code Quality
- `JsonModelEntry` ↔ `ModelEntry` separation keeps serialization annotations out of the domain model.
- `effectiveApiKeyEnv` / `effectiveBaseUrl` computed properties in `ModelEntry` — clean defaulting logic.
- `resolveOrNull()` in `ModelCatalog` enables graceful test fallback in `AgentTurnRunner`.
- `DropdownSelectedIndicator()` extraction removes 5 duplicated composable blocks.

### Testing
- `ModelCatalogTest` (270 lines) covers happy path, edge cases, error validation, unknown fields, and computed properties. Excellent coverage.
- `LLMClientFactoryTest` (104 lines) covers caching, provider resolution, and error paths.
- Existing tests updated to use `String` model param — no tests broken.

---

## ⚠️ Issues & Suggestions

### 1. `apiKeyResolver` ignores provider (CRITICAL — see bug above)

**Severity**: 🔴 Blocks non-OpenAI models
**Location**: [SessionServices.kt:111-118](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt#L111-L118)

### 2. Legacy `llmClient` kept alongside `llmClientFactory` — dual path risk

**Severity**: 🟡 Medium
**Location**: [SessionServices.kt:61](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt#L61), [AgentTurnRunner.kt:216-222](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt#L216-L222)

`AgentTurnRunner.runPlanningPhase()` has a fallback path:
```kotlin
val llmClient = if (modelEntry != null) {
    services.llmClientFactory.create(config.modelName)
} else {
    Log.w(TAG, "Model '${config.modelName}' not in catalog; using legacy llmClient")
    services.llmClient  // ← legacy path
}
```

This is documented as a test convenience, but in production the legacy `llmClient` is always an `OpenAIResponseClient` constructed with the old `llmBackend` logic. If a model name typo occurs, the error message says "not in catalog" but silently uses a different client — confusing to debug.

**Suggestion**: In a follow-up, consider throwing instead of falling back in production, and only use the fallback in test code paths (e.g. via a `testFallbackClient` parameter).

### 3. `model` vs `mainModel` — redundant defaults

**Severity**: 🟡 Medium
**Location**: [Op.kt:131-155](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/protocol/Op.kt#L131-L155)

```kotlin
@Deprecated val model: String = "gpt-5.2",
@Suppress("DEPRECATION") val mainModel: String = model,
```

This works for backward compat, but `MainActivity` now sets **both** `model` and `mainModel` to the same value:
```kotlin
model = settingsState.selectedModel,
mainModel = settingsState.selectedModel,
```

The `model` line is unnecessary since `mainModel` defaults to `model`. It's not wrong but adds confusion about which is canonical.

### 4. `ChatCompletionClient` non-streaming path not implemented

**Severity**: 🟡 Medium (currently unused)
**Location**: [ChatCompletionClient.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt)

The `chatWithTools()` (non-streaming) method throws `UnsupportedOperationException`:
```kotlin
override suspend fun chatWithTools(...): ResponsesResult {
    throw UnsupportedOperationException("Use chatWithToolsStreaming() for ChatCompletionClient")
}
```

The existing `OpenAIResponseClient` has both paths. If any code path calls the non-streaming variant, this will crash at runtime. Fine for now since the agent exclusively uses streaming, but worth noting as a landmine.

### 5. `ChatCompletionInterop.convertInputItems` — assistant text + tool_calls race

**Severity**: 🟡 Medium
**Location**: [ChatCompletionInterop.kt](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionInterop.kt)

When an assistant message (text) is immediately followed by `FunctionCall` items, the interop creates separate messages: one `assistant` text message then another `assistant` message with `toolCalls`. Per the OpenAI Chat API spec, these should be merged into a **single** assistant message with both `content` and `tool_calls`. Some providers may reject split messages.

```kotlin
// Current: creates two separate assistant messages
item.isMessage() -> { /* assistant text message */ }
item.isFunctionCall() -> { /* separate assistant + toolCalls message */ }
```

**Suggestion**: Buffer text content and merge with the subsequent `FunctionCall` group.

### 6. Hardcoded `base_url` for OpenRouter in `llm_models.json`

**Severity**: 🟢 Low  
**Location**: [llm_models.json:24](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/assets/llm_models.json#L20-L25)

The `base_url` is specified per-model entry for OpenRouter models, but `LLMProvider.OPENROUTER.defaultBaseUrl` already provides the same value. The JSON entries could omit `base_url` and rely on the provider default. Not a bug — just redundancy.

> Actually, on recheck: the `glm-4.7` and `qwen-plus` entries do **not** have `base_url` in the JSON file. The design doc shows them but the actual `llm_models.json` asset omits them. This is correct — the `LLMProvider.OPENROUTER.defaultBaseUrl` handles it.
> 
> **No action needed.** 👍

### 7. `PromptBuilder` — `supportsVision` now derived from `ModelEntry`

**Severity**: 🟢 Low (informational)
**Location**: [AgentTurnRunner.kt:224](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt#L224)

Good refactor: `supportsVision` is now derived from `modelEntry.supportsVision` instead of `llmBackend != LOCAL`. Clean.

Fallback default of `true` when model not in catalog (line 224) is reasonable.

---

## 📋 Checklist Summary

| Area | Status | Notes |
|---|---|---|
| ModelCatalog + JSON parsing | ✅ Solid | Good validation, ignoreUnknownKeys |
| LLMClient refactor (ChatModel→String) | ✅ Clean | Minimal caller changes |
| ChatCompletionClient | ✅ Works | Non-streaming is stub; interop edge case |
| LLMClientFactory + caching | ✅ Good | Thread-safe, proper cache key |
| SessionConfig changes | ✅ OK | Backward compat preserved |
| AgentTurnRunner wiring | ✅ Correct | Factory lookup + fallback |
| UI (dropdowns, catalog) | ✅ Clean | Executor model conditional display |
| debug-run.sh | ⚠️ Incomplete | Missing OPENROUTER_API_KEY passthrough |
| API key resolution | 🔴 Bug | Single-key for all providers |
| Tests | ✅ Good | 374 new test lines, existing tests updated |
| Deprecation annotations | ✅ Proper | @Deprecated + @Suppress at usage sites |

---

## Recommended Priority

1. **Fix API key resolution** (blocks all non-OpenAI models)
2. **Fix debug-run.sh** to pass per-provider keys
3. (Optional) Merge assistant text + tool_calls in interop
4. (Future) Remove legacy `llmClient` and dual path

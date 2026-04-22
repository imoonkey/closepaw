# Multi-LLM Support Design

> Design by Antigravity · 2026-02-09
> Based on qi_note.md requirements

## Problem Statement

The current codebase has a single `LLMClient` per session, tightly coupled to:
1. OpenAI **Response API** types (`ResponseInputItem`, `FunctionTool`, `ChatModel` enum)
2. A hardcoded model list in the UI (`SettingsModels.kt`)
3. A single `LLMBackendType` enum (`OPENAI | LOCAL`) that doesn't cover OpenAI-compatible providers

**Goal**: Support multiple LLM providers (OpenAI, OpenRouter, vLLM, etc.) with per-agent-role model selection, driven by a JSON config file — without third-party abstraction libraries.

---

## Design Principles

1. **KISS** — No abstract factory patterns, no provider registries, no plugin systems. Just config → client.
2. **Two API shapes, one interface** — Response API (existing) + ChatCompletion API (new). Both produce the same `LLMStreamEvent` / `ResponsesResult`.
3. **JSON-driven model catalog** — A single `llm_models.json` acts as the source of truth for all available models.
4. **Agent role → model name** — Each agent role (standalone/planner/executor) specifies a model name string. The client is resolved at runtime from the catalog.
5. **No backward compatibility tax** — Old `LLMBackendType`, `LocalLLMConfig`, and hardcoded model lists will be deprecated and removed.

---

## Architecture Overview

```
┌────────────────────────────────────────────────────────────────┐
│                        llm_models.json                         │
│   { "gpt-5.2": { provider: "OPENAI", api: "response", ... },  │
│     "glm-4.7": { provider: "OPENROUTER", api: "chat", ... } } │
└──────────────────────────┬─────────────────────────────────────┘
                           │ parsed at app startup
                           ▼
┌──────────────────────────────────────────────┐
│           ModelCatalog                        │
│   fun resolve(modelName: String): ModelEntry  │
└──────────────────────────┬───────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────┐
│         LLMClientFactory                      │
│   fun create(modelName: String): LLMClient    │
│   (resolves entry → picks client class →      │
│    injects apiKey & baseUrl)                  │
└──────────────────────────┬───────────────────┘
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
   OpenAIResponseClient      ChatCompletionClient
   (existing, renamed)       (new, OpenAI SDK's
                              chat.completions)
```

---

## Detailed Design

### 1. Model Config File: `llm_models.json`

Placed in `assets/llm_models.json` (shipped with APK) with optional override from device storage.

```json
{
  "gpt-5.2": {
    "display_name": "GPT-5.2",
    "provider": "OPENAI",
    "api": "response",
    "model_id": "gpt-5.2"
  },
  "gpt-5.2-pro": {
    "display_name": "GPT-5.2 Pro",
    "provider": "OPENAI",
    "api": "response",
    "model_id": "gpt-5.2-pro"
  },
  "gpt-5.2-chat": {
    "display_name": "GPT-5.2 (Chat API)",
    "provider": "OPENAI",
    "api": "chat",
    "model_id": "gpt-5.2"
  },
  "glm-4.7": {
    "display_name": "GLM-4.7",
    "provider": "OPENROUTER",
    "api": "chat",
    "model_id": "zhipu-ai/glm-4.7",
    "base_url": "https://openrouter.ai/api/v1"
  },
  "qwen-plus": {
    "display_name": "Qwen Plus",
    "provider": "OPENROUTER",
    "api": "chat",
    "model_id": "qwen/qwen-plus",
    "base_url": "https://openrouter.ai/api/v1"
  }
}
```

> **Note**: `provider` and `api` are independent axes. Both OPENAI and OPENROUTER support both `response` and `chat` APIs. The `api` field controls which client class is used; the `provider` field controls which API key env var is read. Switching a model between API types is a one-line config change.

**Fields**:
| Field | Required | Description |
|---|---|---|
| `display_name` | yes | Shown in UI dropdowns |
| `provider` | yes | `OPENAI`, `OPENROUTER` — determines which env var to read for API key |
| `api` | yes | `response` or `chat` — determines which client class to use (independent of provider) |
| `model_id` | yes | The model string sent to the API (e.g. `gpt-5.2`, `zhipu-ai/glm-4.7`) |
| `base_url` | no | Custom API endpoint. Defaults per provider (omitted = OpenAI default) |
| `api_key_env` | no | Env var name for API key. Defaults: `OPENAI_API_KEY` for OPENAI, `OPENROUTER_API_KEY` for OPENROUTER |

> **Why not just a flat API key field?** Because API keys are secrets that should come from environment, not be stored in a config file that might be committed or logged. The `provider` field gives sensible defaults; `api_key_env` is for exotic setups.

### 2. Kotlin Data Model

```kotlin
// llm/ModelCatalog.kt

/**
 * Which OpenAI-compatible API shape this model uses.
 */
enum class ApiType { RESPONSE, CHAT }

/**
 * Provider determines default API key and base URL.
 */
enum class LLMProvider {
    OPENAI,      // api.openai.com, OPENAI_API_KEY
    OPENROUTER   // openrouter.ai, OPENROUTER_API_KEY
}

/**
 * One entry from llm_models.json.
 */
data class ModelEntry(
    val name: String,         // the JSON key, e.g. "gpt-5.2"
    val displayName: String,
    val provider: LLMProvider,
    val api: ApiType,
    val modelId: String,      // sent to the API as the model param
    val baseUrl: String?,     // null = use provider default
    val apiKeyEnv: String?    // null = use provider default
)

/**
 * Loads and caches model entries from llm_models.json.
 */
class ModelCatalog(private val entries: Map<String, ModelEntry>) {

    fun resolve(name: String): ModelEntry =
        entries[name] ?: throw IllegalArgumentException(
            "Unknown model '$name'. Available: ${entries.keys}"
        )

    fun all(): List<ModelEntry> = entries.values.toList()

    companion object {
        /** Parse from JSON string (read from assets or file). */
        fun fromJson(json: String): ModelCatalog { /* ... */ }
    }
}
```

This is intentionally flat. No inheritance, no generics, no builder patterns.

### 3. LLM Client Changes

#### 3a. Rethink the base class

The current `LLMClient` abstract class uses OpenAI Response API types (`ResponseInputItem`, `FunctionTool`) as its method parameters. This is a problem: `ChatCompletionClient` doesn't natively speak Response API.

**Two options**:

| Option | Approach | Pros | Cons |
|---|---|---|---|
| A | Keep `LLMClient` with Response API types; convert to Chat format inside `ChatCompletionClient` | No changes to callers (`Turn.kt`, `AgentTurnRunner.kt`) | `ChatCompletionClient` needs conversion logic |
| B | Create neutral domain types; both clients convert from those | Cleaner abstraction | Touches all callers |

**Decision: Option A.** KISS wins. The existing callers already speak Response API types. The new `ChatCompletionClient` accepts those same types and internally converts them to `ChatCompletionRequest`. This keeps the change surface minimal.

#### 3b. Remove `ChatModel` enum coupling

`Turn.kt` currently does `modelNameToChatModel()` to convert a string to `ChatModel` enum. This breaks for non-OpenAI models.

**Fix**: Change `LLMClient.chatWithTools()` and `chatWithToolsStreaming()` to accept `model: String` instead of `model: ChatModel`. The conversion to `ChatModel` (or direct string usage) happens inside each client implementation:

```kotlin
// LLMClient.kt — new signature
abstract suspend fun chatWithTools(
    systemPrompt: String,
    inputItems: List<ResponseInputItem>,
    tools: List<FunctionTool>,
    model: String                    // ← was ChatModel
): ResponsesResult

abstract fun chatWithToolsStreaming(
    systemPrompt: String,
    inputItems: List<ResponseInputItem>,
    tools: List<FunctionTool>,
    model: String                    // ← was ChatModel
): Flow<LLMStreamEvent>
```

Inside `OpenAIResponseClient` (renamed from `OpenAILLMClient`):
```kotlin
// Convert string to ChatModel enum for the Response API
private fun resolveModel(model: String): ChatModel =
    ChatModel.of(model)   // OpenAI SDK supports arbitrary strings via .of()
```

This removes the fragile `when` block in `Turn.modelNameToChatModel()` entirely.

#### 3c. New `ChatCompletionClient`

```kotlin
// llm/ChatCompletionClient.kt

/**
 * LLM client using OpenAI Chat Completions API.
 *
 * Works with any OpenAI-compatible endpoint (OpenRouter, vLLM, etc.)
 * by setting base_url and api_key.
 */
class ChatCompletionClient(
    apiKey: String,
    baseUrl: String? = null      // null = OpenAI default
) : LLMClient() {

    private val client: OpenAIClient = OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { baseUrl?.let { baseUrl(it) } }
        .build()

    override suspend fun chatWithTools(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): ResponsesResult {
        // 1. Convert ResponseInputItem → ChatCompletionMessageParam
        // 2. Convert FunctionTool → ChatCompletionTool
        // 3. Call client.chat().completions().create(params)
        // 4. Convert ChatCompletion → ResponsesResult
    }

    override fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: String
    ): Flow<LLMStreamEvent> {
        // Same conversion, but use .createStreaming()
    }
}
```

**The conversion layer** (`ResponseInputItem → ChatMessage`) is contained entirely within this class. It's ~100 lines of straightforward mapping. This is the pragmatic KISS approach: one class handles the translation, callers don't change.

#### 3d. `LLMClientFactory`

```kotlin
// llm/LLMClientFactory.kt

/**
 * Creates LLMClient instances from model names using the catalog.
 *
 * Resolves API keys from environment/system properties.
 * Caches clients per unique (provider, baseUrl, apiKey) tuple
 * so multiple models from the same provider share a connection pool.
 */
class LLMClientFactory(
    private val catalog: ModelCatalog,
    private val apiKeyResolver: (String) -> String?  // env var name → value
) {
    private val clientCache = mutableMapOf<String, LLMClient>()

    fun create(modelName: String): LLMClient {
        val entry = catalog.resolve(modelName)
        val cacheKey = "${entry.provider}|${entry.baseUrl ?: "default"}|${entry.api}"

        return clientCache.getOrPut(cacheKey) {
            val apiKey = resolveApiKey(entry)
            when (entry.api) {
                ApiType.RESPONSE -> OpenAIResponseClient(apiKey, entry.baseUrl)
                ApiType.CHAT -> ChatCompletionClient(apiKey, entry.baseUrl)
            }
        }
    }

    private fun resolveApiKey(entry: ModelEntry): String {
        val envVar = entry.apiKeyEnv ?: when (entry.provider) {
            LLMProvider.OPENAI -> "OPENAI_API_KEY"
            LLMProvider.OPENROUTER -> "OPENROUTER_API_KEY"
        }
        return apiKeyResolver(envVar)
            ?: throw IllegalStateException("API key not found for env var: $envVar")
    }
}
```

### 4. Per-Agent-Role Model Selection

The user wants each agent role (standalone, planner, executor) to potentially use a different model.

**Current flow**:
```
SessionConfig.model ("gpt-5.2") → services.config.model → AgentTurnRunner → Turn.runStreaming(modelName)
```

**New flow**: Instead of a single `model` string in `SessionConfig`, it has role-based model names:

```kotlin
// protocol/Op.kt — updated SessionConfig

data class SessionConfig(
    val maxTurns: Int = 50,
    val actionDelayMs: Long = 2000,
    val approvalMode: ApprovalMode = ApprovalMode.SMART,
    val debugMode: Boolean = false,
    val traceEnabled: Boolean = false,
    val traceRunId: String? = null,
    val perceptionConfig: PerceptionConfig = PerceptionConfig.DEFAULT,
    val agentMode: AgentMode = AgentMode.PRO,

    /**
     * Model name for standalone/planner agent.
     * Key into llm_models.json.
     */
    val mainModel: String = "gpt-5.2",

    /**
     * Model name for executor agent (only used in PRO mode).
     * Key into llm_models.json. Defaults to mainModel if null.
     */
    val executorModel: String? = null
)
// Removed: model, llmBackend, localLLMConfig
```

**How agent gets its model**: `AgentExecutionConfig` gains a `modelName: String` field (set by `SessionAgentRunner` based on role):

```kotlin
// SessionAgentRunner.start()
val modelName = when (agentDef.executionRole) {
    AgentExecutionRole.STANDALONE,
    AgentExecutionRole.PLANNER -> config.mainModel
    AgentExecutionRole.EXECUTOR -> config.executorModel ?: config.mainModel
}

val agentConfig = AgentExecutionConfig(
    // ...existing fields...
    modelName = modelName
)
```

`AgentTurnRunner` passes `config.modelName` to `Turn.runStreaming()`.

### 5. SessionServices Changes

`SessionServices` holds a `ModelCatalog` and `LLMClientFactory` instead of a single `LLMClient`:

```kotlin
data class SessionServices(
    val toolRegistry: ToolRegistry,
    val toolRouter: ToolRouter,
    val historyManager: HistoryManager,
    val sessionState: AgentSessionState,
    val policyEngine: PolicyEngine,
    val platform: AndroidPlatform,
    val config: SessionConfig,
    val modelCatalog: ModelCatalog,
    val llmClientFactory: LLMClientFactory,   // ← was: llmClient: LLMClient
    val traceRecorder: TraceRecorder
)
```

`AgentTurnRunner` resolves both the client and the API model ID at turn time. `Turn` doesn't know about the catalog:

```kotlin
// AgentTurnRunner — in runPlanningPhase
val entry = services.modelCatalog.resolve(config.modelName)
val llmClient = services.llmClientFactory.create(config.modelName)
val turn = Turn(
    toolRegistry = services.toolRegistry,
    llmClient = llmClient,
    allowedToolNames = config.allowedToolNames
)
turn.runStreaming(
    systemPrompt = systemPrompt,
    inputItems = inputItems,
    modelName = entry.modelId  // "gpt-5.2", "zhipu-ai/glm-4.7", etc.
)
```

The factory caches clients by `(provider, baseUrl, api)`, so ephemeral `Turn` creation per turn is efficient — the underlying HTTP client is reused.

### 6. `debug-run.sh` Changes

The script currently passes `--es llm_backend 'openai'` and `--es api_key '...'`.

**New**: Pass the model names instead:
```bash
# New intent extras
--es main_model 'gpt-5.2'
--es executor_model 'glm-4.7'   # optional
```

The API keys are resolved from environment on the device side (or passed via intent for debug convenience).

For backward compatibility during development, the script could auto-map:
```bash
if [[ -n "$MAIN_MODEL" ]]; then
    INTENT_EXTRAS="$INTENT_EXTRAS --es main_model '$MAIN_MODEL'"
fi
if [[ -n "$EXECUTOR_MODEL" ]]; then
    INTENT_EXTRAS="$INTENT_EXTRAS --es executor_model '$EXECUTOR_MODEL'"
fi
```

### 7. UI Changes

`SettingsModels.kt` currently has hardcoded `AVAILABLE_CLOUD_MODELS` and `AVAILABLE_LOCAL_MODELS`. 

**New**: The UI reads the model catalog and displays all available models:

```kotlin
// In settings ViewModel or composable
val models = modelCatalog.all()
// Group by provider for display if desired

// Two dropdowns:
// 1. "Main Model" — used for standalone and planner
// 2. "Executor Model" — used for executor (PRO mode only)
```

The dropdown items come from `ModelEntry.displayName`, and the selected value is `ModelEntry.name` (the JSON key).

---

## Files Changed Summary

### New files
| File | Purpose |
|---|---|
| `assets/llm_models.json` | Model catalog config |
| `llm/ModelCatalog.kt` | Parse and resolve model entries |
| `llm/ChatCompletionClient.kt` | Chat Completions API client |
| `llm/LLMClientFactory.kt` | Create LLMClient from model name |
| `llm/ChatCompletionInterop.kt` | ResponseInputItem ↔ ChatMessage conversion |

### Modified files
| File | Change |
|---|---|
| `llm/LLMClient.kt` | `model` param: `ChatModel` → `String` |
| `llm/OpenAILLMClient.kt` | Rename to `OpenAIResponseClient.kt`, accept `String` model, use `ChatModel.of()` |
| `agent/Turn.kt` | Remove `modelNameToChatModel()`, pass string through |
| `protocol/Op.kt` | `SessionConfig`: replace `model`/`llmBackend`/`localLLMConfig` with `mainModel`/`executorModel` |
| `agent/AgentExecutionConfig.kt` | Add `modelName: String` |
| `session/SessionServices.kt` | Hold `ModelCatalog` + `LLMClientFactory` instead of single `LLMClient` |
| `session/SessionAgentRunner.kt` | Resolve model name per role |
| `agent/AgentTurnRunner.kt` | Resolve client from factory per turn |
| `ui/settings/SettingsModels.kt` | Dynamic model list from catalog |
| `ui/settings/SettingsDropdowns.kt` | Two model dropdowns (main + executor) |
| `scripts/debug-run.sh` | New `--main-model` / `--executor-model` flags |

### Deprecated / Removed
| File | Reason |
|---|---|
| `protocol/LLMBackendType` | Replaced by `ModelEntry.api` + `ModelEntry.provider` |
| `ui/settings/AVAILABLE_CLOUD_MODELS` | Replaced by catalog |
| `ui/settings/AVAILABLE_LOCAL_MODELS` | Replaced by catalog |

---

## Implementation Status

| Phase | Description | Status | Commit |
|-------|-------------|--------|--------|
| 1 | ModelCatalog + ModelEntry + llm_models.json | DONE | `feat: add ModelCatalog foundation` |
| 2 | LLMClient interface refactor (ChatModel → String), rename OpenAILLMClient | DONE | `refactor: modernize LLMClient interface` |
| 3 | ChatCompletionClient + ChatCompletionInterop + LLMClientFactory | DONE | `feat: add ChatCompletionClient, Interop layer, and LLMClientFactory` |
| 4 | Config & Wiring (SessionConfig, AgentExecutionConfig, SessionServices, runners, PromptBuilder) | DONE | `feat: wire multi-LLM config into agent infrastructure` |
| 5 | UI & Scripts (Settings dropdowns, MainActivity, debug-run.sh) | DONE | `feat: catalog-driven model dropdowns and executor model selection` |
| 6 | Cleanup (deprecate LLMBackendType, LocalLLMConfig; marker annotations) | DONE | `refactor: deprecate legacy LLMBackendType and LocalLLMConfig fields` |

### Phase 4 Implementation Notes

**Per-agent-role model selection**: `SessionConfig.mainModel` is used for standalone/planner agents; `SessionConfig.executorModel` (when set) is used for executor agents. `SessionAgentRunner` and `SubAgentRunner` route the model name into `AgentExecutionConfig.modelName`.

**Backward compatibility strategy**: `SessionConfig.model` is retained for UI compatibility. `mainModel` defaults to `model`, so existing code that only sets `model` continues to work. The old `LLMBackendType` enum and `llmClient` field in `SessionServices` are still present — they will be removed in Phase 6.

**Test fallback mechanism**: `AgentTurnRunner` uses `services.modelCatalog.resolveOrNull(config.modelName)`. When the model is not in the catalog (as in unit tests with dummy catalogs), it falls back to `services.llmClient`, allowing tests to inject mock LLM clients without configuring a full catalog.

**PromptBuilder refactor**: Replaced `llmBackend: LLMBackendType` parameter with `supportsVision: Boolean`, decoupling image attachment logic from backend type. Vision support is now derived from `ModelEntry.supportsVision`.

### Phase 5 Implementation Notes

**Catalog-driven dropdowns**: `AVAILABLE_CLOUD_MODELS` removed. `catalogModelOptions()` converts `ModelCatalog.all()` to `List<Pair<String, String>>` for dropdown display. `MainActivity` lazy-loads the catalog from `assets/llm_models.json` with a single-model fallback.

**Executor model UX**: `ExecutorModelDropdown` shows "(Same as Main Model)" as the first/default option (null value). The section is only visible when `isCloudBackend && agentMode == AgentMode.PRO`.

**Settings persistence**: `AppSettingsStore` stores `executorModel` as nullable string. `null` means "same as main", saved by removing the key from SharedPreferences.

**Intent extras**: `main_model` and `executor_model` can be passed via intent (e.g., from `debug-run.sh`). They override the persisted settings for that session.

**debug-run.sh**: Added `--main-model <name>` and `--executor-model <name>` flags, plus `MAIN_MODEL`/`EXECUTOR_MODEL` env var support.

### Phase 6 Implementation Notes

**Deprecation strategy**: `SessionConfig.model`, `SessionConfig.llmBackend`, and `SessionConfig.localLLMConfig` are annotated `@Deprecated`. The local LLM path (`LFMLLMClient`) still depends on these, so full removal is deferred until local models migrate to the `ModelCatalog` system (a future task).

**Suppression**: `@Suppress("DEPRECATION")` added at legitimate usage sites — `SessionServices.create()`, `MainActivity.ensureSessionAndSend()`, `AgentTrace`, and test files that construct `SessionConfig`.

### Remaining Work

1. **Local LLM migration**: Move `AVAILABLE_LOCAL_MODELS` into `llm_models.json` with a new provider type (e.g., `LOCAL`). Update `LLMClientFactory` to instantiate `LFMLLMClient` when `provider == LOCAL`.
2. **Per-provider API keys**: The `LLMClientFactory.apiKeyResolver` currently returns the same API key for all providers. Add per-provider key storage in `AppSettingsStore`.
3. **Context window config**: Different models have different context windows. `HistoryManager.maxTokenBudget` (currently 18K) should eventually come from `ModelEntry`.
4. **Remove deprecated fields**: Once local models are in the catalog, remove `LLMBackendType`, `LocalLLMConfig`, `SessionConfig.model`, `SessionConfig.llmBackend`, `SessionConfig.localLLMConfig`, the `BackendSelector` UI, and all `@Suppress("DEPRECATION")` annotations.

---

## Sequencing / Implementation Order

This is a rough order that keeps the app compiling at each step:

1. **Create `ModelCatalog` + `ModelEntry` data classes** — No existing code touches these yet
2. **Create `assets/llm_models.json`** — Include current models (gpt-5.2, gpt-5.2-pro) and new (glm-4.7)
3. **Change `LLMClient` base: `ChatModel` → `String`** — Update `OpenAILLMClient`
4. **Rename `OpenAILLMClient` → `OpenAIResponseClient`**
5. **Create `ChatCompletionClient`** with conversion layer
6. **Create `LLMClientFactory`**
7. **Update `SessionConfig`** — `mainModel` / `executorModel`
8. **Update `AgentExecutionConfig`** — add `modelName`
9. **Update `SessionServices`** — factory instead of single client
10. **Update `SessionAgentRunner`** — role-based model resolution
11. **Update `AgentTurnRunner`** — factory.create per turn
12. **Update `Turn.kt`** — remove `modelNameToChatModel()`
13. **Update UI** — dynamic model dropdowns from catalog
14. **Update `debug-run.sh`** — new flags
15. **Remove deprecated code** — `LLMBackendType`, `LocalLLMConfig`, hardcoded model lists

---

## Key Design Decisions & Rationale

### Why keep ResponseInputItem as the lingua franca?

`Turn.kt` and `PromptBuilder` already produce `ResponseInputItem` lists. Introducing a new neutral type would require rewriting both. The ChatCompletion client can convert from ResponseInputItem internally — it's a contained change.

### Why a factory with caching, not one-client-per-agent?

Multiple agents (planner + executor) might use the same provider. Creating a new OkHttp client per agent wastes connection pools. The factory caches by `(provider, baseUrl, api)` so agents sharing a provider share a client.

### Why JSON file and not SharedPreferences or Room?

The model catalog is essentially a fixed schema. JSON is human-readable, version-controllable, and easy to override. SharedPreferences is for user preferences. Room is for relational data. Neither fits.

### Why not a sealed class hierarchy for clients?

Two client types (Response, Chat) with one factory function is simpler than a sealed hierarchy with visitors/pattern matching. KISS.

### What about streaming vs non-streaming?

Both paths use the same `model: String` parameter. No change to the streaming architecture.

---

## Open Questions

1. **API key delivery**: On Android, environment variables aren't natively available. Currently the API key comes from the `.env` file via intent extras in `debug-run.sh`, or from the UI settings. Should the catalog's `api_key_env` map to system properties (`System.getProperty`)? Or should we pass a `Map<String, String>` of API keys from the UI/intent?
   - **Recommendation**: The `LLMClientFactory.apiKeyResolver` lambda handles this flexibly. The caller (SessionServices.create) can provide a resolver that checks intent extras, then SharedPreferences, then BuildConfig.

2. **Hot-swap models mid-session?** Not needed for v1. Model is fixed per agent creation.

3. **Token counting / context window differences?** Different models have different context windows. The `HistoryManager.maxTokenBudget` is currently hardcoded at 18K. This should eventually come from the model config. Deferred to a follow-up.

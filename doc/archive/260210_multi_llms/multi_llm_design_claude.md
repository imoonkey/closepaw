# Multi-LLM Support Design

> **Goal**: Enable the Android Agent to seamlessly work with multiple LLM providers (OpenAI, Gemini, Anthropic, local models, OpenAI-compatible endpoints) without tightly coupling to any single SDK. Allow different agent roles (planner, executor) to use different models.

---

## Part 1: Reference Analysis — How Coding Agents Support Multi-LLM

### 1. OpenHands (Python)

**Approach**: Uses **LiteLLM** as unified proxy layer.

The `LLM` class wraps `litellm.completion()` which internally handles 100+ providers (OpenAI, Anthropic, Gemini, Bedrock, Vertex, Ollama, etc.) through a unified API. Configuration uses `LLMConfig` with fields like `model`, `api_key`, `base_url`, `custom_llm_provider`.

- Model is specified as a string like `"gpt-4o"`, `"claude-3-5-sonnet"`, `"gemini/gemini-2.5-pro"`.
- LiteLLM auto-routes based on model name prefix.
- Handles provider-specific quirks internally (e.g., Azure `max_tokens` vs `max_completion_tokens`, Gemini safety settings, Anthropic caching).
- Supports custom OpenAI-compatible endpoints via `base_url`.
- Has `for_routing` flag in config to support multi-LLM setups where a secondary model is used alongside the main one.

**Key Insight**: Single library dependency (`litellm`) abstracts all provider differences. Agent code never touches provider-specific SDKs. This is the **litellm pattern** — a unified proxy library.

---

### 2. Cline (TypeScript)

**Approach**: **Manual per-provider implementations** behind a shared `ApiHandler` interface.

Cline defines an `ApiHandler` interface with 4 methods:
```typescript
interface ApiHandler {
    createMessage(systemPrompt, messages, tools?, useResponseApi?): ApiStream
    getModel(): ApiHandlerModel
    getApiStreamUsage?(): Promise<ApiStreamUsageChunk | undefined>
    abort?(): void
}
```

Then implements **40+ separate provider handlers**: `AnthropicHandler`, `OpenAiHandler`, `GeminiHandler`, `DeepSeekHandler`, `OllamaHandler`, `LiteLlmHandler`, `DoubaoHandler`, etc. A giant `switch` statement in `createHandlerForProvider()` maps provider name → handler class.

- Uses a `Mode` concept (`"plan"` vs `"act"`) where each mode can use a different provider and model.
- Each provider has its own model registry (e.g., `anthropicModels`, `openaiModels`) with pricing/capability info hardcoded.
- Very explicit about provider-specific options (API keys, base URLs, region settings per provider).

**Key Insight**: Maximum control, but massive maintenance burden (40+ files, 4000+ lines). Each new provider requires a new handler file + model registry + config plumbing. The plan/act mode separation is worth noting — it's essentially multi-LLM by role.

---

### 3. Gemini CLI (TypeScript)

**Approach**: **Gemini-only** — no multi-LLM support.

Only supports Gemini models (`gemini-2.5-pro`, `gemini-2.5-flash`, `gemini-3-pro-preview`, etc.). Has model aliasing (`auto`, `pro`, `flash`, `flash-lite`) and a classifier-based model router that automatically picks between Pro and Flash based on task complexity.

**Key Insight**: Interesting model routing within a single provider, but not relevant to multi-provider support.

---

### 4. Codex (TypeScript/Rust)

**Approach**: **OpenAI-only** — no multi-LLM support.

Tightly coupled to OpenAI Responses API. Has a `modelRouterService` and `modelConfigService` for selecting between different OpenAI models, but no multi-provider abstraction.

**Key Insight**: Even within a single provider, they have configuration infrastructure for model selection. Good pattern for model routing, but not multi-provider.

---

### 5. DroidRun (Python, Mobile Agent)

**Approach**: Uses **LlamaIndex's LLM abstraction** with dynamic provider loading.

`llm_picker.py` dynamically loads provider classes:
```python
module_path = f"llama_index.llms.{provider_name.lower()}"
llm_module = importlib.import_module(module_path)
llm_class = getattr(llm_module, provider_name)
llm_instance = llm_class(**kwargs)
```

`llm_loader.py` manages **role-based LLM profiles** (manager, executor, codeact, app_opener, etc.) loaded from YAML config:
```python
profiles = ["manager", "executor", "text_manipulator", "app_opener"]
```

- Provider classes are from LlamaIndex plugins: `llama-index-llms-openai`, `llama-index-llms-anthropic`, etc.
- Each role gets its own LLM instance, potentially different providers/models.
- Config-driven: YAML profiles map role → (provider, model, temperature, ...).

**Key Insight**: Library-based abstraction (LlamaIndex) + role-based profile system. Clean separation between "which LLM" (config) and "how to use" (agent code). Very similar conceptually to what we need.

---

### Summary Table

| Repo | Language | Multi-LLM Approach | Library Used | Multi-Role Support |
|------|----------|-------------------|--------------|-------------------|
| OpenHands | Python | Unified proxy library | **LiteLLM** | Config flag (`for_routing`) |
| Cline | TypeScript | Manual per-provider handlers | None (custom) | Plan/Act mode split |
| Gemini CLI | TypeScript | Gemini-only | None | Model routing (Pro/Flash) |
| Codex | TS/Rust | OpenAI-only | None | Model selection within OpenAI |
| DroidRun | Python | Library abstraction + profiles | **LlamaIndex** | Named role profiles |

---

## Part 2: Java/Kotlin Multi-LLM Libraries

### LangChain4j ⭐ Recommended

> [!IMPORTANT]
> LangChain4j is the **Java/Kotlin equivalent of LiteLLM** — the most mature, widely-used unified LLM API for the JVM ecosystem.

- **Unified `ChatLanguageModel` interface**: `chat(messages, tools) → Response<AiMessage>`
- **Providers**: OpenAI, Gemini, Anthropic Claude, Ollama, Azure, Bedrock, Mistral, HuggingFace, local models, and more. Each is a separate Maven module (e.g., `langchain4j-open-ai`, `langchain4j-google-ai-gemini`).
- **Tool/Function calling**: Built-in via `@Tool` annotation or `ToolSpecification` objects.
- **Kotlin support**: Official `langchain4j-kotlin` extension with coroutine `suspend` functions and Flow-based streaming.
- **Android compatible**: Standard JVM library, works in Android projects.
- **Streaming**: `StreamingChatLanguageModel` with token-by-token callbacks; Kotlin extension provides `Flow<String>`.
- **Vision/Multimodal**: Supports image inputs via `ImageContent` in messages.

**Migration implication**: Would replace our custom `LLMClient` abstraction entirely. We'd depend on LangChain4j's types for messages, tools, and responses — but these are well-designed, provider-agnostic types, unlike our current OpenAI SDK coupling.

### tddworks/openai-kotlin

- Kotlin Multiplatform SDK with "Unified Gateway" for OpenAI, Anthropic, Gemini, Ollama.
- Flow-based streaming, type-safe APIs.
- Less mature than LangChain4j, smaller community.
- Good if we want to stay Kotlin-native and don't need the full LangChain4j feature set.

### Spring AI

- Spring Boot integration for multi-LLM.
- Not suitable for Android (Spring is server-side).

### Direct OpenAI-compatible approach

- Many providers (Gemini via OpenAI compatibility, Anthropic via proxies, Ollama, vLLM) expose OpenAI-compatible APIs.
- Could keep our current `OpenAILLMClient` and just point `baseUrl` to different providers.
- **Limitation**: Not all models support the Responses API (only OpenAI does). Most OpenAI-compatible endpoints only support the Chat Completions API. We currently use the Responses API.

---

## Part 3: Design for Android Agent

### Current State Analysis

```
Current Architecture:
┌──────────────┐
│  Turn.kt     │──→ LLMClient (abstract)
└──────────────┘         │
                         ├── OpenAILLMClient  (OpenAI Responses API)
                         └── LFMLLMClient     (Local Leap SDK)
```

**Problems**:
1. `LLMClient` interface uses OpenAI SDK types (`ChatModel`, `ResponseInputItem`, `FunctionTool`) — tight coupling.
2. Only supports OpenAI and local models. No Gemini, Anthropic, etc.
3. Single `llmClient` per session — no role-based model routing.
4. The `OpenAILLMClient` uses Responses API, which is OpenAI-exclusive. Other "OpenAI-compatible" endpoints only support Chat Completions API.

### Addressing qi_note Questions

> **Q1**: LLMClient该是哪个层次的config？是不是可以作为agent runtime的一部分？

**Answer**: Yes. LLMClient配置应该是agent runtime config的一部分. 类似DroidRun的profile系统, 每个agent role指定它的model config. SessionServices创建对应的client实例.

> **Q2**: OpenAI-compatible serving platforms — Chat Completions API vs Responses API?

**Answer**: 绝大多数OpenAI-compatible平台(如vLLM, Ollama, LiteLLM proxy, Groq)只支持Chat Completions API, 不支持Responses API. 这意味着我们不能简单地把当前的`OpenAILLMClient`指向不同的base URL来支持其他provider. 我们需要一个真正的抽象层, 或者使用LangChain4j这样的库来处理差异.

### Recommended Approach: LangChain4j

> [!TIP]
> 使用LangChain4j替换自定义的LLMClient抽象，是最KISS的方案。它是经过battle-tested的Java/Kotlin生态系统中的标准方案，和Python世界的LiteLLM地位等同。

**Why LangChain4j over rolling our own**:
1. We avoid writing and maintaining per-provider implementations (Cline's 40+ files nightmare).
2. Tool calling, streaming, vision all built-in with consistent API.
3. Provider-specific quirks handled by library (like LiteLLM does in Python).
4. Kotlin coroutine extensions available.
5. Each provider is an opt-in Maven dependency — only include what you need.

### Proposed Architecture

```
New Architecture:
┌──────────────┐
│  Turn.kt     │──→ LLMService (our thin wrapper)
└──────────────┘         │
                         │  uses
                         ▼
                    LangChain4j
                    ChatLanguageModel
                         │
                ┌────────┼────────┬──────────┐
                ▼        ▼        ▼          ▼
            OpenAI    Gemini   Anthropic   Ollama
            (module)  (module)  (module)   (module)
```

### Core Design

#### 1. LLMService (our wrapper)

A thin adapter between our agent code and LangChain4j. Converts between our domain types and LangChain4j types.

```kotlin
/**
 * Thin wrapper around LangChain4j ChatLanguageModel.
 * Converts between our domain types and LangChain4j types.
 * 
 * This is the ONLY place where LangChain4j types appear.
 * Agent code (Turn.kt, etc.) uses our own domain types exclusively.
 */
class LLMService(
    private val chatModel: ChatLanguageModel,
    private val streamingModel: StreamingChatLanguageModel? = null
) {
    suspend fun chat(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentToolDef> = emptyList()
    ): AgentLLMResponse {
        // Convert our types → LangChain4j types
        // Call chatModel.chat(...)
        // Convert response back → our types
    }
    
    fun chatStreaming(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentToolDef> = emptyList()
    ): Flow<AgentStreamEvent> {
        // Convert, call streamingModel, emit events
    }
}
```

#### 2. Configuration (role-based)

```kotlin
/**
 * Configuration for a single LLM provider + model.
 */
data class LLMModelConfig(
    val provider: String,       // "openai", "gemini", "anthropic", "ollama"
    val model: String,          // "gpt-4o", "gemini-2.5-pro", "claude-sonnet-4"
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val temperature: Double = 0.0,
    val maxTokens: Int = 4096
)

/**
 * Multi-LLM config: maps agent roles → model configs.
 * If a role-specific config is not found, falls back to "default".
 */
data class MultiLLMConfig(
    val default: LLMModelConfig,
    val roles: Map<String, LLMModelConfig> = emptyMap()
)
```

#### 3. LLMServiceFactory

```kotlin
object LLMServiceFactory {
    fun create(config: LLMModelConfig): LLMService {
        val chatModel = when (config.provider) {
            "openai" -> OpenAiChatModel.builder()
                .apiKey(config.apiKey)
                .modelName(config.model)
                .temperature(config.temperature)
                .build()
            "gemini" -> GoogleAiGeminiChatModel.builder()
                .apiKey(config.apiKey)
                .modelName(config.model)
                .temperature(config.temperature)
                .build()
            "anthropic" -> AnthropicChatModel.builder()
                .apiKey(config.apiKey)
                .modelName(config.model)
                .temperature(config.temperature)
                .build()
            "ollama" -> OllamaChatModel.builder()
                .baseUrl(config.baseUrl ?: "http://localhost:11434")
                .modelName(config.model)
                .temperature(config.temperature)
                .build()
            else -> error("Unknown provider: ${config.provider}")
        }
        return LLMService(chatModel)
    }
}
```

#### 4. LLMRegistry (role-based lookup)

```kotlin
class LLMRegistry(
    private val services: Map<String, LLMService>
) {
    fun get(role: String): LLMService =
        services[role] ?: services["default"]
            ?: error("No LLM service for role '$role' and no default")
    
    val default: LLMService get() = get("default")
    
    companion object {
        fun fromConfig(config: MultiLLMConfig): LLMRegistry {
            val services = mutableMapOf<String, LLMService>()
            services["default"] = LLMServiceFactory.create(config.default)
            config.roles.forEach { (role, modelConfig) ->
                services[role] = LLMServiceFactory.create(modelConfig)
            }
            return LLMRegistry(services)
        }
    }
}
```

#### 5. Domain Types (provider-agnostic)

```kotlin
// Our own message types — not tied to any LLM SDK
sealed class AgentMessage {
    data class System(val content: String) : AgentMessage()
    data class User(val parts: List<ContentPart>) : AgentMessage()
    data class Assistant(val content: String?, val toolCalls: List<AgentToolCall> = emptyList()) : AgentMessage()
    data class ToolResult(val callId: String, val result: String) : AgentMessage()
}

sealed class ContentPart {
    data class Text(val text: String) : ContentPart()
    data class Image(val base64: String, val mimeType: String) : ContentPart()
}

data class AgentLLMResponse(
    val content: String?,
    val toolCalls: List<AgentToolCall> = emptyList(),
    val usage: TokenUsage? = null
)

data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: String  // JSON string
)

data class AgentToolDef(
    val name: String,
    val description: String,
    val parameters: String  // JSON Schema
)
```

### Integration with SessionServices

```kotlin
data class SessionServices(
    val llmRegistry: LLMRegistry,   // was: llmClient: LLMClient
    // ... other services unchanged
) {
    // Convenience accessor for single-model usage
    val llmService: LLMService get() = llmRegistry.default
}
```

### Migration Path

| Step | Change | Effort |
|------|--------|--------|
| 1 | Add LangChain4j dependencies (core + openai + gemini modules) | Small |
| 2 | Define our domain types (`AgentMessage`, `AgentLLMResponse`, etc.) | Small |
| 3 | Implement `LLMService` wrapper | Medium |
| 4 | Implement `LLMServiceFactory` and `LLMRegistry` | Small |
| 5 | Update `Turn.kt` to use `LLMService` instead of `LLMClient` | Medium |
| 6 | Update `SessionServices` to hold `LLMRegistry` | Small |
| 7 | Remove old `LLMClient`, `OpenAILLMClient`, `LFMLLMClient` | Small |
| 8 | Add Gemini/Anthropic provider modules and test | Small |

### What About LFMLLMClient (Local Models)?

The local Leap SDK model (`LFMLLMClient`) is a special case — it's not an HTTP API but an on-device inference engine. Options:
1. **Keep it as a separate path** alongside LangChain4j (since it has its own model loading/downloading lifecycle).
2. **Wrap it as a custom LangChain4j ChatLanguageModel** implementation — cleanest architecturally.
3. **Use Ollama** as the local model backend (Ollama runs locally and exposes OpenAI-compatible API) — simplest but adds a dependency.

Recommendation: Option 2 for clean architecture. The Leap SDK implementation would implement LangChain4j's `ChatLanguageModel` interface, keeping all local-model quirks isolated.

### Gradle Dependencies

```kotlin
// build.gradle.kts
dependencies {
    // LangChain4j core
    implementation("dev.langchain4j:langchain4j:1.0.0-beta2")
    
    // Provider modules (add as needed)
    implementation("dev.langchain4j:langchain4j-open-ai:1.0.0-beta2")
    implementation("dev.langchain4j:langchain4j-google-ai-gemini:1.0.0-beta2")
    implementation("dev.langchain4j:langchain4j-anthropic:1.0.0-beta2")
    implementation("dev.langchain4j:langchain4j-ollama:1.0.0-beta2")
    
    // Kotlin coroutine extensions (optional)
    implementation("dev.langchain4j:langchain4j-kotlin:1.0.0-beta2")
}
```

> [!CAUTION]
> Need to verify LangChain4j Android compatibility before committing to this path. While it's a standard JVM library, some modules may have server-side dependencies (e.g., gRPC for Vertex) that don't work on Android. The core `langchain4j` and HTTP-based providers (OpenAI, Anthropic) should be fine.

---

## Design Principles

1. **KISS**: Use LangChain4j instead of rolling our own 40-file provider system.
2. **High Readability**: Thin wrapper (`LLMService`) keeps our code clean, hides the library.
3. **No Backward Compatibility**: Replace `LLMClient` entirely, don't deprecate.
4. **Configuration > Code**: Role → model mapping lives in config, not in conditionals.
5. **Provider-agnostic domain types**: Agent code never imports provider SDK classes.

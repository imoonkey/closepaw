# Multi-LLM Architecture Design

> [!NOTE]
> This design decouples the Agent logic from specific LLM implementations, allowing seamless switching between providers (OpenAI, Gemini, Anthropic, Local) and specialized models (Grounding vs. Planning).

## 1. Core Abstractions

### 1.1 `LLMClient` Interface
The fundamental contract for any LLM interaction. It should be stateless regarding conversation history (which belongs to the Agent/Session) but stateful regarding configuration.

```kotlin
interface LLMClient {
    val config: LLMConfig

    /**
     * Generates a response based on the provided messages.
     * @param messages List of messages (System, User, Assistant) including text and images.
     * @param tools Optional list of tools/functions available to the model.
     * @return LLMResponse containing text content and/or tool calls.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        jsonMode: Boolean = false
    ): LLMResponse
    
    /**
     * Stream not prioritized for now, but interface should support it eventually.
     */
}

data class LLMConfig(
    val provider: LLMProviderType, // OPENAI, GEMINI, ANTHROPIC, LOCAL
    val modelName: String,
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val temperature: Double = 0.0,
    val maxTokens: Int = 1000
)

data class LLMResponse(
    val content: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    val usage: TokenUsage? = null
)
```

### 1.2 Specialized Capabilities (Optional)
Instead of forcing all features into `LLMClient`, we can use extension interfaces or specific subtypes if necessary, but for KISS, a single flexible `chat` method with structured `ChatMessage` (supporting text/image/video parts) is usually sufficient.

For **Grounding**, we might want a `GroundingClient` wrapper *around* an `LLMClient` that handles:
- Image rescaling/normalization.
- Coordinate parsing (0-1000 vs 0-1).
- Bounding box formatting.

## 2. LLM Registry & Factory

To manage multiple models (e.g., "Planner" uses GPT-4o, "Executor" uses a local highly-specialized model), we need a centralized registry.

```kotlin
object LLMFactory {
    fun createClient(config: LLMConfig): LLMClient {
        return when (config.provider) {
            LLMProviderType.OPENAI -> OpenAILLMClient(config)
            LLMProviderType.GEMINI -> GeminiLLMClient(config)
            LLMProviderType.ANTHROPIC -> AnthropicLLMClient(config)
            LLMProviderType.LOCAL -> LocalLLMClient(config) // OpenAl-compatible typically
        }
    }
}

// Service Locator or DI integration
class AgentSystem(private val config: AgentSystemConfig) {
    val plannerLLM: LLMClient by lazy { LLMFactory.createClient(config.plannerConfig) }
    val executorLLM: LLMClient by lazy { LLMFactory.createClient(config.executorConfig) }
}
```

## 3. Configuration Strategy

Configuration should be injectable and distinct for different agent roles.

### `config.json` / Environment
```json
{
  "planner": {
    "provider": "OPENAI",
    "model": "gpt-4o",
    "temperature": 0.1
  },
  "executor": {
    "provider": "LOCAL",
    "model": "ui-tars-7b",
    "base_url": "http://localhost:8000/v1",
    "temperature": 0.0
  }
}
```

## 4. Grounding & Multimodal Handling

Handling images is the critical part for Android Agents. 
The `ChatMessage` structure must support:
- **Screenshots**: High-res for Executor, maybe downscaled/compressed for Planner.
- **Set-of-Marks (SoM)**: If the model requires overlay.
- **A11y Tree**: Passed as text context.

The **Executor Agent** will likely use a specific `LLMClient` configured for a vision-heavy model. The *Agent* itself (not the LLM client) executes the logic to fetch the screenshot, process it, and attach it to the message.

## 5. Migration Strategy

1.  **Define `LLMClient` interface** in `com.moonkey.androidagent.llm.core`.
2.  **Implement `OpenAILLMClient`** implementing this interface (refactor existing `OpenAILLMClient`).
3.  **Implement `GeminiLLMClient`** (if needed) and others.
4.  **Update `Agent`** to accept `LLMClient` in constructor instead of hardcoded implementations.

This separation ensures that when we swap the Planner from GPT-4 to Claude 3.5 Sonnet, we only change the config, not the Agent code.

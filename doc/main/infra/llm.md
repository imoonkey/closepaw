# LLM Integration

> LLM clients, backends, and API configuration.
> Last updated: 2026-02-09 (commit: 917ebf7)

## Overview

The agent supports multiple LLM backends through a unified interface, with native streaming support.

---

## LLMClient Interface

→ See: `llm/LLMClient.kt`

```kotlin
interface LLMClient {
    fun chatWithToolsStreaming(
        systemPrompt: String,
        inputItems: List<ResponseInputItem>,
        tools: List<FunctionTool>,
        model: ChatModel = ChatModel.GPT_5_2
    ): Flow<LLMStreamEvent>
}
```

### Stream Event Types

| Event | Description |
|-------|-------------|
| `Created` | Response initiated (includes response ID) |
| `TextDelta` | Text chunk |
| `ToolCallDone` | Tool call completed |
| `Completed` | Stream finished successfully |
| `Failed` | Error occurred |

---

## Backends

### OpenAI (Cloud)

→ See: `llm/OpenAILLMClient.kt`

Uses OpenAI Responses API with native tool calling.

**Models:**
- `gpt-5.2` (default)
- `gpt-5.2-pro`

### OpenRouter & Novita AI (Compatible)

Support for third-party providers via OpenAI Chat Completion API shape:
- **OpenRouter**: Aggregates many models (Claude, Gemini, Llama, etc.)
- **Novita AI**: Specialized models like `autoglm-phone-9b`

Configuration is driven by `llm_models.json`.

### Local LFM

→ See: `llm/LFMLLMClient.kt`

On-device inference using Leap SDK:
- Function calling support via `LeapFunctionInterop`
- Model download and management
- No network required after model download

**Models:**
- LFM 1.2B Instruct
- LFM 350M

---

## Configuration

### Backend Selection

```kotlin
data class SessionConfig(
    val llmBackend: LLMBackendType = LLMBackendType.OPENAI,
    val model: String = "gpt-5.2",
    val localLLMConfig: LocalLLMConfig? = null,
)

enum class LLMBackendType {
    OPENAI,
    LOCAL
}
```

### API Key

For OpenAI backend, API key is provided at session creation:

```kotlin
val session = AgentSession.create(config, platform, scope, apiKey)
```

---

## LeapFunctionInterop

→ See: `llm/LeapFunctionInterop.kt`

Adapters for tool schema and argument conversion between OpenAI format and Leap SDK format.

---

## File Structure

```
llm/
├── LLMClient.kt              # Unified interface + stream events
├── OpenAILLMClient.kt        # OpenAI Responses API client
├── LFMLLMClient.kt           # Local LFM client (Leap SDK)
└── LeapFunctionInterop.kt    # Tool schema + argument adapters
```

---

## Related Docs

- [Session](session.md) - SessionServices creates LLMClient
- [Loop](../agent/loop.md) - LLM calls during Turn execution
- [Protocol](../protocol/protocol.md) - SessionConfig details
- [Settings](../app/settings.md) - LLM backend settings

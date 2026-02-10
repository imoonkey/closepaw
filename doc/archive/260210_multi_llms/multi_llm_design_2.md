# Multi-LLM Infrastructure Design

## Overview

This document defines the infrastructure for supporting multiple LLM providers/models simultaneously within a single agent session. This enables role-specific model selection (e.g., GPT-4 for planning, UI-Ins for grounding).

## Problem Statement

Current system uses a single `LLMClient` instance per session. To support Planner-Executor architecture with specialized models, we need:
1. Multiple LLM clients active simultaneously
2. Role-based model routing (planner vs executor)
3. Clean separation between client management and model selection

## Design

### Core Concept: LLM Registry

A simple registry pattern that holds named LLM clients. Agents request clients by role, not by model name.

```kotlin
/**
 * Registry of LLM clients keyed by role/purpose.
 * 
 * Roles are semantic identifiers like "planner", "executor", "grounding".
 * The mapping of role -> model is configuration, not hardcoded.
 */
class LLMRegistry(
    private val clients: Map<String, LLMClient>
) {
    fun get(role: String): LLMClient =
        clients[role] ?: clients["default"]
            ?: error("No LLM client for role '$role' and no default")
    
    fun getOrDefault(role: String): LLMClient =
        clients[role] ?: clients.values.first()
}
```

### Configuration

Model selection is configuration, not code. Defined in `LLMConfig`:

```kotlin
/**
 * Configuration for multi-LLM setup.
 * 
 * @param default The fallback client used when role isn't specified
 * @param roleClients Optional role-specific clients
 */
data class LLMConfig(
    val default: LLMClientConfig,
    val roleClients: Map<String, LLMClientConfig> = emptyMap()
)

data class LLMClientConfig(
    val provider: LLMProvider,      // OPENAI, LOCAL, CUSTOM
    val model: String,              // e.g., "gpt-4o", "ui-ins-7b"
    val baseUrl: String? = null,    // For custom endpoints
    val apiKey: String? = null      // Optional override
)

enum class LLMProvider {
    OPENAI,     // Uses OpenAILLMClient
    LOCAL,      // Uses LFMLLMClient (Leap SDK)
    CUSTOM      // Uses OpenAI-compatible endpoint
}
```

### Factory Pattern

Simple factory creates clients from config:

```kotlin
object LLMClientFactory {
    fun create(config: LLMClientConfig): LLMClient = when (config.provider) {
        LLMProvider.OPENAI -> OpenAILLMClient(
            apiKey = config.apiKey ?: System.getenv("OPENAI_API_KEY"),
            model = config.model
        )
        LLMProvider.LOCAL -> LFMLLMClient(
            modelPath = config.model
        )
        LLMProvider.CUSTOM -> OpenAILLMClient(
            baseUrl = config.baseUrl ?: error("CUSTOM provider requires baseUrl"),
            apiKey = config.apiKey ?: "empty",
            model = config.model
        )
    }
    
    fun createRegistry(config: LLMConfig): LLMRegistry {
        val clients = mutableMapOf<String, LLMClient>()
        clients["default"] = create(config.default)
        config.roleClients.forEach { (role, clientConfig) ->
            clients[role] = create(clientConfig)
        }
        return LLMRegistry(clients)
    }
}
```

### Integration with SessionServices

`SessionServices` holds the registry instead of a single client:

```kotlin
// Before
data class SessionServices(
    val llmClient: LLMClient,
    // ...
)

// After
data class SessionServices(
    val llmRegistry: LLMRegistry,
    // ...
) {
    // Convenience for single-LLM usage (backward compat during migration)
    val llmClient: LLMClient get() = llmRegistry.getOrDefault("default")
}
```

### Usage in Agent Turn

The turn runner selects client based on agent role:

```kotlin
class AgentTurnRunner(...) {
    private fun getLLMClient(): LLMClient {
        val role = config.agentRole.toLLMRole()
        return services.llmRegistry.get(role)
    }
}

// Simple mapping
fun AgentExecutionRole.toLLMRole(): String = when (this) {
    AgentExecutionRole.PLANNER -> "planner"
    AgentExecutionRole.EXECUTOR -> "executor"
    AgentExecutionRole.STANDALONE -> "default"
}
```

## Example Configurations

### Development (Single Model)
```kotlin
LLMConfig(
    default = LLMClientConfig(
        provider = LLMProvider.OPENAI,
        model = "gpt-4o"
    )
)
```

### Production (Planner + Grounding Model)
```kotlin
LLMConfig(
    default = LLMClientConfig(
        provider = LLMProvider.OPENAI,
        model = "gpt-4o"
    ),
    roleClients = mapOf(
        "planner" to LLMClientConfig(
            provider = LLMProvider.OPENAI,
            model = "gpt-4o"
        ),
        "executor" to LLMClientConfig(
            provider = LLMProvider.CUSTOM,
            model = "ui-ins-7b",
            baseUrl = "http://localhost:8080/v1"
        )
    )
)
```

## File Changes

| File | Change |
|------|--------|
| [NEW] `llm/LLMRegistry.kt` | Registry class |
| [NEW] `llm/LLMConfig.kt` | Configuration data classes |
| [NEW] `llm/LLMClientFactory.kt` | Factory for creating clients |
| [MODIFY] `session/SessionServices.kt` | Add `llmRegistry`, deprecate direct `llmClient` |
| [MODIFY] `agent/AgentTurnRunner.kt` | Use registry lookup based on role |

## Design Principles

1. **KISS**: No dependency injection framework, just simple factory pattern
2. **Configuration over Code**: Model selection is data, not conditionals
3. **Backward Compatible**: Single-client usage still works via `llmClient` accessor
4. **Testable**: Registry can be mocked with test clients

## Not In Scope

- Hot-swapping models at runtime
- Load balancing between multiple instances
- Model-specific prompt formatting (handled by agent definitions)
- Token budget management per model

---

*This is a dependency for the Planner-Executor design. See `design_claude.md` for the agent architecture.*

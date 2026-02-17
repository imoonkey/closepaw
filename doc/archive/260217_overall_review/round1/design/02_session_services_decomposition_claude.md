# Design: SessionServices Decomposition

**Priority**: P1 — Architectural
**Files affected**: `session/SessionServices.kt`, all consumers (Agent, AgentTurnRunner, AgentSession, etc.)

---

## Problem

`SessionServices` (343 lines) is a god object holding 12 fields:

```kotlin
data class SessionServices(
    val toolRegistry: ToolRegistry,
    val toolRouter: ToolRouter,
    val historyManager: HistoryManager,
    val sessionState: AgentSessionState,
    val policyEngine: PolicyEngine,
    val platform: AndroidPlatform,
    val config: SessionConfig,
    val llmClient: LLMClient,
    val modelCatalog: ModelCatalog,
    val llmClientFactory: LLMClientFactory,
    val traceRecorder: TraceRecorder,
    val recordingService: SessionRecordingService,
    val userResponseChannel: UserResponseChannel
)
```

This creates several problems:
1. **Testing friction** — any test that needs `platform` also gets `llmClient`, `toolRouter`, etc.
2. **Implicit coupling** — components that only need LLM access receive the full service container
3. **Long factory** — `create()` is 80 lines of sequential initialization with verbose logging
4. **Unclear ownership** — who owns cleanup? `SessionServices.cleanup()` knows about every service's teardown

## Solution

Group fields into 3 domain-specific containers, keep `SessionServices` as a thin facade.

### New structure

```kotlin
data class LLMServices(
    val llmClient: LLMClient,
    val modelCatalog: ModelCatalog,
    val llmClientFactory: LLMClientFactory
)

data class ToolServices(
    val toolRegistry: ToolRegistry,
    val toolRouter: ToolRouter,
    val policyEngine: PolicyEngine
)

data class SessionServices(
    val config: SessionConfig,
    val platform: AndroidPlatform,
    val historyManager: HistoryManager,
    val sessionState: AgentSessionState,
    val llm: LLMServices,
    val tools: ToolServices,
    val traceRecorder: TraceRecorder,
    val recordingService: SessionRecordingService,
    val userResponseChannel: UserResponseChannel
) {
    // Convenience accessors for backward compatibility during migration:
    val llmClient get() = llm.llmClient
    val modelCatalog get() = llm.modelCatalog
    val llmClientFactory get() = llm.llmClientFactory
    val toolRegistry get() = tools.toolRegistry
    val toolRouter get() = tools.toolRouter
    val policyEngine get() = tools.policyEngine
}
```

### Factory refactoring

Split `create()` into focused builders:

```kotlin
companion object {
    fun create(...): SessionServices {
        val llm = createLLMServices(config, apiKeys, context)
        val tools = createToolServices(config, sessionState)
        return SessionServices(
            config = config,
            platform = platform,
            llm = llm,
            tools = tools,
            ...
        )
    }

    private fun createLLMServices(...): LLMServices { ... }
    private fun createToolServices(...): ToolServices { ... }
}
```

### Cleanup refactoring

Each sub-container gets its own `cleanup()`:

```kotlin
data class LLMServices(...) {
    suspend fun cleanup() {
        llmClient.cleanup()
        llmClientFactory.cleanupAll()
    }
}
```

## Steps

1. Create `LLMServices` data class in `session/LLMServices.kt`
2. Create `ToolServices` data class in `session/ToolServices.kt`
3. Refactor `SessionServices` to compose these sub-containers
4. Add convenience accessors (`val llmClient get() = llm.llmClient`) for backward compatibility
5. Split `create()` into `createLLMServices()` and `createToolServices()`
6. Migrate callers incrementally: update `services.llmClient` → `services.llm.llmClient` when touching files
7. Remove convenience accessors once migration is complete
8. Delete `SessionServicesBuilder` — it only wraps `create()` with register/unregister

## Risks

- **Low**: The convenience accessors ensure this is a non-breaking change. Callers can migrate incrementally.
- **Low**: Data classes compose well — no behavioral change.

# Design: SessionConfig Restructuring

**Priority**: P2 — Cleanup
**Files affected**: `protocol/Op.kt`, all `SessionConfig` consumers

---

## Problem

`SessionConfig` (lines 110-174 in `Op.kt`) has 17 top-level fields with 5 deprecated:

```kotlin
data class SessionConfig(
    val maxTurns: Int = 50,
    val actionDelayMs: Long = 2000,
    val approvalMode: ApprovalMode = ApprovalMode.SMART,
    @Deprecated val model: String = "gpt-5.2",        // deprecated
    @Deprecated val llmBackend: LLMBackendType = ...,  // deprecated but still used
    val agentMode: AgentMode = AgentMode.PRO,
    @Deprecated val localLLMConfig: LocalLLMConfig?,   // deprecated
    val debugMode: Boolean = false,
    val traceEnabled: Boolean = false,
    val traceRunId: String? = null,
    val perceptionConfig: PerceptionConfig = ...,
    val mainModel: String = model,
    val executorModel: String? = null,
    val platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
)
```

Issues:
1. **Flat namespace** — 17 fields with no grouping makes it hard to see what's related
2. **Deprecated fields still in use** — `llmBackend` is deprecated but is the only way to select backend; `model` is deprecated but `mainModel` defaults to it
3. **Mixed concerns** — LLM config, trace config, perception config, platform config all in one flat class
4. **`Op.kt` is overloaded** — contains `Op` + `SessionConfig` + 5 enums

## Solution

### Phase 1: Move to own file + group into sub-configs

```kotlin
// protocol/SessionConfig.kt (new file)
data class SessionConfig(
    val maxTurns: Int = 50,
    val actionDelayMs: Long = 2000,
    val approvalMode: ApprovalMode = ApprovalMode.SMART,
    val agentMode: AgentMode = AgentMode.PRO,
    val debugMode: Boolean = false,
    val llm: LLMConfig = LLMConfig(),
    val trace: TraceConfig = TraceConfig(),
    val perception: PerceptionConfig = PerceptionConfig.DEFAULT,
    val platformMode: PlatformMode = PlatformMode.ACCESSIBILITY
)

data class LLMConfig(
    val backend: LLMBackendType = LLMBackendType.OPENAI,
    val mainModel: String = "gpt-5.2",
    val executorModel: String? = null,
    val localConfig: LocalLLMConfig? = null
)

data class TraceConfig(
    val enabled: Boolean = false,
    val runId: String? = null
)
```

### Phase 2: Remove deprecated fields

After migrating all callers from `config.model` to `config.llm.mainModel`, etc.:

1. Remove `model` field (callers use `llm.mainModel`)
2. Remove `llmBackend` field (callers use `llm.backend`)
3. Remove `localLLMConfig` field (callers use `llm.localConfig`)
4. Remove `mainModel` top-level field (callers use `llm.mainModel`)

### Phase 3: Clean up Op.kt

Move enums to their own files:
- `protocol/PlatformMode.kt`
- `protocol/AgentMode.kt`
- `protocol/LLMBackendType.kt`
- `protocol/ApprovalMode.kt`

`Op.kt` shrinks to just the `Op` sealed interface (~100 lines → ~50 lines).

## Steps

1. Create `protocol/SessionConfig.kt` with `LLMConfig` and `TraceConfig`
2. Add backward-compat accessors: `val mainModel get() = llm.mainModel` etc.
3. Move `SessionConfig` out of `Op.kt`
4. Move enums to individual files
5. Migrate callers incrementally (each file touched updates to new accessors)
6. Remove backward-compat accessors and deprecated fields when migration is complete

## Risks

- **Low**: Backward-compat accessors make this incremental
- **Medium**: UI settings screen constructs `SessionConfig` directly — needs coordinated update

# Phase 4 Code Review: Multi-LLM Config & Wiring

**Scope**: Config and wiring changes for per-agent-role model selection.

**Review Date**: 2025-02-09

---

## Summary

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High | 1 |
| Medium | 2 |
| Low | 2 |

**Recommendation**: **CHANGES_REQUESTED** — Address the High and consider the Medium items before merge.

---

## 1. protocol/Op.kt

[CRITICAL] — None

[HIGH] — None

[MEDIUM] **mainModel default reference to `model`**

- **Line**: 143
- **Problem**: `val mainModel: String = model` creates a subtle coupling: callers who pass only `mainModel` (e.g. `SessionConfig(mainModel = "glm-4.7")`) will get `model = "gpt-5.2"` (default) while `mainModel = "glm-4.7"`. The legacy `model` field then diverges from `mainModel`. UI that reads `model` (e.g. settings display) may show a different value than what the agent actually uses.
- **Fix**: Document this explicitly in KDoc, or ensure UI reads `mainModel` when available. Consider deprecating `model` in favor of `mainModel` in Phase 5.

[LOW] **executorModel KDoc could mention planner/executor mapping**

- **Line**: 145–147
- **Problem**: KDoc says "falls back to [mainModel]" but doesn't mention that executor agents use this; planner/standalone use mainModel.
- **Fix**: Add one line: "Used by executor agents in PRO mode; planner and standalone agents use mainModel."

---

## 2. agent/AgentExecutionConfig.kt

[CRITICAL] — None

[HIGH] — None

[MEDIUM] **Hardcoded default model name**

- **Line**: 76
- **Problem**: `val modelName: String = "gpt-5.2"` hardcodes a model. If SessionAgentRunner doesn't set it (e.g. custom Agent creation path), this default may not exist in the catalog. AgentTurnRunner falls back to legacy llmClient, which is correct, but the default is somewhat arbitrary.
- **Fix**: Consider `modelName: String = "gpt-5.2"` with a comment that it must match a catalog entry or legacy path; or derive from SessionConfig when appropriate.

[LOW] — None

---

## 3. agent/cognition/prompt/PromptBuilder.kt

[CRITICAL] — None

[HIGH] — None

[MEDIUM] — None

[LOW] **Improved API clarity**

- **Line**: 28–31
- **Problem**: Changing from `llmBackend: LLMBackendType` to `supportsVision: Boolean` is a good abstraction—vision support is the actual concern for prompt building. The rename is clear.
- **Fix**: None needed.

---

## 4. session/SessionServices.kt

[CRITICAL] — None

[HIGH] **loadModelCatalog null context: I/O on caller thread**

- **Line**: 184–200
- **Problem**: `loadModelCatalog(context)` is called during `SessionServices.create()`. When `context != null`, it does `context.assets.open("llm_models.json").bufferedReader().use { it.readText() }` — blocking I/O. `SessionServices.create()` is invoked from `AgentSession.create()` which is typically called from a coroutine (e.g. `lifecycleScope.launch`), but the caller may use `Dispatchers.Main`. The asset read is typically fast (~few KB), but blocking the main thread is not ideal.
- **Fix**: Wrap in `withContext(Dispatchers.IO)` if `create` is ever called from main, or document that callers must invoke from a background dispatcher. Alternatively, make `loadModelCatalog` a suspend function and use `context.applicationContext.contentResolver`/async loading if available. For typical asset size, this is low risk but worth noting.

[MEDIUM] **Broad exception catch in loadModelCatalog**

- **Line**: 192–196
- **Problem**: `catch (e: Exception)` catches all exceptions. IO failures, parsing errors, and programming errors are all handled the same way—fallback catalog. A serialization bug could be silently masked.
- **Fix**: Consider `catch (e: IOException)` or `catch (e: Exception)` with rethrow for `SerializationException` / `IllegalArgumentException` to surface real bugs.

[LOW] **FALLBACK_CATALOG_JSON duplication**

- **Line**: 202–211
- **Problem**: Fallback JSON is similar to `llm_models.json` structure. If the catalog schema changes, the fallback might get out of sync.
- **Fix**: Add a comment or unit test that the fallback matches the expected schema for the default model.

---

## 5. agent/AgentTurnRunner.kt

[CRITICAL] — None

[HIGH] — None

[MEDIUM] **Fallback logic correctness**

- **Line**: 214–227
- **Problem**: When `modelEntry == null`, the code uses `services.llmClient` (legacy) and `modelId = config.modelName`. The legacy client (OpenAIResponseClient, LFMLLMClient) receives whatever `config.modelName` is. For tests, this is often `"gpt-5.2"` (default) or a custom value. For production with `SessionConfig(model = "gpt-5.2")`, `mainModel` defaults to `model`, so `config.modelName` = `"gpt-5.2"` which is correct. If the UI passes a catalog key that doesn't exist in the deployed `llm_models.json`, fallback still works. Logic is sound.
- **Fix**: None. Consider adding a log when `modelEntry == null` in production to detect config drift (already present at line 222).

[LOW] — None

---

## 6. session/SessionAgentRunner.kt

[CRITICAL] — None

[HIGH] — None

[MEDIUM] — None

[LOW] **Clear role-to-model mapping**

- **Line**: 45–50
- **Problem**: `modelName = when (agentDef.executionRole) { ... }` is clear and correct. STANDALONE and PLANNER use mainModel; EXECUTOR uses executorModel ?: mainModel.
- **Fix**: None.

---

## 7. agent/subagent/SubAgentRunner.kt

[CRITICAL] — None

[HIGH] — None

[MEDIUM] — None

[LOW] **Consistent executor model resolution**

- **Line**: 136–138
- **Problem**: `childModelName = parentServices.config.executorModel ?: parentServices.config.mainModel` matches SessionAgentRunner. Sub-agents (executors) correctly use executor model when configured.
- **Fix**: None.

---

## 8. llm/ModelCatalog.kt

[CRITICAL] — None

[HIGH] — None

[MEDIUM] **resolveOrNull lacks unit test**

- **Line**: 95–96
- **Problem**: `resolveOrNull(name: String): ModelEntry?` is new and used by AgentTurnRunner for fallback. ModelCatalogTest covers `resolve()` but not `resolveOrNull()`.
- **Fix**: Add test: `resolveOrNull returns null for unknown model` and `resolveOrNull returns entry for known model`.

[LOW] — None

---

## 9. PromptBuilderTest.kt

[CRITICAL] — None

[HIGH] — None

[MEDIUM] — None

[LOW] **Tests updated for new API**

- **Line**: 235, 283, 300, 331, 345–351
- **Problem**: All tests updated to use `supportsVision` instead of `llmBackend`. New test `buildObservationText excludes screenshot hint when vision not supported` covers the vision toggle. Good coverage.
- **Fix**: None.

---

## Checklist Summary

### Backward compatibility

- **Old fields preserved**: `model`, `llmBackend`, `localLLMConfig` remain in SessionConfig with KDoc noting backward compat.
- **mainModel default**: `mainModel = model` ensures existing `SessionConfig(model = "x")` callers get mainModel = "x" automatically.
- **UI**: MainActivity and AgentService pass `model`; they do not yet pass `mainModel` or `executorModel`. Defaults apply. Correct.

### Thread safety

- **ModelCatalog**: Entries map is effectively immutable after construction. Read-only access is safe. KDoc states "Thread-safe after construction."
- **LLMClientFactory**: Uses `ConcurrentHashMap` for client cache. `create()` and `cleanupAll()` documented as thread-safe.
- **loadModelCatalog**: Runs during session creation. No shared mutable state. Asset read is synchronous; see HIGH note above.

### Fallback logic

- **AgentTurnRunner**: When `resolveOrNull(config.modelName) == null`, uses `services.llmClient` and `modelId = config.modelName`. Correct. Log warns when fallback is used.
- **SessionAgentRunner / SubAgentRunner**: Model resolution is consistent: executorModel ?: mainModel for executors.

### Test coverage

- **PromptBuilderTest**: Updated for `supportsVision`; new test for vision-off case.
- **AgentErrorRecoveryTest, AgentTraceObservabilityTest, SubAgentRunnerTest, AgentSessionTest**: All use `modelCatalog` and `llmClientFactory` in test SessionServices. Tests use catalog with `_test-only`; agent default `modelName = "gpt-5.2"` is not in catalog, so fallback path is exercised.
- **ModelCatalog.resolveOrNull**: No unit test. Add one.

### Kotlin idioms

- Immutable data classes, `val` over `var`, `copy()` for modifications.
- Null safety: `modelEntry?.supportsVision ?: true`, `config.executorModel ?: config.mainModel`.
- No `!!` observed.
- Sealed classes and enums used appropriately.

---

## Recommendations

1. **Must fix**: Address the HIGH issue (loadModelCatalog I/O). Either document that callers must not block main thread, or move asset loading to a background dispatcher.
2. **Should fix**: Add `resolveOrNull` unit test in ModelCatalogTest.
3. **Consider**: Narrow the exception catch in `loadModelCatalog`; add KDoc for executorModel usage in SessionConfig.

---

## Approval

**Recommendation**: **CHANGES_REQUESTED**

1 High severity item (loadModelCatalog I/O on main thread) and 2 Medium items (mainModel/model coupling, resolveOrNull test). The implementation is correct and backward compatible; the HIGH item is a minor threading concern for typical usage.

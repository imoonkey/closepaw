# Phase 5 Code Review: Multi-LLM UI & Scripts

**Scope**: Replaced hardcoded model lists with catalog-driven dropdowns, executor model selection, intent handling, and debug script updates.

---

## SettingsModels.kt

**Status**: ✓ Clean

- `catalogModelOptions(entries)` correctly maps `ModelEntry` to `List<Pair<String, String>>` for dropdown display.
- `AVAILABLE_LOCAL_MODELS` retained; hardcoded cloud list removed in favor of catalog.
- No dead code or unused imports.

---

## SettingsDropdowns.kt

**Status**: ✓ Clean

- `CloudModelDropdown` accepts `modelOptions: List<Pair<String, String>>` and falls back to raw model id when not in list: `?.second ?: selectedModel`. Correct.
- `ExecutorModelDropdown` handles `selectedModel: String?` with "(Same as Main Model)" when null. Leading icon selection logic is correct for both null and non-null cases.
- `ExposedDropdownMenu` usage is consistent with other dropdowns (no explicit import needed if provided by Material3 `ExposedDropdownMenuBox` scope).

---

## SettingsSheet.kt

**Status**: ✓ Clean

- Executor model section visibility: `AnimatedVisibility(visible = isCloudBackend && agentMode == AgentMode.PRO)`. Correct: only shown in PRO + cloud mode.
- Ordering: Cloud Model → Executor Model (when visible) → Local Model. Logical.
- All new params (`modelOptions`, `selectedExecutorModel`, `onExecutorModelChange`) wired correctly.

---

## AppSettingsStore.kt

**Status**: ✓ Clean

- `executorModel: String?` added to `AppSettings`; nullable correctly.
- Load: `prefs.getString(KEY_EXECUTOR_MODEL, null)` — returns null when absent. Correct.
- Save: `saveExecutorModel(value)` uses `remove(KEY_EXECUTOR_MODEL)` when null, `putString` when non-null. Correct for nullable handling.

---

## AppSettingsState.kt

**Status**: ✓ Clean

- `executorModel by mutableStateOf<String?>(null)` with private setter.
- `load()` assigns `executorModel = settings.executorModel`.
- `updateExecutorModel(value: String?)` delegates to `store.saveExecutorModel(value)`.

---

## MainActivityIntentPayload.kt

**Status**: ✓ Clean

- `mainModel` and `executorModel` parsed with `?.takeIf { it.isNotBlank() }` — blank strings become null. Correct.
- Keys: `EXTRA_MAIN_MODEL`, `EXTRA_EXECUTOR_MODEL` (defined in MainActivity).

---

## MainActivity.kt

**Status**: ✓ Clean with minor notes

### Model catalog loading (checklist)

- **Lazy**: `modelCatalog` is `by lazy { ... }` — loaded on first access.
- **Fallback on error**: `catch (e: Exception)` uses minimal JSON: `{"gpt-5.2":{"display_name":"GPT-5.2",...}}`. Correct.
- **First access**: When Settings sheet opens, `catalogModelOptions(modelCatalog.all())` runs. Catalog loads then; no blocking during `onCreate`.

### Intent handling

- `handleIntent` applies `mainModel` → `updateModel`, `executorModel` → `updateExecutorModel` before session creation.
- `SessionConfig` receives `mainModel = settingsState.selectedModel`, `executorModel = settingsState.executorModel`. Correct.

### Session creation

- `model`, `mainModel`, and `executorModel` passed consistently into `SessionConfig`.

[LOW] **Log message omits executorModel**

- **Line**: 61–64 (`AppSettingsState.load()`)
- **Problem**: Log shows `model`, `localModel`, `agentMode` but not `executorModel`.
- **Fix**: Add `executorModel=$executorModel` to the log for debugging.

---

## debug-run.sh

**Status**: ✓ Consistent with existing patterns

- `--main-model` and `--executor-model` follow the same pattern as `--perception`:
  - Check `$# -lt 2` for missing value.
  - `FORCED_MAIN_MODEL`, `FORCED_EXECUTOR_MODEL` override env vars.
  - Intent extras: `--es main_model '$SAFE_MAIN_MODEL'`, `--es executor_model '$SAFE_EXECUTOR_MODEL'`.
- `escape_shell_arg` used for both.
- Usage comment updated (line 13).
- Env vars `MAIN_MODEL`, `EXECUTOR_MODEL` documented (lines 19–20).

[MEDIUM] **Executor model without PRO mode**

- **Problem**: `--executor-model X` can be used with `--basic`. Executor model is stored but never used in Basic mode.
- **Fix**: Add a warning when `--executor-model` is passed and `AGENT_MODE` is basic: `warn "Executor model is only used in PRO mode. Use --pro to enable planner+executor."`

---

## Dead Code / Unused Imports

- **SettingsModels.kt**: No unused imports; `AVAILABLE_CLOUD_MODELS` removed.
- **SettingsDropdowns.kt**: No unused imports; `Icons.Outlined.Speed` used by `AgentModeDropdown`.
- **SettingsSheet.kt**: No dead code.
- **MainActivity.kt**: All imports used.

---

## Summary

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High     | 0 |
| Medium   | 1 |
| Low      | 1 |

**Recommendation**: **APPROVE**

No Critical or High issues. The two findings are minor improvements. The implementation is correct, catalog loading is safe (lazy + fallback), executor dropdown UX is correct (PRO + cloud only), intent parsing and persistence are correct, and the debug script follows existing patterns.

---

## Checklist Verification

| Item | Result |
|------|--------|
| Model catalog loaded safely (lazy, fallback on error)? | ✓ Yes |
| Executor model dropdown only in PRO + cloud mode? | ✓ Yes |
| Intent extras parsed and applied correctly? | ✓ Yes |
| SharedPreferences handling for nullable executorModel correct? | ✓ Yes |
| debug-run.sh changes consistent with existing patterns? | ✓ Yes |
| Dead code or unused imports? | ✓ None found |

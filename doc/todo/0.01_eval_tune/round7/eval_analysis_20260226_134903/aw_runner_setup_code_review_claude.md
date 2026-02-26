# Code Review: Eval Runner Refactor Commit

**Reviewer**: Claude
**Date**: 2026-02-26
**Commit**: `refactor: align eval runner setup with AndroidWorld baseline flow`
**Scope**: 14 files, +1906 / -869 lines

## Summary

The commit:
1. Splits `runner.py` (1270 lines) into three modules: `runner.py` (411, entrypoint), `runner_preflight.py` (764, preflight), `runner_execution.py` (256, task loop).
2. Adds typed `PreflightError`/`PreflightErrorCode` replacing message-substring matching for retry decisions.
3. Adds `SnapshotPolicy` enum (`strict`/`auto_repair`/`best_effort`/`off`) wired through config and CLI.
4. Hardens accessibility service enablement with bounded retry + dumpsys-based bound-service verification.
5. Adds `prepare_baseline.py` + `scripts/prepare_baseline.sh` for clean baseline provisioning.
6. Fixes test fixture drift and adds two new test files.

## Critical

None.

## High

### 1. `runner_preflight.py` at 764 lines violates the 400-line guideline

`runner_preflight.py:1-764` — The project guideline says "Max 400 lines/file, extract when larger." This file is nearly double that limit. It bundles connectivity, emulator lifecycle, ADB utilities, snapshot verification, package filtering, bridge installation, and APK fallback logic. The adb utility functions alone (`run_adb`, `run_adb_global`, `run_adb_shell`, `is_local_tcp_port_open`, `is_expected_emulator_online`) are generic infrastructure that could live in a small `adb_utils.py` module, bringing `runner_preflight.py` under the limit.

**Status**: noted, not addressed in this pass — requires a follow-up extraction refactor.

### 2. Backward-compatibility aliases in `runner.py` are dead weight

`runner.py:396-406` — The aliases:
```python
_create_env = create_env
_run_preflight_checks = run_preflight_checks
_run_one_task_instance = run_one_task_instance
...
```
These bridge old underscore-prefixed names to new public names, but `main()` in `runner.py` already uses the underscore-prefixed aliases. The tests import from `runner.py` using these underscore names but patch `runner_preflight.*` module paths. The aliases create confusion about the canonical import path and silently break if someone patches the alias instead of the real module path.

**Fix**: Remove aliases. Update `main()` to call the real names directly. Update test imports to pull from the correct source modules.

### 3. `_TASK_REQUIRED_PACKAGES` exported with underscore prefix

`runner.py:21` imports `_TASK_REQUIRED_PACKAGES` from `runner_preflight`. Exporting an underscore-prefixed name across module boundaries breaks Python naming convention — underscore means "private to this module." Since it's part of the public interface of `runner_preflight`, drop the underscore.

**Fix**: Rename to `TASK_REQUIRED_PACKAGES` in `runner_preflight.py` and all consumers.

### 4. `resolve_task_bridge_config` uses `Any` return type instead of `BridgeConfig`

`runner_execution.py:153-162` — The function accepts `base: Any` and returns `Any`. This was `BridgeConfig` in the original code (using `replace(base, **fields)`). The type erasure hides a real constraint — `replace()` only works on dataclass instances.

**Fix**: Type as `BridgeConfig` for both parameter and return.

## Medium

### 5. `_enable_accessibility_service_settings` always writes even when no change needed

`native_agent_bridge.py:280-288` — The method appends the service to the list if missing, then unconditionally calls `_put_secure_setting` for both `enabled_accessibility_services` and `accessibility_enabled`. When the service is already listed and enabled, this still issues two `adb shell settings put` commands per poll cycle.

**Fix**: Early-return when service is already present and `accessibility_enabled` is already `"1"`.

### 6. `prepare_baseline.py` calls `ensure_app_snapshots` twice

`prepare_baseline.py:131-142` — First with `setup_policy` (to repair), then with `SnapshotPolicy.STRICT` (to verify). The strict verification call will attempt `restore_snapshot` for every app again — meaning every snapshot is restored twice. The second call is purely for verification, but its side effect (restoring all snapshots) may leave apps in post-restore state rather than a clean baseline state.

**Status**: noted, intentional design for setup+verify. Consider a verify-only function in a follow-up.

### 7. `_is_service_in_bound_accessibility_services` indent heuristic is brittle

`native_agent_bridge.py:370-388` — The bound-service parser uses indentation level to determine section boundaries. The tests cover two cases but don't cover the `mBoundServices` header variant that the code checks for.

**Fix**: Add test case for `mBoundServices` variant.

### 8. `BridgeConfig` missing `shizuku_apk_path` and `excluded_tools` in test fixtures

`test_native_agent_bridge.py:11-32`, `test_runner.py:22-43`, `test_runner_preflight_policy.py:16-37` — Test `_bridge_config()` helpers don't include `shizuku_apk_path` or `excluded_tools`. Works only because those fields have defaults. If defaults are removed, all three test files break.

**Fix**: Add missing fields to all test fixtures.

### 9. Shell script missing `--` before `EXTRA_ARGS`

`scripts/prepare_baseline.sh:95` — Extra args passed as `"${EXTRA_ARGS[@]}"` without a preceding `--` separator. If any extra arg starts with `-` and matches a shell case pattern, the shell loop will consume it before passing to Python.

**Fix**: Add `--` before `EXTRA_ARGS` in Python invocation.

### 10. No test for `prepare_baseline.py`

The new `prepare_baseline.py` (174 lines) has no test coverage. Key behaviors worth testing: `_resolve_app_names`, manifest output shape, and the double-ensure flow.

**Status**: noted for follow-up.

## Low

### 11. Inconsistent function visibility

Functions in `runner_preflight.py` use a mix of public and private naming. `collect_required_app_names`, `fallback_install_apk_candidates`, `select_avd_name`, and `resolve_emulator_binary` are all public despite being internal implementation details only called within the module.

**Status**: noted, cosmetic.

### 12. `SnapshotCheckReport` uses `frozen=True` but stores mutable fields

`runner_preflight.py:38-55` — The dataclass is frozen, but `app_names`, `repaired_apps`, `missing_before`, and `unresolved` are all mutable `list`/`dict` types. Prevents reassignment but not mutation.

**Status**: noted, fine in practice.

## Recommendation

**APPROVE with changes** — findings #2, #3, #4, #5, #7, #8, #9 fixed inline (see companion commit).

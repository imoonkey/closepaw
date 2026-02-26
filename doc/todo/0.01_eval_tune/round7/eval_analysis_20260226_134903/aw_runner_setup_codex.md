# AndroidWorld-Consistent Runner Setup Design (Codex, Revised)

## 0. Implementation Status (as of 2026-02-26)

- Document status: design + implementation record.
- Implemented now:
  - Accessibility permission stability hardening in
    `eval/aw_bridge/native_agent_bridge.py`:
    - re-enable a11y settings before each task start,
    - verify readiness via secure settings + `dumpsys accessibility` bound-service check,
    - bounded retry (2 attempts),
    - fail fast with explicit error if still not ready (avoid silent hangs).
  - Unit tests added in `eval/tests/test_native_agent_bridge.py` for:
    - bound-service parser behavior,
    - retry-then-success path,
    - retry-budget-exhausted failure path.
  - Runner refactor landed:
    - `eval/aw_bridge/runner.py` reduced to thin entrypoint + config/API-key handling,
    - `eval/aw_bridge/runner_preflight.py` for connectivity/setup/snapshot/package preflight,
    - `eval/aw_bridge/runner_execution.py` for task execution/scoring/artifact writing.
  - Baseline preparation flow landed:
    - `scripts/prepare_baseline.sh` (wipe-data cold boot orchestration),
    - `eval/aw_bridge/prepare_baseline.py` (setup + snapshot verify + manifest output).
  - Snapshot policy rollout landed:
    - supports `strict`, `auto_repair`, `best_effort`, `off`,
    - config default set to `auto_repair` in benchmark configs.
  - Typed retry trigger landed:
    - replaced brittle message matching with `PreflightErrorCode`-based retry logic.
  - Tests updated/added:
    - `eval/tests/test_runner.py` fixture drift fixed and patches aligned to new module boundaries,
    - `eval/tests/test_runner_preflight_policy.py` added for snapshot policy + typed retry behavior.
- Remaining manual validation:
  - full on-device `aw_subset_smoke` and `aw_subset_group_1` reruns against a freshly prepared baseline.

## 1. Scope and Priorities

## 1.1 Primary objective (P0)
- Fix eval correctness first: eliminate cross-task app-state leakage caused by missing snapshots.

## 1.2 Secondary objective (P1)
- Refactor `eval/aw_bridge/runner.py` into clearer modules without changing benchmark semantics.

## 1.3 Constraints
- Preserve Android Agent specific setup:
  - latest debug APK install before run,
  - accessibility service enablement/binding,
  - existing bridge lifecycle and trace/logcat pipeline.
- Keep AndroidWorld task lifecycle semantics (`initialize_task`, `is_successful`, `tear_down`).

## 2. Current State (Evidence-Based)

## 2.1 What is aligned today
- Runner already executes AndroidWorld lifecycle per task.
- Runner uses AndroidWorld env launcher and task loading path.
- Date freezing path matches AndroidWorld usage pattern.

## 2.2 Correctness gap
- In actual run logs, snapshots are missing for benchmark apps.
- When snapshot restore is skipped, app UI prefs/state can leak across tasks (for example calendar view mode).
- This is a correctness problem, not just an engineering hygiene issue.

## 2.3 Maintainability gap
- `runner.py` is too large and mixes orthogonal concerns (connectivity, setup, execution, scoring, persistence).
- Test fixture drift exists (`RunnerConfig` changes not reflected in tests), reducing refactor safety.

## 3. AndroidWorld Snapshot Semantics

- Snapshot mechanism is provided by AndroidWorld code.
- Snapshot artifacts are generated and stored locally on emulator/device:
  - `/data/data/android_world/snapshots/<package>`.
- Expected flow:
  1. one-time app setup saves snapshots,
  2. each task `initialize_task/tear_down` restores snapshots.

Implication: if snapshot baseline is missing or dirty, benchmark determinism is compromised.

## 4. Clean Baseline Provisioning (Wipe Data -> Setup -> Validate)

## 4.1 Invariants
- Start from wiped userdata.
- No manual app interaction after baseline generation.
- Baseline generation and benchmark must use same AVD/image/ports.

## 4.2 Recommended runbook

```bash
# Stop emulator
adb -s emulator-5554 emu kill || true

# Cold boot from clean userdata
~/Library/Android/sdk/emulator/emulator \
  -avd AndroidWorldAvd \
  -port 5554 \
  -grpc 8554 \
  -no-snapshot \
  -wipe-data
```

Then run baseline setup command (see Section 6), validate snapshots, and only then run eval with `perform_emulator_setup=false`.

## 4.3 Interface choice
- Shell is the top-level orchestrator for destructive emulator lifecycle operations (`kill`, `wipe-data`, boot wait).
- Python command performs AndroidWorld-specific setup and snapshot verification.

Deliverables:
- `scripts/prepare_baseline.sh` (outer orchestrator).
- `eval/aw_bridge/prepare_baseline.py` (setup/verification/manifest).

## 5. Refactor Strategy (Adjusted)

## 5.1 Module split (3 modules, not over-fragmented)

- `eval/aw_bridge/runner.py`
  - thin CLI entrypoint for backward compatibility.
- `eval/aw_bridge/runner_preflight.py`
  - connectivity, package checks, baseline snapshot checks/repair, bridge install preflight.
- `eval/aw_bridge/runner_execution.py`
  - task attempt loop, retry policy, scoring, artifact/result writing.

Rationale:
- Satisfies clarity and file-size goals without creating fragile micro-modules.

## 5.2 Keep `NativeAgentBridge` intact

- Do not split into `BridgeInstaller/AgentSessionLauncher/CompletionWatcher`.
- `completion_monitor.py` already separates completion parsing.
- Current bridge boundary is coherent and should remain stable during runner refactor.

## 5.3 `parallel_runner.py` compatibility

- Keep `eval/aw_bridge/runner.py` as stable subprocess entrypoint.
- Any internal module movement must be transparent to `parallel_runner.py`.
- Explicit migration check item: run parallel smoke after Phase 1 extraction.

## 6. Snapshot and App Availability Policy

## 6.1 Snapshot policy enum (revised)

- `strict`
  - snapshots must pre-exist; no auto-repair; fail fast.
- `auto_repair` (recommended benchmark default)
  - attempt one repair pass via AndroidWorld setup path; fail if unresolved.
- `best_effort`
  - attempt repair; continue with warnings if unresolved.
- `off`
  - skip snapshot checks (debug only).

## 6.2 Package availability vs app-name mapping (dual-path, explicit)

- Keep `_TASK_REQUIRED_PACKAGES` for package-level runnability checks (`pm path`-based preflight).
- Use `task.app_names + setup.get_app_mapping()` for snapshot setup/repair only.

Reason:
- `task.app_names` are logical app names, not always directly usable package assertions.
- Multiple package variants exist for some tasks (for example contacts OEM variants).

## 6.3 Preflight order (correctness-first)

Target order:
1. Connectivity preflight.
2. Baseline snapshot preflight/repair.
3. Package runnability filtering/check.
4. Bridge install preflight (debug APK and agent-side requirements).
5. Task execution.

Reason:
- Snapshot repair can install/setup benchmark apps; doing it first avoids prematurely dropping recoverable tasks.

## 7. Migration Plan (with per-phase tests)

## Phase 0.5: restore test safety first
- Status: completed.
- `eval/tests/test_runner.py` fixture drift fixed (`task_overrides`, `snapshot_policy`).

## Phase 1: extraction without behavior change
- Status: completed.
- Functions moved into `runner_preflight.py` and `runner_execution.py`.
- `runner.py` kept as stable entrypoint for subprocess callers.

## Phase 2: baseline command
- Status: completed.
- Added `prepare_baseline.py` and `scripts/prepare_baseline.sh`.
- Added manifest output containing config scope + setup/verify reports.

## Phase 3: policy hardening
- Status: completed.
- Snapshot policy enum introduced and wired into preflight flow.
- Retry trigger changed to typed `PreflightErrorCode`.
- Added policy/retry unit tests.

## Phase 4: operational validation
- Status: pending manual run.
- Run `aw_subset_smoke` and `aw_subset_group_1` with fixed baseline.
- Compare:
  - snapshot-missing warnings (target 0 in strict/auto_repair success case),
  - reduced cross-task UI drift symptoms,
  - unchanged bridge completion/trace collection semantics.

## 8. Stage Telemetry Positioning

- Keep current structured logging as primary observability in near term.
- Defer dedicated stage telemetry framework unless logs prove insufficient for triage.

## 9. Immediate Actions

1. Use `scripts/prepare_baseline.sh` to generate a fresh clean baseline snapshot set.
2. Run `aw_subset_smoke` once with `snapshot_policy=strict` to confirm zero unresolved snapshot issues.
3. Run `aw_subset_group_1` and compare infra_failure/hang rate versus previous run.

## 10. Notes on Claude Review Incorporation

Adopted as reasonable:
- prioritize correctness over structure in narrative and plan.
- reduce over-fragmented module split.
- keep `NativeAgentBridge` boundary stable.
- keep dual-path (`_TASK_REQUIRED_PACKAGES` for runnability, dynamic mapping for setup).
- add `strict` snapshot mode.
- move tests to each phase.
- explicitly preserve `parallel_runner.py` entrypoint contract.
- use shell + python split for clean baseline workflow.

Partially adopted:
- stage telemetry is deferred, not removed; reevaluate after Phase 3 if operational debugging still expensive.

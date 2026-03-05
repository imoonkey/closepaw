# Design Review: `aw_runner_setup_codex.md`

**Reviewer**: Claude (ultra-think)
**Date**: 2026-02-26
**Verdict**: Strong diagnosis, sound direction, with specific flaws in execution details

The document correctly locates the root cause — missing snapshots producing non-deterministic eval — and proposes the right structural fix. But several proposals introduce unnecessary complexity, one has a data-model gap that would break at runtime, and the migration plan has a testing sequencing error that could defeat its own safety goals.

---

## 1. Problem Diagnosis: Accurate and Evidence-Based

The five gaps in Section 2.2 are all real and correctly prioritized. The strongest part of this document is its grounding in evidence (runner log showing `Snapshot not found`). Most design docs in this codebase would benefit from this level of rigor.

One refinement: Gap 3 ("setup concerns are coupled in one large script") is presented as equal to the snapshot problem, but it isn't. The snapshot gap causes **wrong eval results**. The coupling is an ergonomic concern. The document should explicitly rank: fix correctness first, structure second. The migration plan (Section 7) does get this ordering right — but the problem statement in Section 2 buries the lead by listing them at equal weight.

---

## 2. Module Decomposition: Over-Fragmented

The proposed 7-module split:

```
runner_cli.py
runner_orchestrator.py
preflight/connectivity.py
preflight/baseline_setup.py
preflight/bridge_setup.py
execution/task_executor.py
results/writer.py
```

These would produce modules of ~30-180 lines each. `bridge_setup.py` would be one function (~25 lines). `results/writer.py` would be `_append_jsonl` (4 lines) + scoring context capture (~60 lines) + summary write (~15 lines). These are too thin to justify package boundaries, import chains, and the navigational complexity of three nested directories.

**Counterproposal — 3 modules, not 7:**

| Module | Approx. lines | Contents |
|---|---|---|
| `runner.py` | ~80 | Thin entrypoint + CLI, imports orchestrator |
| `runner_preflight.py` | ~350 | All preflight: connectivity, emulator, bridge install, snapshot verification, app filtering |
| `runner_execution.py` | ~350 | Task loop, retry logic, scoring, result serialization |

This satisfies the 400-line guideline (`CLAUDE.md`), keeps related logic co-located, and avoids premature package boundaries. If `runner_preflight.py` grows beyond 400 lines later, split then — not preemptively.

---

## 3. Bridge Component Split: Unnecessary

Section 6.3 proposes splitting `NativeAgentBridge` into `BridgeInstaller`, `AgentSessionLauncher`, `CompletionWatcher`. But:

- `CompletionWatcher` already exists as `LogcatCompletionMonitor` in `completion_monitor.py`. This is a rename, not a new component.
- `BridgeInstaller` would be a class wrapping one function (`_build_and_install_bridge`, 25 lines). A class for this is ceremony without value.
- `AgentSessionLauncher` would duplicate the intent-extra logic that is inherently coupled to `NativeAgentBridge.run_task()`.

`NativeAgentBridge` is currently ~350 lines with a clear API surface (`run_task`, `pull_trace_dir`, `stop_agent`, `force_stop`). This is a well-sized, well-bounded component. Leave it alone. The document's instinct to modularize is right for `runner.py` but wrong for `NativeAgentBridge`.

---

## 4. Critical Gap: `app_names` vs Package Names

Section 6.4 proposes replacing `_TASK_REQUIRED_PACKAGES` with AndroidWorld's `task.app_names`:

> "derive required app list from task instances (`task.app_names`), map to AndroidWorld setup app classes via `setup.get_app_mapping(app_name)`"

This has a data-model gap. `task.app_names` returns **human-readable app names** (e.g., `"simple calendar pro"`, `"pro expense"`). But preflight filtering needs **package names** (e.g., `"com.simplemobiletools.calendar.pro"`) to call `pm path` via ADB. `get_app_mapping()` returns an app class, not a package name. You'd need `app_class.package_name` — but not all AndroidWorld app classes expose this consistently, and some tasks have multiple valid packages (e.g., `ContactsAddContact` works with both `com.android.contacts` and `com.google.android.contacts`).

The current static map exists precisely because this mapping is non-trivial and can't be derived generically. The right approach:

1. **Keep `_TASK_REQUIRED_PACKAGES`** as the authoritative preflight filter.
2. **Use `task.app_names` + `get_app_mapping()`** for snapshot generation/repair (Section 6.1-6.2), where it's already used correctly in the existing `_ensure_task_app_snapshots`.
3. Document this dual-path explicitly: static map for "can this task run?" vs. dynamic resolution for "set up the app."

---

## 5. Snapshot Policy: Tighten the Semantics

The three-level policy (`require`, `repair_best_effort`, `off`) is good, but `require` has an ambiguity: it "attempts targeted setup once" before failing. This means `require` is really "auto-repair then require." For benchmark reproducibility, you sometimes want truly strict: "if snapshots aren't there, fail immediately — don't try to fix anything."

Suggested refinement:

| Policy | Behavior |
|---|---|
| `strict` | Snapshots must exist. No auto-repair. Hard fail if missing. |
| `auto_repair` | Attempt repair once; hard fail if still missing. (Current `require` behavior.) |
| `best_effort` | Attempt repair; continue with warnings if still missing. |
| `off` | Skip entirely. |

Default for benchmark: `auto_repair`. The `strict` mode is useful for CI where you've already run `prepare_baseline.py` and want to catch any drift.

---

## 6. Stage Telemetry: Premature for Current Scale

Section 5.2 proposes each stage emit "start/end timestamp, pass/fail, actionable error code, stage-local artifacts/log summary." This is a mini-observability framework. For 5 preflight stages running sequentially in a single process, this is over-engineered. `logging.info` with structured messages already captures this information in `runner.log`.

Add structured stage telemetry in Phase 3 or later, if and only if you find yourself debugging preflight failures where log grep is insufficient. Right now, `runner.log` is the observability system — and it's working.

---

## 7. Migration Plan: Testing Must Be Per-Phase

The document sequences testing as Phase 4, after all structural and behavioral changes. This is backwards. Each phase should carry its own tests:

| Phase | Should Include |
|---|---|
| Phase 1 (structure extraction) | Move existing tests, verify they pass against new module paths. Add import-level smoke tests. |
| Phase 2 (baseline command) | Tests for `prepare_baseline.py`: manifest generation, idempotency, error handling. |
| Phase 3 (policy hardening) | Tests for snapshot policy enum, error code taxonomy, stage outcomes. |

Phase 4 as written ("update stale unit tests") should be Phase 0.5 — fix the broken `test_runner.py` fixture **before** any refactoring, not after. A broken test suite during refactoring is flying blind.

---

## 8. Missing: `parallel_runner.py` Impact

The document doesn't mention `parallel_runner.py`, which spawns `runner.py` as subprocesses per device. If the entrypoint moves to `runner_cli.py` or the module structure changes, `parallel_runner.py`'s subprocess invocation breaks. Either:

- Explicitly keep `runner.py` as the subprocess entrypoint (the document says "thin entrypoint wrapper" — good, but call out parallel_runner compatibility explicitly), or
- Update `parallel_runner.py` in Phase 1.

---

## 9. `prepare_baseline.py`: Good, But Scope the Python Part

The dedicated baseline command is the best idea in this document. But Section 4.3's runbook reveals that the critical steps (stop emulator, wipe data, cold boot) are shell commands, not Python. The Python `prepare_baseline.py` can only do the snapshot generation step — it can't manage emulator lifecycle cleanly.

Recommendation: make the primary interface a **shell script** (`scripts/prepare_baseline.sh`) that orchestrates the full flow (kill, wipe, boot, wait, python setup, validate), with `prepare_baseline.py` as the Python component it calls for AndroidWorld-specific setup. This matches the existing pattern of `scripts/debug-run.sh`.

---

## 10. Preflight Ordering Insight: Correct and Important

Section 6.5's proposed ordering change is the most operationally significant improvement in this document:

**Current:** adb, bridge install, app install, filter tasks, snapshots
**Proposed:** connectivity, snapshots, bridge install, filter, execute

This is correct because snapshot generation can itself install apps (via `maybe_install_app` + `setup_app`), so doing it before task filtering avoids dropping recoverable tasks. And bridge install is independent of snapshots — the agent APK has nothing to do with benchmark app state. This reordering alone would fix several failure modes. Ship this in Phase 0, not Phase 1.

---

## Summary of Recommendations

| # | Recommendation | Priority |
|---|---|---|
| 1 | Fix broken `test_runner.py` first (Phase 0.5) | Immediate |
| 2 | Reorder preflight: snapshots before bridge install (Section 6.5) | High |
| 3 | Reduce module split from 7 to 3 (`runner.py`, `runner_preflight.py`, `runner_execution.py`) | High |
| 4 | Keep `_TASK_REQUIRED_PACKAGES` for preflight; use `task.app_names` only for snapshot setup | High |
| 5 | Don't split `NativeAgentBridge` — it's already well-bounded | Medium |
| 6 | Add `strict` snapshot policy level (no auto-repair) | Medium |
| 7 | Make baseline provisioning a shell script wrapping Python, not Python alone | Medium |
| 8 | Address `parallel_runner.py` compatibility explicitly | Medium |
| 9 | Move tests into each phase, not a trailing Phase 4 | Medium |
| 10 | Defer stage telemetry framework to when it's needed | Low |

The document's core insight — that snapshot integrity is the #1 eval correctness issue and needs a first-class operational workflow — is right. The refactoring direction is sound. The specific decomposition choices need the adjustments above to avoid trading one kind of complexity for another.

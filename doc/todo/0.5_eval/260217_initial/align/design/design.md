# 0.5_eval Alignment Design (Codex + Claude)

Date: 2026-02-17  
Scope: `doc/todo/0.5_eval`

## 1. Alignment Objective

Produce one implementation-ready evaluation design that preserves benchmark correctness while matching our native on-device Android Agent architecture.

## 2. Agreed Core Decisions

1. Use a **custom bridge runner** instead of adapting to AndroidWorld `step()` agent interface.
2. Reuse AndroidWorld as source of truth for:
   - task setup (`initialize_task`)
   - task scoring (`is_successful`)
   - task cleanup (`tear_down`)
3. Execute our app natively via ADB intent (same contract family as `scripts/debug-run.sh`).
4. Keep a strict distinction between:
   - operational run status (completed/error/timeout/infra failure)
   - benchmark truth (`scripted_success`, `scripted_score`)
5. Preserve artifacts per task for triage (trace/logcat/runner output).

## 3. Unified Evaluation Tiers

1. **Tier 0: Manual smoke (immediate)**
   - 10-15 curated tasks run via `./scripts/debug-run.sh`.
   - Human verification for real-world correctness and quick regression checks.
2. **Tier 1: AndroidWorld bridge automation (primary)**
   - Automated reproducible runs using AndroidWorld task/eval logic.
   - Baseline capability score from scripted success rate.
3. **Tier 2: CI + regression detection**
   - Scheduled/label-triggered runs.
   - Compare against pinned baseline; fail on configured regression threshold.
4. **Tier 3: MobileWorld extension (optional, future)**
   - Only after Tier 1/2 is stable.
   - Start from GUI-only subset; keep separate reporting track.

## 4. Tier 1 System Design

## 4.1 Task Execution Flow

For each task instance:
1. `task.initialize_task(env)` — resets `env.interaction_cache`, restores app snapshots
2. Launch agent app with intent extras (goal + pinned runtime config + `trace_run_id`)
3. Wait for terminal signal
4. Parse trace artifacts (summary + optional `complete_task` answer)
5. **Inject answer**: if trace contains a `complete_task` answer, set `env.interaction_cache = trace.answer`
6. Run `task.is_successful(env)`
7. `task.tear_down(env)` — restores app snapshots again for clean state
8. Persist structured results + pull artifacts
9. Force-stop agent app to prevent state leakage

Note: step 7 (`tear_down`) already handles per-task app snapshot restore internally
(via `_initialize_apps` → `app_snapshot.restore_snapshot`), so no separate
emulator-level snapshot restore is needed between tasks.

## 4.2 Completion Monitor Policy

Use multi-signal detection:
1. Primary: logcat terminal patterns (as in `scripts/debug-run.sh`)
2. Secondary: trace run summary artifact existence
3. Tertiary: bounded timeout

Terminal classification:
- `completed`: task/session completion event
- `error`: session error event
- `timeout`: wait exceeded
- `infra_failure`: runner/adb/env failure before valid completion

## 4.3 Per-Task Result Schema

```json
{
  "task_name": "ContactsAddContact",
  "suite_family": "android_world",
  "seed": 123456,
  "goal": "...",
  "run_id": "aw_20260217_101530_ContactsAddContact_0",
  "bridge_status": "completed|error|timeout|infra_failure",
  "agent_completion_reason": "GoalAchieved|MaxTurnsReached|Error|...",
  "task_status": "success|failure|null",
  "answer": "string|null",
  "scripted_score": 0.0,
  "scripted_success": false,
  "duration_sec": 81.2,
  "turns_executed": 0,
  "tool_calls": 0,
  "tool_failures": 0,
  "artifact_paths": {
    "trace_dir": "...",
    "logcat": "...",
    "runner_log": "..."
  },
  "exception": null
}
```

## 4.4 Metrics and Retry Policy

Primary metric:
- Scripted Success Rate = `sum(scripted_success) / N`

Secondary metrics:
- Timeout rate
- Infra failure rate
- P50/P90 duration
- Goal-claim precision (`GoalAchieved` vs scripted success)
- Tool failure rate

Retry policy:
- Retry only infra failures (adb/env/evaluator instability)
- Do not auto-retry scripted failures/timeouts in headline score
- Record both first attempt and retried outcomes

## 5. Implementation Plan (Merged)

1. **Phase 0 (1 day)**: scaffold `eval/`, define pinned config, add Tier 0 tracker.
2. **Phase 1 (3-5 days)**: implement bridge MVP runner + trace parser + per-task persistence.
3. **Phase 2 (3-5 days)**: harden (checkpoint/resume, error taxonomy, compare reports, regression gate).
4. **Phase 3 (optional)**: pilot MobileWorld subset.

Exit criteria for Tier 1/2 readiness:
- One command runs a fixed subset end-to-end and stores deterministic artifacts.
- Full suite can run unattended in scheduled mode.
- Baseline comparison report clearly flags regressions.

## 6. Repository Layout (Aligned)

```text
eval/
  README.md
  requirements.txt
  config/
    default.yaml
    aw_subset_smoke.txt
    aw_subset_core.txt
  aw_bridge/
    runner.py
    task_loader.py
    native_agent_bridge.py
    completion_monitor.py
    trace_parser.py
    result_schema.py
  analysis/
    summarize.py
    compare_runs.py
  results/
    <timestamp>/
      summary.json
      per_task.jsonl
      artifacts/
```

## 7. Resolved Design Decisions

### 7.1 Tier naming: Tier 0/1/2/3 (confirmed)

Four named tiers: Manual Smoke → AW Bridge → CI/Regression → MobileWorld.
CI/regression detection is a separate tier (Tier 2) rather than folded into Tier 1 phases,
because it has distinct infrastructure (GitHub Actions, baseline storage, threshold config)
and can be deferred independently.

### 7.2 Info-retrieval answer injection (resolved)

Mechanism confirmed from AndroidWorld source code:

- AndroidWorld stores agent answers in `env.interaction_cache` (a string attribute on `AsyncAndroidEnv`).
- Normally populated when an agent executes a `JSONAction(action_type='answer', text=...)` via `env.execute_action()`.
- `task.initialize_task(env)` resets `env.interaction_cache = ""` at the start of each task.
- `is_successful(env)` reads `env.interaction_cache` to evaluate info-retrieval tasks.

**Our bridge approach**: after the agent completes and we parse the trace, directly set:
```python
if trace.answer is not None:
    env.interaction_cache = trace.answer
```
This is done **before** calling `task.is_successful(env)` (step 5 in §4.1).

No ADB broadcast, device file, or other indirection needed — just a Python attribute assignment
on the `env` object we already hold in the runner process.

Evidence:
- `interface.py:execute_action()` — stores answer text directly in `self.interaction_cache`
- `information_retrieval.py:is_successful()` — reads `env.interaction_cache` for comparison
- `proto_utils.check_agent_answer()` — validates via string/number/date/time matching with fuzzy support

### 7.3 Snapshot restore strategy: per-task (confirmed)

AndroidWorld's own code uses per-task restore as the standard pattern:
- `task_eval.py:initialize_task()` calls `_initialize_apps()` → `app_snapshot.restore_snapshot()` for each app
- `task_eval.py:tear_down()` also calls `_initialize_apps()` for cleanup
- No batching mechanism exists in AndroidWorld — each task gets full isolation

We follow the same pattern. The overhead is acceptable because:
- Snapshot restore is app-data-level (copy files), not emulator-level (no full AVD reload)
- Task execution itself (30-300s) dominates over restore cost (~2-5s per app)
- Isolation correctness is more important than throughput for capability measurement

### 7.4 CI regression thresholds (deferred to Tier 2 implementation)

Threshold policy will be set empirically after Tier 1 produces initial baselines.
Starting point: flag if overall TSR drops > 5% or any previously-passing task regresses.
Refine as variance data accumulates.

# Android Agent Core Capability Evaluation Design (Codex)

Date: 2026-02-16  
Scope: `doc/todo/0.5_eval`

## 1. Executive Summary

Your agent is **on-device and autonomous**. AndroidWorld/MobileWorld agents are typically **host-side and step-driven**.  
Trying to force your app into their `step()` interface is the wrong abstraction.

The right design is:

1. Reuse benchmark **task setup + scripted success checks**.
2. Run your app as-is via ADB intent (same mechanism as `scripts/debug-run.sh`).
3. Read terminal status (`TaskCompleted`/timeout/error), then score with benchmark `is_successful(...)`.

This gives you real success-rate numbers with minimal architectural distortion.

## 2. Goals and Non-Goals

### Goals
- Measure core capability with reproducible task success rates.
- Detect regressions after agent/prompt/tool changes.
- Keep eval harness simple enough to maintain.
- Produce artifacts for failure triage (trace + logs + task params).

### Non-Goals
- Perfect benchmark parity on day 1.
- Re-implementing AndroidWorld or MobileWorld infrastructure.
- Overfitting to one benchmark leaderboard before internal reliability is stable.

## 3. Ground Truth Constraints (From Current Code)

- Task start contract already exists via intent extras in `scripts/debug-run.sh`.
- Trace artifacts already exist under:
  - `/sdcard/Android/data/com.moonkey.androidagent/files/inspection-trace/<trace_run_id>`
- Runtime emits terminal task event semantics:
  - `AgentEvent.TaskCompleted(reason=CompletionReason.*)` in `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt`
- Current practical completion detection can use logcat event lines (already used by `scripts/debug-run.sh`).

Implication: you already have enough surface area for an automated evaluator without refactoring the agent loop.

## 4. Architecture Decision

## Decision
Build a **Bridge Runner** that reuses AndroidWorld task definitions/evaluators and invokes your app via ADB intent.

## Why this is correct
- Keeps benchmark truth source (`initialize_task`, `is_successful`, `tear_down`) intact.
- Avoids fake step wrappers that do not match your execution model.
- Gives immediate ROI and clean migration path to broader suites later.

## What to avoid
- Do **not** rewrite your agent into AndroidWorld `EnvironmentInteractingAgent.step()`.
- Do **not** start with MobileWorld full stack (Docker-in-Docker, self-hosted backends, MCP, user-agent tasks) before AndroidWorld bridge is stable.

## 5. Three-Tier Evaluation Strategy

## Tier 0: Manual Smoke (Immediate)
- 10-20 curated tasks on device/emulator.
- Run via `scripts/debug-run.sh`.
- Human verifies real-world result.

Purpose: cheap pre-merge sanity and high-signal regressions.

## Tier 1: AndroidWorld Bridge (Primary)
- Automated suite runner over AndroidWorld tasks.
- Uses AndroidWorld task lifecycle and scripted success checks.
- Uses your app for execution.

Purpose: objective, reproducible core capability score.

## Tier 2: MobileWorld Extension (Later)
- Only after Tier 1 is stable and actionable.
- Start GUI-only subset first.
- Gate MCP and user-interaction tasks behind dedicated milestones.

Purpose: long-horizon + backend-integrated realism.

## 6. Tier 1 System Design (AndroidWorld Bridge)

## 6.1 High-Level Flow

For each task instance:

1. `task.initialize_task(env)`
2. Trigger Android app with `goal=task.goal` + deterministic config + `trace_run_id`.
3. Wait for terminal signal (`TaskCompleted`, `SessionError`, timeout).
4. Score with `task.is_successful(env)`.
5. `task.tear_down(env)`.
6. Persist result + pull trace/log artifacts.

## 6.2 Components

### A. Task Provider
- Source: `.reference/eval/android_world/android_world/registry.py`
- Instantiates task class + seeded params.

### B. Device Environment
- Source: `.reference/eval/android_world/android_world/env/env_launcher.py`
- Reused for setup/snapshots/checks only.

### C. Native Agent Bridge
- Sends intent to `com.moonkey.androidagent/.app.MainActivity`
- Required extras:
  - `goal`
  - `auto_start=true`
  - `fresh_session=true`
  - `trace_enabled=true`
  - `trace_run_id=<run_id>`
  - `agent_mode`, `perception_mode`, `platform_mode`
  - model/backend fields for eval config pinning

### D. Completion Monitor
- Short term: logcat pattern matching (TaskCompleted / SessionError / timeout).
- Mid term hardening: add explicit eval completion artifact or broadcast event (recommended).

### E. Scorer + Reporter
- Primary score from AndroidWorld `task.is_successful(env)`.
- Persist per-task JSONL and aggregate summary.

## 6.3 Result Schema (Per Task Instance)

```json
{
  "task_name": "ContactsAddContact",
  "suite_family": "android_world",
  "seed": 123456,
  "goal": "...",
  "run_id": "aw_20260216_153011_ContactsAddContact_0",
  "bridge_status": "completed|error|timeout|infra_failure",
  "agent_completion_reason": "GOAL_ACHIEVED|MAX_TURNS|ERROR|...",
  "scripted_score": 0.0,
  "scripted_success": false,
  "duration_sec": 81.2,
  "artifact_paths": {
    "trace_dir": "...",
    "logcat": "...",
    "runner_log": "..."
  },
  "exception": null
}
```

Important split:
- `bridge_status` = operational execution status.
- `scripted_success` = benchmark truth.

## 6.4 Metrics

Primary:
- Scripted Success Rate = `sum(scripted_success) / N`

Secondary:
- Timeout Rate
- Infra Failure Rate
- Median/P90 duration
- Goal-claim precision:
  - among `agent_completion_reason == GOAL_ACHIEVED`, how many are scripted-success

Diagnostics:
- By app family, by task complexity, by perception mode, by agent mode.

## 6.5 Flakiness Policy

- Retry only infra failures (`infra_failure`, adb disconnect, evaluator crash).
- Do not auto-retry normal failures (timeout, scripted fail) in headline score.
- Store both first-attempt and retried outcomes for transparency.

## 7. Implementation Plan

## Phase 0 (1 day): Minimal baseline
- Create `eval/` scaffold.
- Implement Tier 0 markdown tracker + script wrapper.
- Define fixed eval config (backend/model/mode/perception/platform/max wait).

## Phase 1 (3-5 days): AndroidWorld bridge MVP
- `eval/runner_aw_bridge.py`
- `eval/native_agent_bridge.py`
- `eval/task_loader_aw.py`
- `eval/results/<timestamp>/...`
- Run a subset (10-20 AndroidWorld tasks) end-to-end.

Exit criteria:
- One command runs subset, outputs deterministic report + artifacts.

## Phase 2 (3-5 days): Robustness and CI usability
- Resume/checkpoint support.
- Better error taxonomy.
- Comparative reporting (`compare_runs.py`).
- Regression gate config (fail if success rate drop > threshold).

Exit criteria:
- Can run nightly and compare against last good baseline.

## Phase 3 (optional): MobileWorld pilot
- GUI-only task subset first.
- Keep separate leaderboard from AndroidWorld to avoid mixed signals.

## 8. Recommended Repository Layout

```text
eval/
  README.md
  config/
    default.yaml
    aw_subset_smoke.txt
  aw_bridge/
    runner.py
    task_loader.py
    native_agent_bridge.py
    completion_monitor.py
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

## 9. Risks and Mitigations

- Brittle logcat completion parsing.
  - Mitigation: introduce explicit eval completion signal in app (broadcast/file) once bridge MVP works.
- Emulator/app drift across runs.
  - Mitigation: strict snapshot restore and pinned task seeds.
- Cost/time explosion.
  - Mitigation: smoke subset for frequent runs, full suite nightly/weekly.
- Benchmark mismatch with your production phone environment.
  - Mitigation: keep Tier 0 manual real-device tasks as a parallel track.

## 10. Concrete Command Targets

Planned commands:

```bash
# Fast local subset
python eval/aw_bridge/runner.py --tasks_file eval/config/aw_subset_smoke.txt --n_task_combinations 1

# Full AndroidWorld run
python eval/aw_bridge/runner.py --suite android_world --n_task_combinations 3

# Compare against baseline
python eval/analysis/compare_runs.py --base eval/results/20260215_010000 --new eval/results/20260216_010000
```

## 11. Definition of Done

You are done when all are true:

1. You can run a fixed subset and full suite without manual intervention.
2. Each task result has goal, params seed, terminal status, scripted score, and artifacts.
3. You can diff runs and detect statistically meaningful regressions.
4. Team can trust one headline number: scripted success rate on pinned suite/config.

## 12. Final Recommendation

Start with **Tier 1 AndroidWorld Bridge now**.  
It is the shortest path to objective capability measurement without corrupting your agent architecture.

Then stabilize and only after that, expand into MobileWorld complexity.

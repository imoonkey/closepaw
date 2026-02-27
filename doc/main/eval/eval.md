# Evaluation Runner Architecture

> How the eval harness executes AndroidWorld tasks and scores agent performance.

## Overview

The eval runner bridges the Android Agent app with
[AndroidWorld](https://github.com/google-research/android_world) -- an open
benchmark of ~116 tasks that test device-automation agents on real Android
apps.  AndroidWorld owns task **definitions** (initialise, score, tear-down);
our runner owns **agent orchestration** (launch via ADB, monitor completion,
pull trace artifacts, aggregate metrics).

```
┌──────────┐      ┌──────────────┐      ┌───────────────────┐
│ runner.py│─────▶│ preflight.py │─────▶│ AndroidWorld env  │
│  (CLI)   │      │  (snapshot,  │      │ (emulator, gRPC)  │
│          │      │   install)   │      └───────────────────┘
│          │      └──────────────┘
│          │──┐
│          │  │   ┌──────────────────┐   ┌───────────────────┐
│          │  └──▶│ execution.py     │──▶│ native_agent_     │
│          │      │ (per-task loop)  │   │ bridge.py (ADB)   │
└──────────┘      └──────────────────┘   └───────────────────┘
                         │                        │
                         ▼                        ▼
                  ┌──────────────┐         ┌─────────────┐
                  │ result_      │         │ completion_  │
                  │ schema.py    │         │ monitor.py   │
                  └──────────────┘         └─────────────┘
```

## Module Decomposition

| Module | Responsibility |
|--------|---------------|
| `runner.py` | CLI entry-point, YAML config loading, orchestration loop, result output |
| `runner_preflight.py` | Device readiness, snapshot policy, app installation, APK build/install |
| `runner_execution.py` | Single-task lifecycle: init → bridge → score → teardown |
| `native_agent_bridge.py` | ADB commands: launch activity, a11y service, Shizuku, logcat capture |
| `task_loader.py` | AndroidWorld TaskRegistry integration, `TaskInstance` creation |
| `completion_monitor.py` | Logcat polling for completion/error signals |
| `trace_parser.py` | Parses trace artifacts (answer, turns, tool stats) from pulled files |
| `result_schema.py` | `TaskResult` dataclass, metric aggregation (`summarize_results`) |
| `prepare_baseline.py` | Snapshot generation for clean emulator baselines |
| `setup_task_only.py` | Task setup without agent execution (debugging aid) |

## Configuration

### RunnerConfig

Top-level orchestration settings loaded from YAML + CLI overrides.

Key fields: `suite_family`, `output_root`, `task_random_seed`,
`n_task_combinations`, `skip_unavailable_tasks`,
`auto_install_missing_task_apps`, `retry_infra_failures`, `snapshot_policy`.

### BridgeConfig

Per-task agent settings passed to `NativeAgentBridge`.

Key fields: `package_name`, `activity`, `llm_backend`, `agent_mode`,
`perception_mode`, `platform_mode`, `main_model`, `executor_model`,
`max_turns`, `auto_start`, `fresh_session`, `max_wait_seconds`,
`excluded_tools`, `api_keys`.

### YAML Structure

```yaml
suite_family: android_world

runner:
  output_root: eval/results
  snapshot_policy: auto_repair
  retry_infra_failures: 1
  # ...

android_world:
  reference_root: .reference/eval/android_world
  console_port: 5554
  grpc_port: 8554
  freeze_datetime: true
  # ...

bridge:
  llm_backend: openai
  main_model: qwen3.5
  perception_mode: accessibility_only
  max_turns: 30
  # ...
  task_overrides:
    BrowserDraw: { perception_mode: hybrid }
    ExpenseAddMultipleFromGallery: { perception_mode: hybrid }
```

### Task Overrides

Per-task config overrides under `bridge.task_overrides`.  Resolved by
**longest-prefix match** on the task name.  Any `BridgeConfig` field can
be overridden (`perception_mode`, `max_turns`, `excluded_tools`, etc.).

See `resolve_task_bridge_config()` in `runner_execution.py`.

## Snapshot Policy

Controls how the runner manages AndroidWorld app baseline snapshots
(saved `data/data` directories used to reset apps between tasks).

| Policy | Behaviour |
|--------|-----------|
| `strict` | Fail if any required snapshots are missing |
| `auto_repair` | Create missing snapshots automatically; fail if still unresolved **(default)** |
| `best_effort` | Warn and continue even with missing snapshots |
| `off` | Skip all snapshot checks |

Set via `--snapshot-policy` CLI flag or `runner.snapshot_policy` in config YAML.

## Preflight Error System

Typed errors (`PreflightError` with `PreflightErrorCode`) allow structured
recovery:

| Code | Trigger | Recovery |
|------|---------|----------|
| `MISSING_TASK_PACKAGES` | Required app APKs not on device | Auto-retry with `perform_emulator_setup=true` |
| `SNAPSHOTS_MISSING` | Baseline snapshots not found | Auto-retry with emulator setup |
| `PRECHECK_FAILED` | Generic preflight failure | No automatic recovery |

`should_run_emulator_setup_retry()` decides whether a second attempt with
full emulator setup is warranted.

## Task Execution Flow

`run_one_task_instance()` in `runner_execution.py`:

1. **Initialise** -- `task.initialize_task(env)` sets up app state
   (e.g., add calendar events, pre-populate files).
2. **Create run context** -- Generate `run_id`, create artifact directory.
3. **Bridge run** -- `bridge.run_task(goal, run_id)` launches the agent via
   ADB intents, captures logcat, polls `completion_monitor` for signals.
   Returns `BridgeOutcome` (`completed` / `error` / `timeout` /
   `infra_failure`).
4. **Pull trace** -- `bridge.pull_trace_dir()` copies device trace to host.
5. **Parse trace** -- `trace_parser.parse_trace()` extracts answer, turns,
   tool call counts, completion reason from `trace.jsonl` and
   `run_summary`.
6. **Score** -- If bridge succeeded, call `task.is_successful(env)` for
   AndroidWorld's scripted validation (returns 0.0-1.0).
7. **Teardown** -- `task.tear_down(env)` resets app state.
8. **Result** -- Build `TaskResult`, append to `per_task.jsonl`.
9. **Retry** -- If `infra_failure` and retries remain, go to step 1.

## Result Schema

### TaskResult

| Field | Type | Description |
|-------|------|-------------|
| `task_name` | str | AndroidWorld task class name |
| `bridge_status` | str | `completed` / `error` / `timeout` / `infra_failure` |
| `agent_completion_reason` | str | Agent's stop reason (e.g., `GOAL_ACHIEVED`) |
| `scripted_score` | float | AndroidWorld score (0.0-1.0) |
| `scripted_success` | bool | Score > 0.5 |
| `turns_executed` | int | LLM turns used |
| `tool_calls` / `tool_failures` | int | Tool execution stats |
| `duration_sec` | float | Wall-clock seconds |
| `artifact_paths` | ArtifactPaths | Paths to trace_dir, logcat, runner_log |

### Summary Metrics

`summarize_results()` aggregates a list of `TaskResult` into:

- `scripted_success_rate` -- fraction of tasks with `scripted_success=true`
- `timeout_rate`, `infra_failure_rate`, `error_rate` -- failure breakdowns
- `duration_p50_sec`, `duration_p90_sec` -- latency percentiles
- `goal_claim_precision` -- fraction of `GOAL_ACHIEVED` claims that scored > 0.5
- `tool_failure_rate` -- `tool_failures / tool_calls`

### Output Layout

```
eval/results/<timestamp>/
  summary.json              # Aggregated metrics
  per_task.jsonl             # One JSON record per attempt
  artifacts/<run_id>/        # Per-attempt artifacts
    logcat.txt
    trace/                   # Pulled from device
    scoring_context.json     # AndroidWorld scoring details
```

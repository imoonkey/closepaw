# Evaluation Runner Architecture

> How the eval harness executes AndroidWorld tasks and scores agent performance.

## Overview

The eval runner bridges the ClosePaw app with
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
| `parallel_runner.py` | Supervisor for 2-device local parallel eval, shard setup, result merge |

## Configuration

### RunnerConfig

Top-level orchestration settings loaded from YAML + CLI overrides.

Key fields: `suite_family`, `output_root`, `task_random_seed`,
`n_task_combinations`, `skip_unavailable_tasks`,
`auto_install_missing_task_apps`, `perform_bridge_setup`,
`retry_infra_failures`, `snapshot_policy`.

### BridgeConfig

Per-task agent settings passed to `NativeAgentBridge`.

Key fields: `package_name`, `activity`, `llm_backend`,
`perception_mode`, `platform_mode`, `main_model`,
`max_turns`, `auto_start`, `fresh_session`, `max_wait_seconds`,
`excluded_tools`, `clear_memory_before_task`, `api_keys`.

The yaml-side `max_turns` key is preserved for backwards compatibility, but the
runner now plumbs it through to `SessionConfig.evalTurnBudget` (intent extra
`eval_turn_budget`). It is an **eval-only runaway safety net**, not a
production turn cap: hitting it stops the agent with
`AgentStopReason.Error("Eval turn budget reached (...)")` and the task records
`TaskOutcome.ERROR`. There is no `MAX_TURNS` outcome — production runs are
bounded by context-window auto-compaction instead. See
[agent/loop.md](../agent/loop.md#auto-compaction).

### YAML Structure

```yaml
suite_family: android_world

runner:
  output_root: eval/results
  perform_bridge_setup: true
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

Any non-default `--config` file is loaded as a deep override on top of
`eval/config/default.yaml`, so config variants only need to include changed
fields.

### Task Overrides

Per-task config overrides under `bridge.task_overrides`.  Resolved by
**longest-prefix match** on the task name.  Any `BridgeConfig` field can
be overridden (`perception_mode`, `max_turns`, `excluded_tools`, etc.).

Default eval hygiene excludes `remember_experience` and clears
`files/memory` before each task launch so long-term memory cannot carry across
tasks or from earlier runs.

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

## Local Parallel Flow

`parallel_runner.py` keeps `runner.py` as the single-device engine. The
supervisor:

1. Shards the selected tasks across explicit device tuples.
2. Builds the agent APK once and installs it once per device when
   `runner.perform_bridge_setup=true`.
3. Writes worker config overlays with `runner.perform_bridge_setup=false` and
   `android_world.auto_start_emulator=false`.
4. Launches one `runner.py` subprocess per device.
5. Merges shard outputs back into the normal `eval/results/<timestamp>/`
   contract.

The supported local contract is two prepared emulators:

| Device | Serial | Console Port | gRPC Port | Default AVD |
|--------|--------|--------------|-----------|-------------|
| A | `emulator-5554` | `5554` | `8554` | `AndroidWorldAvd` |
| B | `emulator-5556` | `5556` | `8556` | `AndroidWorldAvd2` |

Use `./scripts/prepare_baseline.sh` once per AVD, then run
`./scripts/eval_parallel.sh eval/config/<task_file>`. The standard one-time prep
path is `./scripts/prepare_parallel_baselines.sh`, which prepares both supported
AVDs sequentially before parallel runs.

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

```text
eval/results/<timestamp>/
  summary.json             # Aggregated metrics
  per_task.jsonl           # One JSON record per attempt
```

Parallel runs add shard debug artifacts without changing the top-level files:

```text
eval/results/<timestamp>/
  summary.json
  per_task.jsonl
  parallel/
    shard_manifest.json
    shards/
      shard_00_emulator_5554/
        run/<worker_timestamp>/artifacts/<run_id>/
      shard_01_emulator_5556/
        run/<worker_timestamp>/artifacts/<run_id>/
```

Serial runs keep per-attempt artifacts under `artifacts/<run_id>/` in the
top-level run directory. Parallel runs keep the canonical top-level metrics
files, while each `per_task.jsonl` row points at the shard-local artifact paths
through `artifact_paths`.

## Remote Eval Worker

For long-running eval batches, a headless emulator can run on a remote machine (e.g. `desktop`) while the dev machine drives orchestration.

### Setup

- `scripts/remote/provision.sh` — one-shot remote setup (JDK 17, Python 3.11, Android SDK, dual AVDs)
- `scripts/remote/proxy_tunnel.sh` — autossh tunnel service manager (install/start/stop/status)
- `scripts/remote/openai-proxy-tunnel.service` — systemd user unit with auto-reconnect
- `scripts/remote/eval_tmux.sh` — tmux wrapper for SSH-disconnect-safe eval runs
- `eval/config/remote.yaml` — remote-specific overrides (currently just `adb_path`)

### Key Details

- ADB path and emulator path are expanded from config before use (no assumption of `PATH` availability)
- Preflight ADB calls route through the configured binary
- Emulator pinned to `32.1.15` (`emulator-linux_x64-10696886.zip`) for Ubuntu 18.04 compatibility
- `prepare_baseline.sh` and `eval_parallel.sh` support `--headless` flag for remote
- Dual-emulator parallel eval: same layout as local (`AndroidWorldAvd` + `AndroidWorldAvd2`)
- Proxy tunnel managed via autossh systemd service (sources keychain for passphrase-protected SSH keys)

-> See: `/autotune` skill Step 3 for remote eval operational details.

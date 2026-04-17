# ClosePaw Evaluation Harness

This folder contains the Tier 0/1 evaluation implementation described in
`doc/todo/0.5_eval/align/design/design.md`.

## What this supports now

- Tier 0: curated task lists under `eval/config/*.txt`.
- Tier 1 MVP: AndroidWorld bridge runner that:
  - reuses AndroidWorld task lifecycle (`initialize_task`, `is_successful`, `tear_down`)
  - launches this app natively through ADB intent extras
  - monitors completion from logcat + timeout
  - parses pulled trace artifacts (`run_summary`, `complete_task.answer`)
  - persists `per_task.jsonl` and `summary.json`
  - typed preflight error system with automatic recovery
  - snapshot policy for baseline management (strict / auto_repair / best_effort / off)
  - per-task config overrides (e.g., perception mode per task)
  - baseline preparation script (`scripts/prepare_baseline.sh`)
  - dual-emulator launcher for local parallel eval (`scripts/eval_parallel.sh`)
  - setup-only mode for task inspection (`setup_task_only.py`)

## Module Structure

```
eval/aw_bridge/
  runner.py              # CLI entry, config loading, orchestration loop
  runner_preflight.py    # Device/env readiness, snapshot policy, ADB helpers
  runner_execution.py    # Per-task lifecycle (init, bridge, score, teardown)
  native_agent_bridge.py # ADB-based agent launch, a11y service, Shizuku, logcat
  task_loader.py         # AndroidWorld task registry integration
  completion_monitor.py  # Logcat-based completion/error detection
  trace_parser.py        # Trace artifact parsing (answer, turns, tool stats)
  result_schema.py       # TaskResult, ArtifactPaths, metric summarization
  prepare_baseline.py    # Snapshot preparation for clean baseline
  setup_task_only.py     # Task setup without agent execution
  parallel_runner.py     # Multi-emulator parallel orchestration
```

## Quick Start

1. Create/use eval virtualenv and install Python dependencies for AndroidWorld and this harness.
2. Ensure emulator/device + AndroidWorld runtime are ready.
3. Install latest APK to device (`./scripts/setup.sh`).
4. Run a smoke subset:

```bash
# Run from task file
eval/.venv/bin/python eval/aw_bridge/runner.py --tasks-file eval/config/aw_subset_smoke.txt

# Run specific tasks
eval/.venv/bin/python eval/aw_bridge/runner.py --tasks "TaskA,TaskB"

# With snapshot policy override
eval/.venv/bin/python eval/aw_bridge/runner.py \
  --tasks-file eval/config/aw_subset_smoke.txt \
  --snapshot-policy best_effort
```

Use `eval/.venv/bin/python` for eval-related commands to avoid dependency/version drift.

### Local Parallel Eval

For the validated 2-device path, prepare both AVDs once, then use the helper:

```bash
# One-time baseline prep for both AVDs
./scripts/prepare_parallel_baselines.sh

# Equivalent explicit per-AVD commands
./scripts/prepare_baseline.sh --avd AndroidWorldAvd --console-port 5554 --grpc-port 8554 --adb-serial emulator-5554
./scripts/prepare_baseline.sh --avd AndroidWorldAvd2 --console-port 5556 --grpc-port 8556 --adb-serial emulator-5556

# Normal parallel eval run
./scripts/eval_parallel.sh eval/config/aw_subset_smoke.txt
```

Defaults for the helper:

- Device A: `AndroidWorldAvd` -> `emulator-5554` / console `5554` / gRPC `8554`
- Device B: `AndroidWorldAvd2` -> `emulator-5556` / console `5556` / gRPC `8556`

`scripts/eval_parallel.sh` starts missing emulators, validates that the two
devices use distinct AVDs/ports, and refuses to launch if an AVD is already
running on the wrong serial. Baseline prep remains a separate workflow; use
`./scripts/prepare_parallel_baselines.sh` for the standard dual-emulator setup.

### CLI Arguments

| Flag | Default | Description |
|------|---------|-------------|
| `--config` | `eval/config/default.yaml` | Config file path |
| `--suite` | from config | Suite family (e.g., `android_world`) |
| `--tasks` | none | Comma-separated task names |
| `--tasks-file` | none | File with task names (one per line) |
| `--n-task-combinations` | from config | Number of param combinations per task |
| `--task-random-seed` | from config | Random seed for task params |
| `--output-root` | `eval/results` | Output directory |
| `--adb-serial` | auto-detected | ADB device serial (e.g., `emulator-5554`) |
| `--snapshot-policy` | `auto_repair` | Snapshot management policy |
| `--platform-mode` | `accessibility` | `accessibility` or `virtual_display` |

Any non-default `--config` file is loaded as a deep override on top of
`eval/config/default.yaml`, so override files only need to include changed
fields.

## Output Layout

Each run writes to:

`eval/results/<timestamp>/`

- `summary.json`: aggregated metrics
- `per_task.jsonl`: one JSON record per attempt
- per-task artifact locations come from `artifact_paths` in `per_task.jsonl`

Parallel runs keep the same top-level contract and add shard debug artifacts:

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

Serial runs still write artifacts directly under `artifacts/<run_id>/` in the
top-level run directory.

## Snapshot Policy

Controls how the runner handles AndroidWorld app baseline snapshots.

| Policy | Behavior |
|--------|----------|
| `strict` | Fail if any required snapshots are missing |
| `auto_repair` | Try to create missing snapshots; fail if unresolved (default) |
| `best_effort` | Warn and continue even if snapshots are missing |
| `off` | Skip all snapshot checks |

Set via `--snapshot-policy <policy>` CLI flag or `runner.snapshot_policy` in config YAML.

See `SnapshotPolicy` enum and `PreflightError` / `PreflightErrorCode` in `runner_preflight.py`.

## Baseline Preparation

For a clean emulator baseline:

```bash
./scripts/prepare_parallel_baselines.sh

# Or prepare each AVD explicitly
./scripts/prepare_baseline.sh --avd AndroidWorldAvd --console-port 5554 --grpc-port 8554 --adb-serial emulator-5554
./scripts/prepare_baseline.sh --avd AndroidWorldAvd2 --console-port 5556 --grpc-port 8556 --adb-serial emulator-5556
```

This kills any existing emulator, starts a clean one with `-wipe-data`, then runs
`prepare_baseline.py` to install required apps and generate snapshots.

Options: `prepare_baseline.sh` supports `--avd`, `--console-port`, `--grpc-port`,
`--adb-serial`, `--snapshot-policy`. `prepare_parallel_baselines.sh` wraps the
supported two-device contract and runs the same prep sequentially for both AVDs.

When to use: before the first eval run on a new emulator, or when snapshots are
corrupted. For local parallel eval, both AVDs must satisfy this baseline
contract before you use `./scripts/eval_parallel.sh`.

## Task Overrides

Per-task configuration overrides in `eval/config/default.yaml`:

```yaml
bridge:
  task_overrides:
    BrowserDraw: { perception_mode: hybrid }
    ExpenseAddMultipleFromGallery: { perception_mode: hybrid }
```

Semantics: prefix matching on task name (longest prefix wins). Any `BridgeConfig`
field can be overridden: `perception_mode`, `max_turns`, `excluded_tools`, etc.

By default eval excludes `remember_experience` and clears the app's persistent
memory directory before each task launch (`clear_memory_before_task: true`), so
cross-task long-term memory cannot leak into results.

See `resolve_task_bridge_config()` in `runner_execution.py`.

## Setup-Only Mode

Inspect task initialization without running the agent:

```bash
# Initialize a task
eval/.venv/bin/python eval/aw_bridge/setup_task_only.py --task FilesMoveFile

# Initialize and immediately tear down
eval/.venv/bin/python eval/aw_bridge/setup_task_only.py --task FilesMoveFile --teardown
```

Options: `--instance-index`, `--n-task-combinations`, `--task-random-seed`, `--adb-serial`.

Output: JSON with task_name, goal, params, initialized status.

## Preflight Error System

The runner uses typed errors (`PreflightError` with `PreflightErrorCode`) for
structured error handling and automatic recovery:

| Error Code | Meaning | Recovery |
|------------|---------|----------|
| `MISSING_TASK_PACKAGES` | Required app packages not installed | Auto-retry with `perform_emulator_setup=true` |
| `SNAPSHOTS_MISSING` | Baseline snapshots unavailable | Auto-retry with emulator setup |
| `PRECHECK_FAILED` | Generic preflight check failure | No automatic recovery |

See `should_run_emulator_setup_retry()` in `runner_preflight.py`.

## Running in Virtual-Display Mode

By default evals run in **accessibility** mode (agent operates on the real
emulator screen).  To run on a **Shizuku virtual display** instead:

### One-time device setup

1. Install Shizuku on the emulator (the runner can do this automatically if
   `bridge.shizuku_apk_path` is set -- see below).
2. Open the Shizuku app on the emulator and start the server via the
   "Start via ADB" flow.
3. Launch the ClosePaw app, which will trigger the Shizuku permission
   dialog.  Tap **Allow**.

This grant persists across emulator reboots (as long as you don't wipe data).
You only need to do it once per emulator image.

### Running

```bash
# CLI override (no config change needed):
eval/.venv/bin/python eval/aw_bridge/runner.py \
  --platform-mode virtual_display \
  --tasks-file eval/config/aw_subset_smoke.txt

# Or set it in eval/config/default.yaml:
#   bridge:
#     platform_mode: virtual_display
```

The runner will automatically start the Shizuku server if it isn't already
running.  If Shizuku is not installed on the device and you want auto-install,
set `shizuku_apk_path` in your config:

```yaml
bridge:
  platform_mode: virtual_display
  shizuku_apk_path: eval/tools/shizuku.apk   # bundled v13.6.0
```

### Troubleshooting

If the agent silently falls back to accessibility mode, check logcat for:

- `Shizuku is not available` -- server not running; the runner should
  auto-start it, but verify with `adb shell pidof shizuku_server`.
- `Shizuku permission not granted` -- re-do the one-time grant step above.

## Notes

- Bridge status (operational) is tracked separately from scripted success (benchmark truth).
- By default, only infra failures are retried (`retry_infra_failures` in config, default: 1).
- `auto_install_missing_task_apps` (default: true) attempts to install missing app APKs automatically.
- `skip_unavailable_tasks` (default: true) skips tasks whose required packages aren't installed.
- `runner.perform_bridge_setup` is an internal config knob. Serial runs leave it
  `true`; parallel workers set it to `false` so the supervisor can build once
  and install once per device.

## Frozen System Time (freeze_datetime)

AndroidWorld sets the emulator system time to a fixed past date (Oct 2023) for reproducible task scoring. This is controlled by `freeze_datetime` in `eval/config/default.yaml`.

**Why it matters**: Tasks involving dates (calendar events, expense entries) are scored by checking database rows with expected timestamps. If the system time is "now" instead of the fixed date, timestamps won't match and tasks score 0 even when the agent completes them correctly.

**SSL issue**: Freezing the clock to a past date causes HTTPS certificate validation to fail -- certificates appear "not yet valid" from the perspective of the emulator's system time. The app handles this via `InsecureSslConfig`, which disables certificate date validation in debug builds (`BuildConfig.DEBUG`). This allows LLM API calls to succeed even with a frozen past system time.

**Bridge compatibility**: The eval bridge (`native_agent_bridge.py`) previously had a `_ensure_device_time_is_sane()` workaround that re-enabled NTP sync before each task, which undid the frozen time. This was removed since `InsecureSslConfig` handles SSL directly.

**Configuration**:
```yaml
android_world:
  freeze_datetime: true  # Recommended for accurate scoring
```

See `doc/main/infra/llm.md` (InsecureSslConfig section) for implementation details.

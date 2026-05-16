# ClosePaw Development Scripts

> **See also:** [Development Guide](../doc/dev/development.md) for overall development workflow.

This document provides detailed reference for all development scripts.

## Workflow

```
After code changes:
    ./scripts/setup.sh                               # Build, install, setup permissions

Run agent:
    ./scripts/debug-run.sh "goal"                    # Run with OpenAI (default)
    ./scripts/debug-run.sh --local "goal"            # Run with local model

Perception mode:
    ./scripts/debug-run.sh --accessibility-only "goal"
    ./scripts/debug-run.sh --screenshot-only "goal"
    ./scripts/debug-run.sh --hybrid "goal"

Eval:
    ./scripts/prepare_parallel_baselines.sh
    ./scripts/eval_parallel.sh eval/config/aw_subset_smoke.txt

View logs:
    ./scripts/logs.sh                                # All agent logs
    ./scripts/logs.sh orch                           # Orchestration logs
```

## Scripts

### `setup.sh` - Build & Deploy

Run this after code changes to deploy a new version.

```bash
./scripts/setup.sh                    # For OpenAI backend (requires API key)
LLM_BACKEND=local ./scripts/setup.sh  # For local LLM backend (no API key needed)
```

What it does:
- Build APK
- Install APK (replacement install with `-r`, preserves data)
- Grant Overlay permission
- Enable Accessibility service
- Launch app

Environment variables:
- `LLM_BACKEND`: `openai` (default) or `local` - skips API key check when set to `local`

### `debug-run.sh` - Run Agent with Debug Capture

Run the agent with full debug output: screenshots at each turn, trace artifacts, and comprehensive logs. Pressing Ctrl+C will gracefully stop the agent.

```bash
# OpenAI backend (default)
./scripts/debug-run.sh "Open Settings"
./scripts/debug-run.sh "Open Chrome"

# Local LLM backend
./scripts/debug-run.sh --local "Open Chrome"

# Perception mode
./scripts/debug-run.sh --accessibility-only "Open Chrome"  # A11y only
./scripts/debug-run.sh --screenshot-only "Open Chrome"     # Screenshot only
./scripts/debug-run.sh --hybrid "Open Chrome"              # A11y + screenshot
```

Options:
- `--local`, `-l`: Use local LLM backend instead of OpenAI
- `--accessibility-only`, `--a11y-only`: Force accessibility-only perception
- `--screenshot-only`: Force screenshot-only perception
- `--hybrid`: Force hybrid perception
- `--perception <mode>`: Set perception mode explicitly (`accessibility_only`, `screenshot_only`, `hybrid`)
- `--main-model <name>`: Override main model (key in `llm_models.json`)

Environment variables:
- `LLM_BACKEND`: `openai` (default) or `local`
- `PERCEPTION_MODE`: `accessibility_only` (default), `screenshot_only`, or `hybrid`
- `MAIN_MODEL`: same as the flag above
- `DEBUG_MAX_TURNS`: Max turn-start events to capture (default: 80)

Output in `debug-output/run_<timestamp>/`:
- `turn_N.png` - Screenshot at each turn
- `turn_N_log.txt` - Log excerpt per turn
- `agent.log` - Full agent log
- `system.log` - System-level log
- `llm_screens/` - LLM screenshots (if debug mode)
- `trace/` - JSONL trace + replay artifacts

See **[Visual Debug Guide](../doc/dev/visual_debug_guide.md)** for detailed debugging workflow.

### `logs.sh` - View Filtered Logs

Stream filtered logcat output for quick log viewing.

```bash
./scripts/logs.sh                      # All agent logs
./scripts/logs.sh orch                 # Orchestration logs only
./scripts/logs.sh llm                  # LLM/API call logs only
./scripts/logs.sh session              # Session lifecycle logs
./scripts/logs.sh action               # Action execution logs
./scripts/logs.sh all                  # Unfiltered all logs
```

### `prepare_baseline.sh` - Create a Clean Eval Baseline

Use this before the first eval run on a new AndroidWorld AVD, or when app
snapshots are corrupted.

```bash
./scripts/prepare_parallel_baselines.sh
./scripts/prepare_baseline.sh --avd AndroidWorldAvd --console-port 5554 --grpc-port 8554 --adb-serial emulator-5554
./scripts/prepare_baseline.sh --avd AndroidWorldAvd2 --console-port 5556 --grpc-port 8556 --adb-serial emulator-5556
```

What it does:
- Kills the existing emulator on that serial
- Starts the AVD with `-wipe-data`
- Runs `eval/aw_bridge/prepare_baseline.py` to install benchmark apps and create snapshots
- Handles empty passthrough args safely under `set -u`

### `prepare_parallel_baselines.sh` - Prepare the Supported Dual-AVD Pair

Use this as the standard one-time setup before local parallel eval.

```bash
./scripts/prepare_parallel_baselines.sh
```

What it does:
- Runs `prepare_baseline.sh` for `AndroidWorldAvd` on `emulator-5554` / gRPC `8554`
- Runs `prepare_baseline.sh` for `AndroidWorldAvd2` on `emulator-5556` / gRPC `8556`
- Leaves both emulators baseline-prepared for `./scripts/eval_parallel.sh`

### `eval_parallel.sh` - Run Local 2-Device Parallel Eval

Use this for the supported local parallel path after both AVDs have already been
baseline-prepared.

```bash
./scripts/eval_parallel.sh eval/config/aw_subset_smoke.txt
./scripts/eval_parallel.sh --tasks "BrowserDraw,FilesMoveFile"
```

What it does:
- Starts or reuses `AndroidWorldAvd` on `emulator-5554` / gRPC `8554`
- Starts or reuses `AndroidWorldAvd2` on `emulator-5556` / gRPC `8556`
- Verifies both AVDs exist and refuses conflicting same-AVD reuse across the two ports
- Launches `eval/aw_bridge/parallel_runner.py`
- Writes canonical results to `eval/results/<timestamp>/` with shard debug data under `parallel/`

## Configuration

### API Key (OpenAI Backend)

Create `.env` file in project root:

```
OPENAI_API_KEY=sk-proj-your-key-here
```

### LLM Backend Selection

Add to `.env` to persist backend selection:

```
LLM_BACKEND=local    # or "openai" (default)
```

Or use inline: `LLM_BACKEND=local ./scripts/debug-run.sh "goal"`

## Troubleshooting

### "App not installed"

Run `./scripts/setup.sh` to build and install.

### "Accessibility service not enabled"

Run `./scripts/setup.sh` - it automatically enables accessibility.
If it fails, enable manually:
1. Settings > Accessibility > Downloaded apps
2. Find "ClosePaw"
3. Toggle ON

### "No device detected"

1. Check USB connection
2. Ensure USB debugging is enabled
3. Run `adb devices` to verify
4. Try `adb kill-server && adb start-server`

### Agent not responding

1. Run `./scripts/setup.sh` to reinstall and reconfigure
2. View logs: `./scripts/logs.sh`

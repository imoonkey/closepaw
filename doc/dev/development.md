# Development Guide

> Last updated: 2026-02-20 (commit: 2493be6)

This guide covers the development workflow for Android Agent - building, testing, and debugging.

## Prerequisites

- Android device or emulator with USB debugging enabled
- ADB installed and accessible
- OpenAI API key (for cloud backend) OR compatible Android device (for local LLM)

## Quick Start

### Using OpenAI (Cloud)

```bash
# 1. Setup API key
echo 'OPENAI_API_KEY=sk-your-key' > .env

# 2. Build and deploy
./scripts/setup.sh

# 3. Run a test
./scripts/debug-run.sh --basic "Open Settings"
```

### Using Local LLM (On-Device)

```bash
# 1. Build and deploy (no API key needed)
LLM_BACKEND=local ./scripts/setup.sh

# 2. Run a test with local model
./scripts/debug-run.sh --local "Open Settings"
```

The local backend uses LiquidAI's Leap SDK to run LFM models on-device. The model is downloaded automatically on first use and is a relatively large download.

## Development Cycle

The typical development loop:

```
Code change → Build & Deploy → Unit Tests → Device Test → View Logs → Debug
```

### 1. Build & Deploy

After any code change, run setup to build, install, and configure permissions:

```bash
./scripts/setup.sh
```

This handles everything: build APK, install, grant permissions, enable accessibility, launch app.

### 2. Unit Tests (JVM)

Run the local JVM test suite after code changes:

```bash
./gradlew test
```

For faster iteration, run a single test class:

```bash
./gradlew test --tests "com.moonkey.androidagent.history.HistoryManagerTest"
```

### 3. Device Test

Run the agent with a goal. `debug-run.sh` captures screenshots at each turn, records trace artifacts, and saves comprehensive logs for post-run analysis. Press Ctrl+C to gracefully stop the agent.

```bash
./scripts/debug-run.sh "Open Settings"                        # Default OpenAI backend
./scripts/debug-run.sh --local "Open Settings"                # Use local LLM
./scripts/debug-run.sh --basic "Open Chrome"                  # Standalone execution mode
./scripts/debug-run.sh --pro "Open Chrome"                    # Planner+executor mode
./scripts/debug-run.sh --main-model gpt-5.2 --executor-model glm-4.7 "Open Chrome" # Custom models
./scripts/debug-run.sh --perception accessibility_only "Open Chrome" # Explicit perception mode
./scripts/debug-run.sh --accessibility-only "Open Chrome"     # A11y only
./scripts/debug-run.sh --screenshot-only "Open Chrome"        # Screenshot only
./scripts/debug-run.sh --hybrid "Open Chrome"                 # A11y + screenshot
./scripts/debug-run.sh --virtual-display "Open Chrome"        # Run on Shizuku virtual display
```

Output in `debug-output/run_<timestamp>/`:
- `turn_N.png` - Screenshot at each turn
- `turn_N_log.txt` - Log excerpt per turn
- `agent.log` - Full agent log
- `trace/` - JSONL trace + replay artifacts

See [Visual Debug Guide](visual_debug_guide.md) for systematic debugging workflow.

### 3.1 Direct Action Debug (Execution Layer)

Use `action-test.sh` to isolate action execution outside the full agent loop:

```bash
./scripts/action-test.sh click --x 540 --y 1200
./scripts/action-test.sh scroll --direction down
./scripts/action-test.sh long_press --x 540 --y 800 --duration 1500
./scripts/action-test.sh click --x 540 --y 1200 --compare   # ADB baseline vs a11y path
```

This is useful when `mobile_action` reports success but UI does not change.

### 4. View Logs

Monitor agent behavior through filtered logs:

```bash
./scripts/logs.sh                # All agent logs
./scripts/logs.sh orch           # Orchestration flow
./scripts/logs.sh llm            # LLM API calls
./scripts/logs.sh action         # Action execution
```

## Configuration

### API Key (OpenAI Backend)

Create `.env` in project root:

```
OPENAI_API_KEY=sk-proj-your-key-here
```

### LLM Backend Selection

You can choose between cloud (OpenAI) and local (on-device) LLM backends:

**Via environment variable:**
```bash
# Set in .env for persistence
echo 'LLM_BACKEND=local' >> .env

# Or use inline for one-off runs
LLM_BACKEND=local ./scripts/debug-run.sh "Open Settings"
```

**Via command-line flag:**
```bash
./scripts/debug-run.sh --local "Open Settings"
```

| Backend | Pros | Cons |
|---------|------|------|
| `openai` | Better quality, tool-calling | Requires API key, network latency |
| `local` | Offline, no cost, fast | Lower quality, ~800MB model download |

### Agent Execution Mode

Select runtime orchestration mode with either flags or env var:

```bash
# one-off
./scripts/debug-run.sh --basic "Open Settings"
./scripts/debug-run.sh --pro "Check notifications"

# persistent default
echo 'AGENT_MODE=basic' >> .env
```

| Mode | Behavior |
|------|----------|
| `pro` (default) | Planner main agent + delegated executor |
| `basic` | Standalone main agent executes UI actions directly |

### Perception Mode

```bash
# one-off
./scripts/debug-run.sh --accessibility-only "Open Settings"
./scripts/debug-run.sh --screenshot-only "Open Settings"
./scripts/debug-run.sh --hybrid "Open Settings"
./scripts/debug-run.sh --perception screenshot_only "Open Settings"

# persistent default
echo 'PERCEPTION_MODE=hybrid' >> .env
```

| Mode | Behavior |
|------|----------|
| `accessibility_only` (default) | Accessibility tree only |
| `hybrid` | Accessibility tree + screenshot |
| `screenshot_only` | Screenshot only |

### Platform Mode

Control which platform implementation is used:

```bash
# one-off
./scripts/debug-run.sh --virtual-display "Open Settings"
./scripts/debug-run.sh --vd "Open Settings"

# persistent default
echo 'PLATFORM_MODE=virtual_display' >> .env
```

| Mode | Behavior |
|------|----------|
| `accessibility` (default) | Standard operation on main display |
| `virtual_display` | Runs agent on a private virtual display (requires Shizuku) |

## Troubleshooting

| Issue | Solution |
|-------|----------|
| "App not installed" | Run `./scripts/setup.sh` |
| "Accessibility service not enabled" | Run `./scripts/setup.sh`, or enable manually in Settings |
| "No device detected" | Check USB debugging, run `adb devices` |
| Agent not responding | Run `./scripts/setup.sh`, then check `./scripts/logs.sh` |

## Detailed Documentation

- **[Scripts README](../../scripts/README.md)** - Complete script reference and options
- **[Visual Debug Guide](visual_debug_guide.md)** - Step-by-step debugging methodology

## Evaluation Harness (AndroidWorld Bridge)

`eval/` contains the Python harness for batch evaluation and trace summary generation.

### Quick Start

```bash
# Run a smoke subset
eval/.venv/bin/python eval/aw_bridge/runner.py --tasks-file eval/config/aw_subset_smoke.txt

# Run specific tasks
eval/.venv/bin/python eval/aw_bridge/runner.py --tasks "TaskA,TaskB"

# Virtual-display eval run
eval/.venv/bin/python eval/aw_bridge/runner.py --platform-mode virtual_display --tasks-file eval/config/aw_subset_smoke.txt

# Setup-only for one AndroidWorld task (no agent run)
eval/.venv/bin/python eval/aw_bridge/setup_task_only.py --task FilesMoveFile
```

Use `eval/.venv/bin/python` for eval commands to avoid dependency drift with the app environment.

### Key CLI Arguments

| Flag | Default | Description |
|------|---------|-------------|
| `--tasks` / `--tasks-file` | none | Tasks to run (comma-separated or file) |
| `--snapshot-policy` | `auto_repair` | `strict` / `auto_repair` / `best_effort` / `off` |
| `--platform-mode` | `accessibility` | `accessibility` or `virtual_display` |
| `--adb-serial` | auto-detected | Target device serial |
| `--config` | `eval/config/default.yaml` | Config file path |

### Snapshot Policy

Controls baseline snapshot management: `strict` (fail on missing), `auto_repair` (create missing, default), `best_effort` (warn and continue), `off` (skip checks).

### Baseline Preparation

```bash
scripts/prepare_baseline.sh --avd AndroidWorldAvd
```

Wipes emulator, installs apps, generates snapshots. Run before first eval or when snapshots are corrupted.

### Task Overrides

Per-task config overrides in `eval/config/default.yaml` under `bridge.task_overrides`.
Prefix matching on task name; any `BridgeConfig` field can be overridden:

```yaml
bridge:
  task_overrides:
    BrowserDraw: { perception_mode: hybrid }
    ExpenseAddMultipleFromGallery: { perception_mode: hybrid }
```

> See: `eval/README.md` for full reference, `doc/main/eval/eval.md` for architecture

## Inspection Tool (Replay v2)

The Inspection Tool is a web-based trace viewer for debugging agent sessions. It provides a step-by-step replay of the agent's execution, including screenshots, accessibility trees, and tool calls.

### Running the Tool

```bash
./inspection_tool/serve.sh
```

Then open [http://localhost:8000](http://localhost:8000).

### Features

- **Step-by-step Replay**: Navigate through each turn of the conversation.
- **Visual State**: View screenshots and accessibility trees side-by-side.
- **Tool Calls**: See exact tool parameters and outputs.
- **Performance Stats**: Analyze token usage and latency (using `a11y_token_stats.py`).
- **Auto-Compilation**: Automatically compiles raw traces into a viewable format.

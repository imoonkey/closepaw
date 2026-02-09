# Android Agent Development Scripts

> **See also:** [Development Guide](../doc/dev/development.md) for overall development workflow.

This document provides detailed reference for all development scripts.

## Workflow

```
After code changes:
    ./scripts/setup.sh                               # Build, install, setup permissions

Run agent:
    ./scripts/debug-run.sh "goal"                    # Run with OpenAI (default)
    ./scripts/debug-run.sh --local "goal"            # Run with local model
    ./scripts/debug-run.sh --basic "goal"            # Standalone mode
    ./scripts/debug-run.sh --pro "goal"              # Planner+executor mode

Perception mode:
    ./scripts/debug-run.sh --accessibility-only "goal"
    ./scripts/debug-run.sh --screenshot-only "goal"
    ./scripts/debug-run.sh --hybrid "goal"

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

# Execution mode
./scripts/debug-run.sh --basic "Open Chrome"       # Standalone mode
./scripts/debug-run.sh --pro "Open Chrome"         # Planner+executor mode

# Perception mode
./scripts/debug-run.sh --accessibility-only "Open Chrome"  # A11y only
./scripts/debug-run.sh --screenshot-only "Open Chrome"     # Screenshot only
./scripts/debug-run.sh --hybrid "Open Chrome"              # A11y + screenshot
```

Options:
- `--local`, `-l`: Use local LLM backend instead of OpenAI
- `--basic`: Force basic standalone execution mode
- `--pro`: Force pro planner+executor mode
- `--accessibility-only`, `--a11y-only`: Force accessibility-only perception
- `--screenshot-only`: Force screenshot-only perception
- `--hybrid`: Force hybrid perception
- `--perception <mode>`: Set perception mode explicitly (`accessibility_only`, `screenshot_only`, `hybrid`)

Environment variables:
- `LLM_BACKEND`: `openai` (default) or `local`
- `AGENT_MODE`: `pro` (default) or `basic`
- `PERCEPTION_MODE`: `accessibility_only` (default), `screenshot_only`, or `hybrid`
- `DEBUG_MAX_TURNS`: Max turn-start events to capture (default: 80)

Output in `debug-output/run_<timestamp>/`:
- `turn_N.png` - Screenshot at each turn
- `turn_N_log.txt` - Log excerpt per turn
- `agent.log` - Full agent log
- `system.log` - System-level log
- `llm_screens/` - LLM screenshots (if debug mode)
- `trace/` - JSONL trace + replay artifacts

See **[agent_process_visual_debug.md](./agent_process_visual_debug.md)** for detailed debugging workflow.

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
2. Find "Android Agent"
3. Toggle ON

### "No device detected"

1. Check USB connection
2. Ensure USB debugging is enabled
3. Run `adb devices` to verify
4. Try `adb kill-server && adb start-server`

### Agent not responding

1. Run `./scripts/setup.sh` to reinstall and reconfigure
2. View logs: `./scripts/logs.sh`

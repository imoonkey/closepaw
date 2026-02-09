# Android Agent Development Scripts

> **See also:** [Development Guide](../doc/dev/development.md) for overall development workflow.

This document provides detailed reference for all development scripts.

## Workflow

```
After code changes:
    ./scripts/setup.sh                      # Build, install, setup permissions

Run tests (OpenAI backend):
    ./scripts/dev.sh run                    # Run agent test
    ./scripts/dev.sh logs                   # View logs

Run tests (Local LLM backend):
    ./scripts/dev.sh run --local            # Run with local model
    LLM_BACKEND=local ./scripts/dev.sh run  # Same via env var

Execution mode:
    ./scripts/dev.sh run --basic            # Standalone mode
    ./scripts/dev.sh run --pro              # Planner+executor mode
    AGENT_MODE=basic ./scripts/dev.sh run   # Same via env var

Perception mode:
    ./scripts/dev.sh run --accessibility-only
    ./scripts/dev.sh run --screenshot-only
    ./scripts/dev.sh run --hybrid
    PERCEPTION_MODE=hybrid ./scripts/dev.sh run

Debug issues:
    ./scripts/debug-run.sh "goal"           # With OpenAI
    ./scripts/debug-run.sh --local "goal"   # With local LLM
    ./scripts/debug-run.sh --basic "goal"   # Standalone mode
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

### `dev.sh` - Run & Test

Run agent tests and view logs. Assumes app is already installed via `setup.sh`.

```bash
# OpenAI backend (default)
./scripts/dev.sh run                       # Run with default goal ("Open Settings")
./scripts/dev.sh run "Open Chrome"         # Run with custom goal

# Local LLM backend
./scripts/dev.sh run --local               # Run with local model
./scripts/dev.sh run --local "Open Chrome" # Custom goal with local model
LLM_BACKEND=local ./scripts/dev.sh run     # Same via env var

# Execution mode
./scripts/dev.sh run --basic               # Standalone mode
./scripts/dev.sh run --pro                 # Planner+executor mode
AGENT_MODE=basic ./scripts/dev.sh run      # Same via env var

# Perception mode
./scripts/dev.sh run --accessibility-only  # A11y only
./scripts/dev.sh run --screenshot-only     # Screenshot only
./scripts/dev.sh run --hybrid              # A11y + screenshot
PERCEPTION_MODE=hybrid ./scripts/dev.sh run

# Logs and status
./scripts/dev.sh logs                      # View all agent logs
./scripts/dev.sh logs orch                 # Orchestration logs only
./scripts/dev.sh logs llm                  # LLM call logs only
./scripts/dev.sh status                    # Check device status
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

Log filter options:
| filter | content |
|--------|---------|
| (default) | All agent-related logs |
| `orch` | Orchestration logs |
| `llm` | LLM/API call logs |
| `session` | Session lifecycle logs |
| `action` | Action execution logs |
| `all` | Unfiltered all logs |

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

Or use inline: `LLM_BACKEND=local ./scripts/dev.sh run`

### `debug-run.sh` - Visual Debugging

Capture screenshots at each turn for debugging agent behavior.

```bash
./scripts/debug-run.sh "Open Chrome"              # With OpenAI
./scripts/debug-run.sh --local "Open Chrome"      # With local LLM
./scripts/debug-run.sh --basic "Open Chrome"      # Standalone mode
./scripts/debug-run.sh --pro "Open Chrome"        # Planner+executor mode
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

Output in `debug-output/`:
- `turn_N.png` - Screenshot at each turn
- `turn_N_log.txt` - Log excerpt per turn
- `agent.log` - Full agent log

See **[agent_process_visual_debug.md](./agent_process_visual_debug.md)** for detailed debugging workflow.

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

1. Run `./scripts/dev.sh status` to check status
2. Run `./scripts/setup.sh` to reinstall and reconfigure
3. View logs: `./scripts/dev.sh logs`

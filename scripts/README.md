# Android Agent Development Scripts

> **See also:** [Development Guide](../doc/dev/development.md) for overall development workflow.

This document provides detailed reference for all development scripts.

## Workflow

```
After code changes:
    ./scripts/setup.sh              # Build, install, setup permissions

Run tests:
    ./scripts/dev.sh run            # Run agent test
    ./scripts/dev.sh logs           # View logs

Debug issues:
    ./scripts/debug-run.sh "goal"   # Run with screenshot capture per turn
```

## Scripts

### `setup.sh` - Build & Deploy

Run this after code changes to deploy a new version.

```bash
./scripts/setup.sh
```

What it does:
- Build APK
- Install APK (replacement install with `-r`, preserves data)
- Grant Overlay permission
- Enable Accessibility service
- Launch app

### `dev.sh` - Run & Test

Run agent tests and view logs. Assumes app is already installed via `setup.sh`.

```bash
./scripts/dev.sh run                # Run with default goal ("Open Settings")
./scripts/dev.sh run "Open Chrome"  # Run with custom goal
./scripts/dev.sh logs               # View all agent logs
./scripts/dev.sh logs orch          # Orchestration logs only
./scripts/dev.sh logs llm           # LLM call logs only
./scripts/dev.sh status             # Check device status
```

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

### API Key

Create `.env` file in project root:

```
OPENAI_API_KEY=sk-proj-your-key-here
```

### `debug-run.sh` - Visual Debugging

Capture screenshots at each turn for debugging agent behavior.

```bash
./scripts/debug-run.sh "Open Chrome"
```

Output in `debug-output/`:
- `turn_N.png` - Screenshot at each turn
- `turn_N_log.txt` - Log excerpt per turn
- `orchestration.log` - Full orchestration log

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

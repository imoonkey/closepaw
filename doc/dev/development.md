# Development Guide

This guide covers the development workflow for Android Agent - building, testing, and debugging.

## Prerequisites

- Android device or emulator with USB debugging enabled
- ADB installed and accessible
- OpenAI API key

## Quick Start

```bash
# 1. Setup API key
echo 'OPENAI_API_KEY=sk-your-key' > .env

# 2. Build and deploy
./scripts/setup.sh

# 3. Run a test
./scripts/dev.sh run "Open Settings"
```

## Development Cycle

The typical development loop:

```
Code change → Build & Deploy → Test → View Logs → Debug (if needed)
```

### 1. Build & Deploy

After any code change, run setup to build, install, and configure permissions:

```bash
./scripts/setup.sh
```

This handles everything: build APK, install, grant permissions, enable accessibility, launch app.

### 2. Test

Run the agent with a goal:

```bash
./scripts/dev.sh run                    # Default: "Open Settings"
./scripts/dev.sh run "Open Chrome"      # Custom goal
```

### 3. View Logs

Monitor agent behavior through filtered logs:

```bash
./scripts/dev.sh logs           # All agent logs
./scripts/dev.sh logs orch      # Orchestration flow
./scripts/dev.sh logs llm       # LLM API calls
./scripts/dev.sh logs action    # Action execution
```

### 4. Debug

For deeper investigation, use visual debugging to capture screenshots at each turn:

```bash
./scripts/debug-run.sh "Open Chrome"
```

Output in `debug-output/`:
- `turn_N.png` - Screenshot at each turn
- `turn_N_log.txt` - Log excerpt per turn  
- `agent.log` - Full agent log

See [Visual Debugging Guide](../../scripts/agent_process_visual_debug.md) for systematic debugging workflow.

## Configuration

### API Key

Create `.env` in project root:

```
OPENAI_API_KEY=sk-proj-your-key-here
```

### Device Status

Check if everything is configured correctly:

```bash
./scripts/dev.sh status
```

## Troubleshooting

| Issue | Solution |
|-------|----------|
| "App not installed" | Run `./scripts/setup.sh` |
| "Accessibility service not enabled" | Run `./scripts/setup.sh`, or enable manually in Settings |
| "No device detected" | Check USB debugging, run `adb devices` |
| Agent not responding | Run `./scripts/dev.sh status`, then `./scripts/setup.sh` |

## Detailed Documentation

- **[Scripts README](../../scripts/README.md)** - Complete script reference and options
- **[Visual Debugging Guide](../../scripts/agent_process_visual_debug.md)** - Step-by-step debugging methodology

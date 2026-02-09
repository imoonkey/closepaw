# Development Guide

> Last updated: 2026-02-09 (commit: 20b72144e48182d50229fada5ec9c491ffd074f0)

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
./scripts/dev.sh run "Open Settings"
```

### Using Local LLM (On-Device)

```bash
# 1. Build and deploy (no API key needed)
LLM_BACKEND=local ./scripts/setup.sh

# 2. Run a test with local model
./scripts/dev.sh run --local "Open Settings"
```

The local backend uses LiquidAI's Leap SDK to run LFM models on-device. The model is downloaded automatically on first use and is a relatively large download.

## Development Cycle

The typical development loop:

```
Code change → Build & Deploy → Unit Tests → Device Test → View Logs → Debug (if needed)
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

Run the agent with a goal:

```bash
./scripts/dev.sh run                    # Default: "Open Settings"
./scripts/dev.sh run "Open Chrome"      # Custom goal
./scripts/dev.sh run --local "Open Settings"  # Use local LLM
./scripts/dev.sh run --basic "Open Chrome"    # Standalone execution mode
./scripts/dev.sh run --pro "Open Chrome"      # Planner+executor mode
./scripts/dev.sh run --accessibility-only "Open Chrome"  # A11y only
./scripts/dev.sh run --screenshot-only "Open Chrome"     # Screenshot only
./scripts/dev.sh run --hybrid "Open Chrome"              # A11y + screenshot
```

### 4. View Logs

Monitor agent behavior through filtered logs:

```bash
./scripts/dev.sh logs           # All agent logs
./scripts/dev.sh logs orch      # Orchestration flow
./scripts/dev.sh logs llm       # LLM API calls
./scripts/dev.sh logs action    # Action execution
```

### 5. Debug

For deeper investigation, use visual debugging to capture screenshots at each turn:

```bash
./scripts/debug-run.sh "Open Chrome"              # With OpenAI
./scripts/debug-run.sh --local "Open Chrome"      # With local LLM
./scripts/debug-run.sh --basic "Open Chrome"      # Standalone mode trace
./scripts/debug-run.sh --pro "Open Chrome"        # Planner+executor trace
./scripts/debug-run.sh --accessibility-only "Open Chrome"  # A11y only
./scripts/debug-run.sh --screenshot-only "Open Chrome"     # Screenshot only
./scripts/debug-run.sh --hybrid "Open Chrome"              # A11y + screenshot
```

Output in `debug-output/`:
- `turn_N.png` - Screenshot at each turn
- `turn_N_log.txt` - Log excerpt per turn  
- `agent.log` - Full agent log

See [Visual Debugging Guide](../../scripts/agent_process_visual_debug.md) for systematic debugging workflow.

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
LLM_BACKEND=local ./scripts/dev.sh run
```

**Via command-line flag:**
```bash
./scripts/dev.sh run --local "Open Settings"
./scripts/debug-run.sh --local "Open Chrome"
```

| Backend | Pros | Cons |
|---------|------|------|
| `openai` | Better quality, tool-calling | Requires API key, network latency |
| `local` | Offline, no cost, fast | Lower quality, ~800MB model download |

### Agent Execution Mode

Select runtime orchestration mode with either flags or env var:

```bash
# one-off
./scripts/dev.sh run --basic "Open Settings"
./scripts/debug-run.sh --pro "Check notifications"

# persistent default
echo 'AGENT_MODE=basic' >> .env
```

| Mode | Behavior |
|------|----------|
| `pro` (default) | Planner main agent + delegated executor |
| `basic` | Standalone main agent executes UI actions directly |

### Perception Mode

Both `dev.sh run` and `debug-run.sh` support explicit perception mode switching:

```bash
# one-off
./scripts/dev.sh run --accessibility-only "Open Settings"
./scripts/dev.sh run --screenshot-only "Open Settings"
./scripts/dev.sh run --hybrid "Open Settings"
./scripts/dev.sh run --perception screenshot_only "Open Settings"

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

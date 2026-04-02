# Development Guide

> Last updated: 2026-03-06 (uncommitted)

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

### Prompt Ownership

When tuning the agent's cognition, edit the narrowest owner:

- Core cross-tool behavior: `agent/definition/StandaloneAgentDef.kt` and `PlannerAgentDef.kt`
- Tool-local semantics: tool `description` strings in `tool/impl/*.kt`
- App-specific guidance: `app/src/main/assets/app_skills/<package>/SKILL.md`

The active app skill is loaded fresh each turn from the foreground package and inserted into the
prompt between Working Memory and Observation.

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

### Provider Base URL Override (cproxy + Tailscale)

The app sets `usesCleartextTraffic="false"`, so all LLM traffic must go over HTTPS. For OPENAI-provider models routed through [cproxy](https://github.com/user/cproxy) (a local Copilot-backed proxy), use Tailscale Serve to expose cproxy over HTTPS:

```bash
# 1. cproxy listens on localhost:18080 (see ~/workspace/cproxy/)

# 2. Tailscale Serve exposes it as HTTPS on port 8741
tailscale serve --bg --https=8741 http://127.0.0.1:18080

# 3. Set the base URL in .env
OPENAI_BASE_URL=https://laptop.tail6bd948.ts.net:8741/v1
```

Port allocation on `laptop.tail6bd948.ts.net`:

| Port | Target | Purpose |
|------|--------|---------|
| 443 (default) | `127.0.0.1:5173` | workflow frontend |
| 8741 | `127.0.0.1:18080` | cproxy (LLM proxy) |

The URL is passed as an intent extra and applied at session bootstrap via `ModelCatalog.withBaseUrlOverrides()` — no changes to `llm_models.json` needed.

**Emulator note:** Emulators can't reach Tailscale. The debug build includes a `network_security_config.xml` that permits cleartext to `10.0.2.2`/`127.0.0.1`/`localhost` only. Set `OPENAI_BASE_URL=http://localhost:18080/v1` — the eval bridge auto-rewrites to `10.0.2.2`. Release builds block all cleartext.

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

## Evaluation Harness

-> See: `eval/README.md` for full reference, `doc/main/eval/eval.md` for architecture.

```bash
# Quick start
eval/.venv/bin/python eval/aw_bridge/runner.py --tasks-file eval/config/aw_subset_smoke.txt
eval/.venv/bin/python eval/aw_bridge/runner.py --tasks "TaskA,TaskB"

# Setup-only for one task (no agent run)
eval/.venv/bin/python eval/aw_bridge/setup_task_only.py --task FilesMoveFile
```

Use `eval/.venv/bin/python` for eval commands. Config override files are deep-merged on top of `eval/config/default.yaml`.

## Inspection Tool (Replay v2)

Web-based trace viewer: `./inspection_tool/serve.sh` → [http://localhost:8000](http://localhost:8000). Step-by-step replay with screenshots, a11y trees, tool calls, and token stats.

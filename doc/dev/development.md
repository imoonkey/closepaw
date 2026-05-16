# Development Guide

> Last updated: 2026-05-12 (release-prep wave 2: signing pipeline, R8 unblock, GitHub release workflow)

This guide covers the development workflow for ClosePaw - building, testing, and debugging.

## Debug vs Release APK — always debug unless shipping

All day-to-day work uses the **debug** APK. The **release** APK is only for shipping, release smoke tests, or APK-size / cold-start measurements. Do **not** drive eval, autotune, UX QA, or `debug-run.sh` against a release build.

| | Debug | Release |
|---|---|---|
| Build | `./gradlew assembleDebug` (seconds) | `./scripts/release-build.sh :app:assembleRelease` (~2 min) |
| Install | `scripts/setup.sh`, `adb install -r …` | Sign then `adb install`. `scripts/setup.sh` targets debug. |
| R8 / resource shrink | off | **on** (`isMinifyEnabled=true`, `isShrinkResources=true`) |
| APK size | ~96 MB | 140 MB (R8 enabled; large because Leap SDK + bcprov + tessdata native libs ship inside the APK) |
| `BuildConfig.DEBUG` | `true` → `LlmLogger.VERBOSE_LOGGING` prints full prompt/response; streaming clients build accumulators | `false` → verbose log off, accumulators skipped (see `perf-streaming-guard`) |
| `INSECURE_SSL_FOR_EVAL` | opt-in via `-PinsecureSslForEval=true` | forced `false` |
| Cleartext (`10.0.2.2`, `localhost`) | allowed for emulator via `network_security_config.xml` | blocked |
| Stack traces | non-obfuscated | obfuscated by R8 (use `app/build/outputs/mapping/release/mapping.txt` to deobfuscate) |

**When to build release:**
1. Release artifact / public distribution.
2. Validating R8 keep rules (`app/proguard-rules.pro`). After any SDK upgrade or reflection/AIDL touch, run `./scripts/release-build.sh :app:assembleRelease` and grep `app/build/outputs/mapping/release/mapping.txt` for any symbol expected to stay unobfuscated.
3. Measuring APK size, cold start, or dex method count.

### Signed release builds (`scripts/release-build.sh`)

`./gradlew assembleRelease` direct doesn't sign — `signingConfigs.create("release")` reads keystore env vars (`KEYSTORE_PATH/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`) and gracefully falls back to `null` when they're absent (so IDE syncs don't break). The wrapper script does the env setup:

- Keystore + password live at `~/secrets/closepaw/release.keystore` and `~/secrets/closepaw/release.keystore.password` (mode 600), outside the repo by design. Mirrored desktop ↔ laptop via Tailscale `scp` — both machines can sign independently.
- `scripts/release-build.sh` sources `~/.android-agent-env` (for `JAVA_HOME` / `ANDROID_HOME` in non-interactive shells), exports the signing env from those two files, then `exec ./gradlew "$@"`.
- New maintainer onboarding: ask the existing maintainer for the keystore + password files (or generate a fresh keystore for personal/dev work — see `projects/active/1_publish/2_release_build_claude.md`). `.gitignore` covers `*.keystore` `*.jks` `*.password`, but the canonical location is outside the repo to enforce the boundary.
- CI publishes via `.github/workflows/release.yml` (fires on `v*` tag push; reads `KEYSTORE_BASE64` + `KEYSTORE_PASSWORD` from GitHub Secrets).

**R8 keep-rule pitfalls already handled** (in `app/proguard-rules.pro` + `app/build.gradle.kts`):
- snakeyaml (direct dep, `SkillFrontmatterParser`) calls `java.beans.*` which Android lacks → `-dontwarn java.beans.**` + `-keep org.yaml.snakeyaml.**`.
- `bcprov-jdk18on:1.84` ships Java-25 multi-release bytecode that Kotlin 2.3.0's `produceReleaseComposeMapping` ASM can't parse → that whole optional pipeline (`produce/merge/reportReleaseComposeMappingErrors`) is disabled in `afterEvaluate`. Only debug-only stack-trace metadata is lost; APK functionality unaffected.
- R8 needs `-Xmx4096m` daemon heap (`gradle.properties`); 2 GB OOMs.

**Before publishing a release:** install the signed release APK with a real API key and run at least one full LLM tool-call end-to-end. R8 is the likely source of any `ClassNotFoundException` / `NoSuchMethodError`, and the `OpenAIResponseClient` / `ChatCompletionClient` streaming paths are the highest-risk zones. The `perf-qa-real-device` QA report explicitly calls this out as a follow-up.

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

> `debug-run.sh` now invokes `setup.sh` as a preflight on every run, so the typical loop is just `./scripts/debug-run.sh ...` — the preflight is idempotent and fast when the APK is up-to-date and permissions are already granted. The standalone `./scripts/setup.sh` invocation above is for first-install or when you want the explicit build/perms pass without firing an agent goal.

### 2. Unit Tests (JVM)

Run the local JVM test suite after code changes:

```bash
./gradlew test
```

For faster iteration, run a single test class:

```bash
./gradlew test --tests "ai.closepaw.history.HistoryManagerTest"
```

### 2b. Compose UI Tests (instrumented, on device/emulator)

Behavior-guard tests for app-owned UI live under `app/src/androidTest/kotlin/ai/closepaw/qa/`. They run on a connected device or emulator via `AndroidJUnitRunner` + Compose UI Test, with `animationsDisabled=true` to prevent flake.

```bash
adb devices                                                                    # confirm device attached
./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.package=ai.closepaw.qa
```

What is and isn't covered:

- `app/src/test/` — fast JVM unit tests (logic, state, formatting).
- `app/src/androidTest/kotlin/ai/closepaw/qa/` — Compose UI behavior guards across Chat, SmartCapsule, Settings (45 tests as of 2026-04-17). Layout is flat, files grouped by area (`ChatHeaderTest`, `CapsuleInputTest`, `SettingsLlmAuthTest`, ...). No Robot pattern, no annotations, no base classes.
- `eval/` — AndroidWorld-style agent benchmarks (separate Python harness, see `/autotune`).

Design rules: `projects/active/qa_test/final/cn/design_kiss.md`. Add new tests when adding behavior or fixing bugs — don't wait for bugs to grow guards.

Critical pitfall: **never use Kotlin built-in `assert(...)` for verdicts** in androidTest — it's a no-op without `-ea` and silently passes. Use `org.junit.Assert.assertTrue` / `assertEquals` or Compose's `onNode(...).assertExists()` / `assertCountEquals(...)`.

### 2c. Browser Runtime Tests

The browser CDP runtime has JVM coverage for transport, session lifecycle, policy, settings, and
tool behavior, plus the wireless-ADB self-pair stack (SPAKE2-25519 KAT vectors, ADB wire protocol,
relay stress harness) and the localhost-relay token gate. Run the full JVM suite after browser
changes:

```bash
./gradlew test
```

The hidden-WebView prelude test lives in `app/src/androidTest/kotlin/ai/closepaw/browser/script/`.
At minimum, compile the debug androidTest source set after CDP transport signature changes:

```bash
./gradlew :app:compileDebugAndroidTestKotlin
```

To run the on-device browser script host test directly:

```bash
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=ai.closepaw.browser.script.BrowserScriptRunnerInstrumentedTest
```

To exercise real `browser_script` from the app, enable **Settings → Permissions & Advanced →
Browser automation (experimental)**, keep Chrome installed and the DevTools socket reachable, and
make sure at least one transport is available:

- **`USER_SERVICE`** (preferred) — Shizuku running and authorized for ClosePaw.
- **`WIRELESS_ADB_SELF_PAIR`** (fallback) — wireless debugging enabled in Developer Options
  (Android 11+); ClosePaw self-pairs once and reuses the stored ADB key.

SMART mode still asks for approval before `browser_script` runs against Chrome.

> **AOSP emulator note:** Chrome stable on AOSP defaults to `chromium-enable-devtools-remote =
> false` in `Local State`, which disables the `chrome_devtools_remote` socket entirely. Apply the
> chrome://flags Local State unlock procedure in
> `projects/active/browser/cn/diag_20260503_emu_chrome_devtools.md` once before expecting CDP to
> work on the emulator. Real devices (e.g. nubia P0110) do not need this.

### Prompt Ownership

When tuning the agent's cognition, edit the narrowest owner:

- Core cross-tool behavior: `agent/definition/StandaloneAgentDef.kt` and `PlannerAgentDef.kt`
- Tool-local semantics: tool `description` strings in `tool/impl/*.kt`
- App-specific guidance: `app/src/main/assets/app_skills/<package>/SKILL.md`

The active app skill is loaded fresh each turn from the foreground package and inserted into the
prompt between Working Memory and Observation.

### 3. Device Test

Run the agent with a goal. `debug-run.sh` captures screenshots at each turn, records trace artifacts, and saves comprehensive logs for post-run analysis. Press Ctrl+C to gracefully stop the agent.

> Onboarding bypass: debug builds skip the onboarding wizard whenever the launch intent carries both `fresh_session=true` and a `goal` extra (handled in both `onCreate` and `onNewIntent` of `MainActivity`). `debug-run.sh` always sets these, so you never need to complete the wizard before iterating.

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
./scripts/action-test.sh tap --x 540 --y 1200 --shizuku --display-id 0
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
| `ClassNotFoundException` / `NoSuchMethodError` only on release | Missing R8 keep rule — open `app/proguard-rules.pro`, add a targeted keep for the offending package/class, rebuild. Debug build won't reproduce. |
| Verbose prompt/response log missing | You are running release. Switch to debug — `VERBOSE_LOGGING = BuildConfig.DEBUG`. |

## Worktrees

This repo uses git worktrees for isolated task branches (e.g., orchestrate's `.worktrees/<slug>/`). Two per-machine files — `.env` (LLM credentials) and `local.properties` (Android SDK path) — are gitignored and must be present in every worktree for `./gradlew` and `scripts/debug-run.sh` to work.

`scripts/worktree-provision.sh` handles this automatically: the orchestrate skill's `worktree-create.sh` runs it once when a worktree is first created, symlinking `.env` and `local.properties` from the main checkout. It's idempotent — safe to re-run manually if a worktree is ever reset or missing the links.

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

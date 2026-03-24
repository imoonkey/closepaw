---
name: ux-visual-debug
description: Run end-to-end Android UX QA from a user perspective through ADB-driven interaction and visual evidence capture. Use when iterating app UX, validating interaction regressions after UI changes, reproducing reported UX bugs, or checking flows in different app states (including Main app and Smart Capsule states such as Takeover/Supplement and text input paths). Prefer this skill when the goal is UX behavior validation rather than internal agent reasoning/debug logs.
---

# UX Visual Debug

Validate app UX from a real user's perspective via ADB-driven interaction and visual evidence capture.

Two modes:
1. **AI-Interactive Mode** (primary) — Claude drives testing via screenshot-analyze-act loops, adapting to actual screen state.
2. **Scenario Mode** (deterministic) — JSON-defined step sequences for smoke tests and CI regression.

Keep this separate from `cog-tune`:
- `cog-tune`: inspect agent reasoning/perception/tool chain.
- `ux-visual-debug`: inspect app UX behavior and interaction quality.

---

## Mode 1: AI-Interactive Testing (Primary)

The app's actual screen state is unpredictable — agent timing, LLM responses, and async events mean each run looks different. Instead of rigid JSON steps, Claude should drive testing interactively: capture state, analyze visually, decide the next action, execute, and verify.

### Workflow

1. **Start agent run** (in a background shell or separate terminal):
   ```bash
   ./scripts/setup.sh && ./scripts/debug-run.sh --basic "play a Adele song on youtube"
   # For VD mode: add --vd
   ```

2. **Capture current state** (screenshot + UI dump + visible text in one command):
   ```bash
   python3 .claude/skills/ux-visual-debug/scripts/adb_ux_runner.py \
     --capture /tmp/ux_cap/state_001
   ```
   Outputs: `state_001.png`, `state_001.xml`, `state_001_visible.txt`

3. **Analyze**: Read the screenshot (visual) + visible text file (structured). Determine current CapsuleMode and available actions. See "Smart Capsule State Reference" below.

4. **Act**: Execute ADB commands based on analysis. See "ADB Quick Reference" below.

5. **Capture again**: Verify the action had the expected effect.

6. **Repeat**: Continue the capture-analyze-act loop until the flow under test is complete.

7. **Record findings**: Document pass/fail per user flow with evidence.

### ADB Quick Reference

```bash
# Screenshot (raw, without runner)
adb exec-out screencap -p > /tmp/screen.png

# UI tree dump
adb shell uiautomator dump /sdcard/dump.xml && adb pull /sdcard/dump.xml /tmp/dump.xml

# Tap by coordinates
adb shell input tap <x> <y>

# Tap by finding element in UI dump (use --capture, then read XML for bounds)
# Parse bounds="[x1,y1][x2,y2]" → tap center ((x1+x2)/2, (y1+y2)/2)

# Type text (spaces encoded as %s)
adb shell input text "hello%sworld"

# Key events
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_HOME
adb shell input keyevent KEYCODE_ENTER
adb shell input keyevent KEYCODE_DEL    # backspace

# Swipe
adb shell input swipe <x1> <y1> <x2> <y2> <duration_ms>

# Force stop app
adb shell am force-stop com.moonkey.androidagent

# Launch app
adb shell monkey -p com.moonkey.androidagent -c android.intent.category.LAUNCHER 1
```

### Smart Capsule State Reference

Source: `CapsuleRenderSpec.from()` + `SmartCapsuleLayoutBuilder.kt`

#### Modes and UI Elements

| Mode | Dot | Row1 (thought) | Expanded Body | Primary Button | Stop Button | Row3 (input) |
|---|---|---|---|---|---|---|
| **Hidden** | — | "" | — | — | — | hint="What can I help you with?" btn="Send" |
| **Running** | blue pulse | thought text | — | "Takeover" | "Stop" | hint="Got ideas? Add a note..." btn="Add note" |
| **TakeoverPending** | amber static | "Handing over..." | — | "Handing over" (disabled) | "Stop" | same as Running |
| **Takeover** | amber static | lastThought (dimmed) | — | "Resume" | "Stop" | same as Running |
| **WaitingForInput** | — | "Awaiting response" | question text | — | "Stop" | hint="Type your response..." btn="Send" |
| **WaitingForAction** | — | "Action needed" | instruction text | "Done" | "Stop" | — (hidden) |
| **Done** | teal static | "message" | — | — | — | — |
| **Error** | red static | "message" | — | — | "Close" | — |

#### Content Descriptions (ADB selectors)

| Element | contentDescription | Notes |
|---|---|---|
| Primary button | Dynamic: "Takeover" / "Handing over" / "Resume" / "Done" | Matches button label |
| Stop button | Dynamic: "Stop" / "Close" | Matches button label |
| Row1 container | "Open main app" | Only in overlay mode (when `onRow1Tap` is set) |
| Nav: Minimize | "Minimize" | VD BACKGROUND context only |
| Nav: Open App | "Open app" | Overlay/VD when not in MAIN_APP |
| Nav: View Screen | "View screen" | VD BACKGROUND context only |
| Status Island | "Agent status island" | VD mode compact pill |

#### Navigation Button Visibility

| Context | Platform | Minimize | Open App | View Screen |
|---|---|---|---|---|
| MAIN_APP | any | no | no | no |
| SCREEN_VIEWING | A11y | no | yes | no |
| SCREEN_VIEWING | VD | no | yes | no |
| BACKGROUND | A11y | no | yes | yes |
| BACKGROUND | VD | yes | yes | yes |

### How to Verify Each State

**Running**: Blue pulsing dot visible. Row1 shows thought text (updates as agent thinks). Row2 has "Takeover" and "Stop" buttons. Row3 shows input with "Got ideas? Add a note..." hint.

**TakeoverPending**: Amber static dot. Row1 shows "Handing over...". Primary button shows "Handing over" but is disabled. Transition is brief — may jump straight to Takeover if agent responds fast.

**Takeover**: Amber static dot. Row1 shows last thought text with reduced opacity (dimmed). "Resume" button replaces "Takeover". "Stop" still available. Row3 still available for supplements.

**WaitingForInput**: No dot. Row1 shows "Awaiting response". Expanded body shows the agent's question. Row3 changes to "Type your response..." with "Send" button. In overlay mode: overlay becomes focusable, keyboard may auto-show.

**WaitingForAction**: No dot. Row1 shows "Action needed". Expanded body shows instruction. "Done" button visible. Row3 is hidden (no text input needed).

**Done**: Teal static dot. Row1 shows completion message prefixed with checkmark. All buttons hidden. Row3 hidden. Auto-hides after ~3 seconds.

**Error**: Red static dot. Row1 shows error message prefixed with warning. Only "Close" button visible. Does NOT auto-hide — stays until user taps Close.

**Hidden**: No capsule visible (in overlay mode). In Main App: only Row3 input dock visible with "What can I help you with?" hint and "Send" button.

### Overlay Visibility Note

The Smart Capsule overlay uses `TYPE_ACCESSIBILITY_OVERLAY`. Whether `uiautomator dump` captures overlay elements is device/Android-version dependent. Before relying on text/desc selectors for overlay elements:

```bash
# Empirical test: with capsule overlay showing, check if uiautomator sees it
adb shell uiautomator dump /sdcard/dump.xml && adb pull /sdcard/dump.xml /tmp/dump.xml
grep -i "takeover\|stop\|thinking\|Add note" /tmp/dump.xml
```

If overlay nodes are NOT visible in the dump:
- Use `tap_xy` with coordinates calculated from layout (see SmartCapsuleLayoutBuilder.kt dp values).
- Main App Compose UI is typically visible to uiautomator.
- Status Island may also be invisible — use coordinates or `tap_contains_text` on truncated thought text.

### Recording Findings

Use this template per user flow tested:

```
Flow ID:    [e.g., A1, B1, F2]
Surface:    Main App | A11y Overlay | VD Island | VD Capsule
Mode:       A11y | VD
Result:     PASS | FAIL
Evidence:   <screenshot path> <visible text path>

Expected: <what should happen>
Actual:   <what actually happened>
Suspected layer: state holder | renderer | overlay controller | session
```

---

## Mode 2: Scenario Runner (Deterministic)

JSON-defined step sequences for repeatable smoke tests and CI regression.

### Commands

```bash
# Run scenario file
python3 .claude/skills/ux-visual-debug/scripts/adb_ux_runner.py \
  --scenario .claude/skills/ux-visual-debug/references/scenario_a11y_lifecycle.json \
  --out-root debug-output/ux-qa

# Quick state capture (AI-interactive helper)
python3 .claude/skills/ux-visual-debug/scripts/adb_ux_runner.py \
  --capture /tmp/ux_cap/state_001

# Specific device
python3 .claude/skills/ux-visual-debug/scripts/adb_ux_runner.py \
  --scenario <scenario.json> --serial emulator-5554

# Linked mode (parallel): run agent goal + UX scenario together
python3 .claude/skills/ux-visual-debug/scripts/adb_ux_runner.py \
  --scenario .claude/skills/ux-visual-debug/references/scenario_a11y_lifecycle.json \
  --agent-goal "play a Adele song on youtube" \
  --agent-link-mode parallel \
  --agent-setup \
  --agent-debug-arg=--basic

# Linked mode (serial): capture agent trajectory first, then run UX scenario
python3 .claude/skills/ux-visual-debug/scripts/adb_ux_runner.py \
  --scenario <scenario.json> \
  --agent-goal "Complete Smart Capsule handoff flow" \
  --agent-link-mode serial \
  --agent-setup \
  --agent-debug-arg=--basic
```

### Supported Actions

| Action | Key Params | Description |
|---|---|---|
| `force_stop` | `package` (optional, defaults to scenario package) | Kill app |
| `start_app` | `package`, `activity` (optional) | Launch app. Falls back to monkey if activity fails |
| `wait` | `ms` | Fixed sleep |
| `tap_text` | `text`, `occurrence`, `retries`, `retry_interval_ms` | Tap element matching exact text or content-desc |
| `tap_contains_text` | `text`, `occurrence`, `retries`, `retry_interval_ms` | Tap element containing text substring |
| `tap_resource_id` | `resource_id`, `occurrence`, `retries`, `retry_interval_ms` | Tap element by resource ID |
| `tap_desc` | `desc`, `contains`, `occurrence`, `retries`, `retry_interval_ms` | Tap element by content-description |
| `tap_xy` | `x`, `y` | Tap exact coordinates |
| `type` | `text`, `clear`, `clear_count`, `submit` | Type text. `clear` deletes existing, `submit` presses Enter |
| `keyevent` | `key` | Send key event (e.g., `KEYCODE_BACK`) |
| `back` | — | Press back |
| `home` | — | Press home |
| `swipe` | `x1`, `y1`, `x2`, `y2`, `duration_ms` | Swipe gesture |
| `assert_text` | `text` | Assert text is visible (fails if not found) |
| `assert_not_text` | `text` | Assert text is NOT visible |
| `wait_for_text` | `text`, `timeout_ms`, `interval_ms` | Poll until text appears (default 10s timeout, 500ms interval) |
| `wait_for_not_text` | `text`, `timeout_ms`, `interval_ms` | Poll until text disappears |
| `wait_for_desc` | `desc`, `timeout_ms`, `interval_ms` | Poll until content-description appears |
| `screenshot` | — | Take screenshot (evidence only, no assertion) |
| `dump_ui` | — | Dump UI tree (evidence only) |
| `note` | — | No-op marker for documentation |

### Step Options

- `name`: Human-readable step title.
- `continue_on_fail`: If true, continue scenario after this step fails (default: false).
- `retries`: Number of retry attempts for tap actions (default: 1 = no retry).
- `retry_interval_ms`: Delay between retries in ms (default: 800).

### Reference Scenarios

- `references/scenario_a11y_lifecycle.json` — Core A11y task lifecycle (start, takeover, resume, completion)
- `references/scenario_vd_navigation.json` — VD island/capsule navigation
- `references/scenario_main_and_capsule.json` — Main app + capsule smoke test
- `references/scenario_agent_parallel_example.json` — Parallel agent linkage example
- `references/scenario_template.json` — Blank template

### cog-tune Linkage

When `--agent-goal` is set, the runner coordinates with `scripts/debug-run.sh`:
- `parallel`: Agent runs while UX scenario executes user interactions simultaneously.
- `serial`: Agent trajectory completes first, then UX scenario runs.
- Optional `--agent-setup` runs `scripts/setup.sh` before the linked run.
- Extra args via `--agent-debug-arg=--basic` etc.

All linkage metadata appears in outputs (`report.md` + `run_summary.json`).

### Output

Runner output goes to `debug-output/ux-qa/run_<timestamp>_<scenario>/`:
- `report.md` — Summary + per-step results
- `run_summary.json` — Machine-readable full result
- `step_XXX_*.png` — Screenshot after each step
- `step_XXX_*.xml` — UI tree after each step
- `step_XXX_*_visible.txt` — Visible text snapshot
- `agent_debug_run.log` — Present in linked mode
- `agent_setup.log` — Present when `--agent-setup` is enabled

---

## QA Rubric

When reviewing report artifacts, use `references/ux_checks.md` and classify issues as:
- **P0**: Crash, freeze, cannot proceed.
- **P1**: Core flow broken, severe confusion.
- **P2**: UX friction, misleading feedback, consistency issue.
- **P3**: Minor polish issue.

Always include:
- Exact failed step index/name.
- Screenshot + UI XML path.
- Expected vs actual behavior.
- Repro command.

## Execution Rules

- Prefer AI-Interactive Mode for exploratory testing and debugging.
- Prefer Scenario Mode for deterministic regression checks.
- Prefer ADB interaction over code-level hooks.
- Fail fast on blockers unless `continue_on_fail` is set.
- If selectors are unstable, switch from text selector to `resource_id` or coordinate fallback.
- In parallel linked mode, expect occasional ADB contention — use extra waits/retries.
- Keep UX findings independent from agent reasoning findings.

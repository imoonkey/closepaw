# Action Execution Direct Debug: Tech Design

**Status**: DRAFT
**Author**: Claude
**Date**: 2025-02-19
**Goal**: Build a short-path debug harness for deterministic, isolated testing of action execution (click, long_press, scroll, swipe) with adb baseline comparison.

---

## Problem Statement

Action execution reports success but UI doesn't change. Current debugging path is expensive:

```
Manual screen setup → Launch agent with task → Wait for LLM reasoning →
Agent eventually calls action → Observe before/after screenshots → Repeat
```

This loop involves LLM latency, agent reasoning, session bootstrapping, and non-deterministic action selection. We need a **zero-LLM, deterministic, CLI-driven** path that:

1. Executes a single action **directly on the current screen state** (no agent loop, no task context)
2. Captures before/after screenshots and a11y tree automatically
3. Runs the equivalent **adb command as baseline** for A/B comparison
4. Works at multiple execution layers (platform, executor, raw adb)

---

## Design Overview

### Architecture

```
                          Host Machine (Mac)
                    ┌──────────────────────────┐
                    │   action-test.sh          │
                    │   ┌──────────────────┐    │
                    │   │ 1. screencap     │    │
                    │   │ 2. am broadcast  │───────────┐
                    │   │    (or adb input) │    │      │
                    │   │ 3. screencap     │    │      │
                    │   │ 4. pull results  │    │      │
                    │   └──────────────────┘    │      │
                    └──────────────────────────┘      │
                                                       │
                          Android Device               │
                    ┌──────────────────────────┐      │
                    │                          │      │
                    │  ActionDebugReceiver     │◄─────┘
                    │    │                     │
                    │    ├─ Layer 0: adb input │  (script handles directly)
                    │    │   (no app code)     │
                    │    │                     │
                    │    ├─ Layer 1: Platform  │
                    │    │   UIAction → AccessibilityPlatform.performAction()
                    │    │                     │
                    │    └─ Layer 2: Executor  │
                    │        ClickExecutor / ScrollExecutor / SwipeExecutor
                    │        (target resolution + fallback logic)
                    │                          │
                    └──────────────────────────┘
```

### Three Execution Layers

| Layer | What It Tests | Entry Point | Involves |
|-------|---------------|-------------|----------|
| **L0: Raw ADB** | Baseline sanity | `adb shell input tap/swipe` | Nothing app-side |
| **L1: Platform** | Gesture injection, node actions | `AccessibilityPlatform.performAction(UIAction)` | GestureInjector, NodeActionPerformer, NodeFinder |
| **L2: Executor** | Target resolution, fallback chains | `ClickExecutor.execute(target, snapshot, platform)` | TargetResolver + all of L1 |

For any given test, you can run L0 alone (adb baseline), L1 alone (platform test), L2 alone (executor test), or **any combination** for comparison.

---

## Component 1: ActionDebugReceiver (App-Side)

A `BroadcastReceiver` registered in the app manifest that provides the shortest possible path from `adb shell am broadcast` to action execution.

### Location

`app/src/main/kotlin/com/moonkey/androidagent/debug/ActionDebugReceiver.kt`

### Intent Contract

**Action**: `com.moonkey.androidagent.ACTION_DEBUG_EXEC`

**Common Extras**:

| Extra Key | Type | Required | Description |
|-----------|------|----------|-------------|
| `exec_layer` | String | No (default: `platform`) | `platform` or `executor` |
| `action` | String | Yes | `click`, `long_press`, `scroll`, `swipe`, `tap` |
| `capture_tree` | Boolean | No (default: true) | Dump a11y tree before execution |

**Action-Specific Extras**:

| Action | Required Extras | Optional Extras |
|--------|----------------|-----------------|
| `click` | `x`, `y` (Int) | `use_node` (Boolean, default true. true=ClickNodeAt, false=TapAt) |
| `tap` | `x`, `y` (Int) | - |
| `long_press` | `x`, `y` (Int) | `duration_ms` (Int, default 1000) |
| `scroll` | `direction` (String: up/down/left/right) | `x`, `y` (Int, default screen center) |
| `swipe` | `start_x`, `start_y`, `end_x`, `end_y` (Int) | `duration_ms` (Int, default 400) |

**Executor-layer extras** (when `exec_layer=executor`):

| Extra Key | Type | Description |
|-----------|------|-------------|
| `element_index` | Int | Target by element index (requires snapshot) |
| `text` | String | Target by text match |
| `text_index` | Int | Disambiguation for text targets |

### Execution Flow

```
onReceive(context, intent)
  │
  ├── Parse intent extras → ActionDebugRequest
  │
  ├── Get AccessibilityService instance (singleton)
  │   └── If null → write error result, return
  │
  ├── goAsync() to extend receiver lifetime
  │
  └── CoroutineScope(Dispatchers.Main).launch {
        │
        ├── Build execution components from service:
        │   ├── rootProvider = { service.rootInActiveWindow }
        │   ├── nodeFinder = AccessibilityNodeFinder()
        │   ├── nodeActionPerformer = NodeActionPerformer(rootProvider, nodeFinder)
        │   ├── gestureInjector = AccessibilityGestureInjector(service)
        │   └── platform = AccessibilityPlatform(service, ...)
        │
        ├── [Optional] Capture pre-action snapshot
        │   ├── a11y tree → pre_tree.json
        │   └── screenshot → pre.png
        │
        ├── Execute action based on exec_layer:
        │   │
        │   ├── Layer 1 (platform):
        │   │   Build UIAction from params → platform.performAction(uiAction)
        │   │
        │   └── Layer 2 (executor):
        │       Build Target + snapshot → Executor.execute(target, snapshot, platform)
        │
        ├── Wait settle delay (300-500ms)
        │
        ├── Capture post-action snapshot
        │   ├── a11y tree → post_tree.json
        │   └── screenshot → post.png
        │
        ├── Compute UIChangeDetector result (changed/unchanged/unverifiable)
        │
        ├── Write result.json:
        │   {
        │     "action": "click",
        │     "layer": "platform",
        │     "params": {"x": 500, "y": 800, "use_node": true},
        │     "action_result": "Success" | "Failure(reason)",
        │     "ui_changed": true | false | null,
        │     "attempt_trail": ["node_action_click: success"],
        │     "elapsed_ms": 342,
        │     "timestamp": "2025-02-19T14:32:01.123Z"
        │   }
        │
        └── pendingResult.finish()
      }
```

### Output Location (Device)

```
/sdcard/Android/data/com.moonkey.androidagent/files/action-test/
  latest/
    pre.png
    post.png
    pre_tree.json
    post_tree.json
    result.json
```

Always writes to `latest/` so the script can pull without knowing timestamps. The receiver clears `latest/` before each execution.

### Key Implementation Details

**AccessibilityService Access**:

The service is already a singleton with a static instance reference pattern (standard Android a11y service). The receiver accesses it via:

```kotlin
val service = AgentAccessibilityService.instance
    ?: return writeError("Accessibility service not running")
```

**Coroutine Lifetime**: Use `goAsync()` + `PendingResult` pattern:

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
        try {
            executeDebugAction(context, intent)
        } finally {
            pendingResult.finish()
        }
    }
}
```

**Platform Construction**: Build a minimal AccessibilityPlatform specifically for debug:

```kotlin
// Minimal platform — no perception config, no trace, no visualizer
// Just the execution primitives needed to test actions
private fun buildDebugPlatform(service: AgentAccessibilityService): AccessibilityPlatform {
    // Reuse existing factory or build inline with minimal deps
}
```

If constructing a full AccessibilityPlatform has too many dependencies, create a `DebugActionExecutor` that directly composes `NodeActionPerformer` + `AccessibilityGestureInjector` and implements only `performAction()` and `captureScreen()`.

**Manifest Registration**:

```xml
<receiver
    android:name=".debug.ActionDebugReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="com.moonkey.androidagent.ACTION_DEBUG_EXEC" />
    </intent-filter>
</receiver>
```

> Note: `exported=true` is required for adb broadcasts. This is debug-only — gate via BuildConfig.DEBUG or a runtime flag if needed for release builds.

---

## Component 2: action-test.sh (Host-Side CLI)

A shell script that orchestrates the full test cycle: screenshot → execute → screenshot → compare.

### Location

`scripts/action-test.sh`

### Usage

```bash
# Layer 0: Raw ADB (baseline)
./scripts/action-test.sh tap --x 500 --y 800 --adb

# Layer 1: Platform (accessibility-based)
./scripts/action-test.sh click --x 500 --y 800

# Layer 1: Platform with explicit node vs gesture
./scripts/action-test.sh click --x 500 --y 800 --use-node false   # gesture only
./scripts/action-test.sh click --x 500 --y 800 --use-node true    # node action

# Layer 2: Executor (target resolution + fallbacks)
./scripts/action-test.sh click --element-index 5 --layer executor
./scripts/action-test.sh scroll --direction down --element-index 3 --layer executor

# Comparison: run both accessibility and adb, capture both
./scripts/action-test.sh click --x 500 --y 800 --compare

# Swipe
./scripts/action-test.sh swipe --start-x 540 --start-y 1200 --end-x 540 --end-y 600 --duration 400

# Scroll
./scripts/action-test.sh scroll --direction down
./scripts/action-test.sh scroll --direction down --x 540 --y 960

# Long press
./scripts/action-test.sh long_press --x 500 --y 800 --duration 1500

# Options
./scripts/action-test.sh click --x 500 --y 800 --no-tree    # skip a11y tree capture
./scripts/action-test.sh click --x 500 --y 800 --tag "settings_wifi_toggle"  # name the test
./scripts/action-test.sh click --x 500 --y 800 --open       # auto-open output images
```

### Execution Flow

```
action-test.sh <action> [params]
  │
  ├── Parse args
  │
  ├── Select device (reuse debug-run.sh device selection logic)
  │
  ├── Create output directory:
  │   debug-output/action-test/{tag_or_timestamp}/
  │
  ├── [If --compare or --adb mode]:
  │   │
  │   ├── adb exec-out screencap -p > before_adb.png
  │   ├── adb shell input <tap|swipe> <coords>
  │   ├── sleep <settle_delay>
  │   └── adb exec-out screencap -p > after_adb.png
  │
  │   [If --adb only: print result, exit]
  │
  ├── [If --compare: wait for UI to settle back, or prompt user to reset]
  │
  ├── [Default or --compare mode]:
  │   │
  │   ├── adb exec-out screencap -p > before_a11y.png
  │   │
  │   ├── adb shell am broadcast \
  │   │     -a com.moonkey.androidagent.ACTION_DEBUG_EXEC \
  │   │     --es action "click" --ei x 500 --ei y 800 \
  │   │     --es exec_layer "platform" --ez capture_tree true
  │   │
  │   ├── Wait for result file (poll device):
  │   │   adb shell "while [ ! -f $RESULT_PATH ]; do sleep 0.1; done"
  │   │
  │   ├── adb exec-out screencap -p > after_a11y.png
  │   │
  │   ├── Pull result files from device:
  │   │   adb pull .../action-test/latest/ ./
  │   │
  │   └── Parse & print result.json summary
  │
  ├── Print summary:
  │   ┌─────────────────────────────────────────┐
  │   │ Action: click @ (500, 800)              │
  │   │ Layer:  platform                        │
  │   │ Result: Success                         │
  │   │ UI Changed: true                        │
  │   │ Trail: [node_action_click: success]     │
  │   │ Elapsed: 342ms                          │
  │   │                                         │
  │   │ Output: debug-output/action-test/...    │
  │   │   before.png / after.png / result.json  │
  │   └─────────────────────────────────────────┘
  │
  └── [If --open]: open before.png after.png
```

### Comparison Mode Detail (--compare)

The `--compare` flag runs the same action via **both adb and accessibility**, capturing screenshots for each. This is the key debugging workflow.

**Challenge**: After adb executes the action, the UI has changed. We can't run the same action again on the same state.

**Solution**: Two approaches (user picks per scenario):

1. **Idempotent actions**: Some actions can be run twice meaningfully (e.g., scroll down, swipe). Run adb first, capture, then reset (scroll back or user resets), run a11y, capture.

2. **Snapshot-and-reset** (default): For non-idempotent actions (clicks that navigate):
   ```
   State A → adb action → State B (capture) → user presses back/resets to State A → a11y action → State C (capture)
   Compare: B should equal C
   ```
   The script will **pause and prompt** after the adb run:
   ```
   [adb] Action executed. Screenshots saved.
   Reset the screen to the same state, then press Enter to run the accessibility version...
   ```

3. **Parallel device mode** (advanced): If two identical devices/emulators are available, run adb on one and a11y on the other simultaneously. The script accepts `--device2 <serial>` for this.

### ADB Command Mapping

| Action | ADB Command |
|--------|-------------|
| `tap` / `click` | `adb shell input tap {x} {y}` |
| `long_press` | `adb shell input swipe {x} {y} {x} {y} {duration_ms}` |
| `swipe` | `adb shell input swipe {x1} {y1} {x2} {y2} {duration_ms}` |
| `scroll down` | `adb shell input swipe {cx} {cy+d} {cx} {cy-d} 400` (finger up = content down) |
| `scroll up` | `adb shell input swipe {cx} {cy-d} {cx} {cy+d} 400` |

---

## Component 3: A11y Tree Dump (for L2 Debugging)

When debugging executor-layer issues (target resolution, element_index accuracy), the a11y tree snapshot is critical.

### Standalone Tree Dump

```bash
# Dump current screen's a11y tree without executing any action
./scripts/action-test.sh dump-tree
./scripts/action-test.sh dump-tree --format json    # structured
./scripts/action-test.sh dump-tree --format prompt   # same format as LLM sees
```

This triggers a broadcast that only captures and saves the a11y tree, with no action execution. Useful for:

- Verifying element indices before running an action
- Checking if `isScrollable`, `isClickable`, `checked` properties are correct
- Understanding what the executor "sees" before target resolution

### Implementation

Same BroadcastReceiver, with `action=dump_tree`:

```
adb shell am broadcast -a com.moonkey.androidagent.ACTION_DEBUG_EXEC \
  --es action "dump_tree" --es format "prompt"
```

Writes to `latest/tree.json` (structured) and `latest/tree_prompt.txt` (LLM format).

---

## Debugging Workflows

### Workflow 1: Single Action Spot Check

The fastest loop. You see something on screen, you want to test if clicking it works.

```bash
# 1. Look at the device screen, identify coordinates (or use Layout Inspector)
# 2. Run the action
./scripts/action-test.sh click --x 540 --y 1200 --tag "wifi_toggle"

# 3. Check result
cat debug-output/action-test/wifi_toggle/result.json

# 4. Open screenshots to visually verify
open debug-output/action-test/wifi_toggle/before.png
open debug-output/action-test/wifi_toggle/after.png

# 5. If action "succeeded" but UI didn't change:
#    - Check attempt_trail in result.json
#    - Compare with adb baseline
./scripts/action-test.sh tap --x 540 --y 1200 --adb --tag "wifi_toggle_adb"
```

### Workflow 2: A/B Comparison (A11y vs ADB)

For diagnosing "it works via adb but not via accessibility":

```bash
# Run with comparison
./scripts/action-test.sh click --x 540 --y 1200 --compare --tag "settings_click"

# Script will:
# 1. Take screenshot, run adb tap, take screenshot
# 2. Prompt you to reset screen
# 3. Take screenshot, run a11y click, take screenshot
# 4. Both sets of screenshots saved side by side

# Review:
open debug-output/action-test/settings_click/before_adb.png
open debug-output/action-test/settings_click/after_adb.png
open debug-output/action-test/settings_click/before_a11y.png
open debug-output/action-test/settings_click/after_a11y.png
```

### Workflow 3: Node vs Gesture Click

For diagnosing whether node-based click or gesture tap works better:

```bash
# Test node action (AccessibilityNodeInfo.ACTION_CLICK)
./scripts/action-test.sh click --x 540 --y 1200 --use-node true --tag "node"

# Reset screen

# Test gesture tap (dispatchGesture)
./scripts/action-test.sh click --x 540 --y 1200 --use-node false --tag "gesture"

# Compare
diff <(jq . debug-output/action-test/node/result.json) \
     <(jq . debug-output/action-test/gesture/result.json)
```

### Workflow 4: Scroll Debugging

Scroll is the most failure-prone action. Test each layer:

```bash
# 1. Dump tree to find scrollable containers
./scripts/action-test.sh dump-tree --format prompt

# 2. Test a11y scroll (ACTION_SCROLL_DOWN on scrollable node)
./scripts/action-test.sh scroll --direction down --x 540 --y 960 --tag "scroll_a11y"

# 3. Test gesture scroll (swipe gesture)
./scripts/action-test.sh swipe --start-x 540 --start-y 1400 --end-x 540 --end-y 600 --tag "scroll_gesture"

# 4. Test adb scroll baseline
./scripts/action-test.sh swipe --start-x 540 --start-y 1400 --end-x 540 --end-y 600 --adb --tag "scroll_adb"

# Compare all three
```

### Workflow 5: Executor Target Resolution Test

For verifying element_index resolution accuracy:

```bash
# 1. Dump tree and identify element index
./scripts/action-test.sh dump-tree --format prompt
# → Element [5]: "Wi-Fi" Switch (bounds: 0,400,1080,520)

# 2. Test executor with element_index
./scripts/action-test.sh click --element-index 5 --layer executor --tag "wifi_by_index"

# 3. Check result — was target resolved to correct coordinates?
jq '.resolved_point, .attempt_trail' debug-output/action-test/wifi_by_index/result.json
```

### Workflow 6: Batch Regression Test

Run a predefined set of actions on a known screen to catch regressions:

```bash
# Define a test suite as a simple script
cat > tests/action_suite_settings.sh << 'EOF'
#!/bin/bash
# Launch Settings first
adb shell am start -a android.settings.SETTINGS
sleep 2

# Test 1: Click "Network & internet"
./scripts/action-test.sh click --x 540 --y 400 --compare --tag "settings_network"
echo "Press Enter after resetting to Settings main..."
read

# Test 2: Scroll down
./scripts/action-test.sh scroll --direction down --compare --tag "settings_scroll_down"
echo "Press Enter after resetting..."
read

# Test 3: Long press on an item
./scripts/action-test.sh long_press --x 540 --y 600 --compare --tag "settings_longpress"
EOF

chmod +x tests/action_suite_settings.sh
./tests/action_suite_settings.sh
```

---

## Implementation Plan

### Phase 1: ActionDebugReceiver (App-Side) — Core

**Files to create**:

| File | Description |
|------|-------------|
| `app/src/main/kotlin/com/moonkey/androidagent/debug/ActionDebugReceiver.kt` | BroadcastReceiver: parse intent → execute action → write results |
| `app/src/main/kotlin/com/moonkey/androidagent/debug/DebugActionExecutor.kt` | Thin wrapper: builds platform components from AccessibilityService, exposes `performAction()` and `captureSnapshot()` |

**Files to modify**:

| File | Change |
|------|--------|
| `app/src/main/AndroidManifest.xml` | Register `ActionDebugReceiver` |
| `app/src/main/kotlin/com/moonkey/androidagent/platform/AgentAccessibilityService.kt` (or equivalent) | Ensure static `instance` reference is accessible for the receiver |

**Scope**: ~200 lines total. No changes to any production execution path.

### Phase 2: action-test.sh (Host-Side CLI)

**Files to create**:

| File | Description |
|------|-------------|
| `scripts/action-test.sh` | Main CLI script (~250 lines) |

**Dependencies**: Only `adb`, `jq` (optional, for pretty-printing results).

### Phase 3: Enhancements (Optional, On-Demand)

| Enhancement | Description |
|-------------|-------------|
| `dump-tree` command | A11y tree dump without action execution |
| `--compare` mode | A/B comparison with adb baseline |
| `--layer executor` | Full executor path with target resolution |
| VirtualDisplay support | Add `--platform vd` flag to test VirtualDisplayPlatform path |
| Batch runner | Simple script that runs a sequence of tests |

---

## Key Design Decisions

### 1. BroadcastReceiver vs Instrumentation Test vs Custom Service

**Chosen: BroadcastReceiver**

| Option | Pros | Cons |
|--------|------|------|
| BroadcastReceiver | Zero UI, instant trigger via adb, no test dependencies, works with any device state | 10s execution limit (but goAsync() extends to 30s), needs running a11y service |
| Instrumentation test | Full Android test infra, JUnit assertions | Requires test APK build+deploy cycle, heavy setup, slower iteration |
| Bound Service + AIDL | No time limit, bidirectional communication | Complex setup, overkill for single-shot debug |
| ContentProvider | Can return structured data synchronously | Semantically wrong, awkward for actions |

BroadcastReceiver wins because it has the **shortest path from CLI to execution** — one `adb shell am broadcast` command. The goAsync() 30s window is more than sufficient for any single action + screenshot capture.

### 2. Output via File vs Logcat vs Broadcast Result

**Chosen: File on device storage**

- Files are inspectable, diffable, and version-controllable
- Screenshots can only be files (not logcat)
- Result JSON alongside screenshots in same directory = natural grouping
- Script pulls entire directory in one `adb pull`

Supplement with logcat tag `ActionDebug` for real-time monitoring:

```bash
# In another terminal, watch live
adb logcat -s ActionDebug:V
```

### 3. Minimal Platform vs Full Platform Construction

**Chosen: Minimal — DebugActionExecutor**

Create a thin class that directly composes `NodeActionPerformer` + `AccessibilityGestureInjector` from the AccessibilityService. Avoid pulling in `PerceptionConfig`, `TraceWriter`, `ActionVisualizerManager`, etc. This keeps the debug path isolated from production boot-up logic.

```kotlin
class DebugActionExecutor(service: AgentAccessibilityService) {
    private val nodeFinder = AccessibilityNodeFinder()
    private val rootProvider = { service.rootInActiveWindow }
    private val nodePerformer = NodeActionPerformer(rootProvider, nodeFinder)
    private val gestureInjector = AccessibilityGestureInjector(service)

    suspend fun performAction(action: UIAction): ActionResult { ... }
    suspend fun captureSnapshot(): ScreenSnapshot { ... }
}
```

### 4. Debug-Only Gating

The receiver should be debug-build-only to avoid shipping debug endpoints:

```kotlin
// Option A: Manifest-level (preferred)
// Only register in debug build variant via debug/AndroidManifest.xml

// Option B: Runtime check
if (!BuildConfig.DEBUG) {
    Log.w(TAG, "ActionDebugReceiver disabled in release build")
    return
}
```

Use Option A: place the `<receiver>` declaration in `app/src/debug/AndroidManifest.xml` so it's completely absent from release APKs.

### 5. Comparison Mode Screen Reset

For non-idempotent actions in `--compare` mode, the script **pauses and prompts the user to reset** rather than attempting automatic reset. Reasons:

- Automatic reset is fragile (back button doesn't always restore state)
- The user knows the exact screen state they want to test
- Interactive pause is acceptable since this is a manual debugging workflow
- Can add `--auto-reset` later with configurable reset commands if needed

---

## Result Schema

```json
{
  "version": 1,
  "action": "click",
  "layer": "platform",
  "params": {
    "x": 540,
    "y": 1200,
    "use_node": true
  },
  "action_result": {
    "status": "Success",
    "message": "Action completed"
  },
  "ui_changed": true,
  "attempt_trail": [
    "node_action_click: success"
  ],
  "resolved_point": {
    "x": 540,
    "y": 1200
  },
  "elapsed_ms": 342,
  "settle_delay_ms": 300,
  "timestamp": "2025-02-19T14:32:01.123Z",
  "device": "Pixel_7_API_34",
  "files": {
    "pre_screenshot": "pre.png",
    "post_screenshot": "post.png",
    "pre_tree": "pre_tree.json",
    "post_tree": "post_tree.json"
  }
}
```

For executor layer, additional fields:

```json
{
  "target": {
    "type": "element_index",
    "value": 5,
    "resolved_to": {"x": 540, "y": 460},
    "element_info": "Wi-Fi Switch [0,400,1080,520]"
  },
  "warnings": ["Occlusion detected: smaller clickable at center, using left-center (100, 460)"]
}
```

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| A11y service not running when broadcast arrives | Medium | Blocks test | Script checks service status first: `adb shell dumpsys accessibility` |
| goAsync() 30s timeout too short | Low | Incomplete result | Single action + 2 screenshots easily fits in 5s |
| File write permission on `/sdcard/` | Low | No output | Use app-specific external dir which needs no extra permissions |
| Race condition: script pulls before receiver finishes writing | Medium | Incomplete data | Receiver writes a `.done` sentinel file last; script polls for it |
| Debug receiver in release build | Low | Security risk | Use `debug/AndroidManifest.xml` for receiver registration |

---

## Non-Goals (Explicitly Out of Scope)

1. **Automated pass/fail assertions** — This is a debugging tool, not a test framework. Visual inspection of screenshots is the verification method.
2. **CI/CD integration** — Debug-only, human-in-the-loop workflow.
3. **LLM or agent changes** — Zero changes to agent reasoning, prompt, or orchestration.
4. **VirtualDisplayPlatform support in Phase 1** — Focus on AccessibilityPlatform first (the one with known reliability issues). VD support is Phase 3.
5. **Production code changes** — No modifications to any existing executor, tool, or platform code. The debug harness is purely additive.

---

## Success Criteria

1. **From any screen state**, a developer can execute a single action via CLI in <3 seconds (excluding build)
2. Before/after screenshots are automatically captured and saved
3. Action result (success/failure, attempt trail) is captured as structured JSON
4. ADB baseline comparison is available via `--compare` or `--adb` flag
5. A11y tree is dumpable on demand for target resolution debugging
6. Zero impact on production code paths

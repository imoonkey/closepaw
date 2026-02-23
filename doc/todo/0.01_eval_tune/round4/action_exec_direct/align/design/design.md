# Action Execution Direct Debug Harness — Aligned Design

## Status

ALIGNED DRAFT — merged from Codex (`20260219_action_exec_direct_codex.md`) and Claude (`tech_design_claude.md`) proposals with code-verified corrections.

## Problem

Action execution (click, long_press, scroll, swipe) frequently reports success but UI does not change (false success). Current debugging requires launching a full agent task (LLM reasoning → tool selection → action), which is slow and non-deterministic.

After Round 2 click redesign and Round 3 scroll/swipe split — both implemented — false successes persist. The issue is at the execution layer, not the LLM brain. We need to isolate and debug this layer directly.

## Goal

A **zero-LLM, deterministic, CLI-driven** debug harness that:

1. Executes a single action on the **current screen state** (no agent loop, no task)
2. Captures before/after screenshots + a11y tree
3. Explicitly separates "action was accepted" from "UI actually changed" (catches false success)
4. Supports adb baseline comparison (same action via stable `input tap/swipe`)
5. Works at two in-app layers: **platform** (raw UIAction) and **executor** (target resolution + fallbacks)

## Non-Goals

1. No LLM prompt/cognition changes
2. No executor logic refactoring (this harness tests the current implementation as-is)
3. No production code modifications — debug harness is purely additive
4. No CI/CD integration — manual human-in-the-loop debugging

## Final Decisions

1. **Receiver approach**: Dynamic BroadcastReceiver registered in `AgentService.onServiceConnected()`, matching existing `STOP_AGENT` pattern. Gated by `BuildConfig.DEBUG`.
2. **Intent action**: `com.moonkey.androidagent.ACTION_DEBUG_EXEC`
3. **Intent extras**: Individual typed extras via `--es`/`--ei`/`--ez` (no JSON string parsing, simpler adb broadcast invocation).
4. **App-side classes**: Two files — `ActionDebugReceiver.kt` + `DebugActionExecutor.kt`. Effect analysis and artifact writing are methods within these classes, not separate classes.
5. **Output path**: `/sdcard/Android/data/com.moonkey.androidagent/files/action-debug/latest/`. Always overwrites `latest/`, no run_id archiving in Phase 1.
6. **Script name**: `scripts/action-test.sh`
7. **Layer names**: `platform` (L1: direct UIAction → AccessibilityPlatform primitives) and `executor` (L2: target resolution + Executor full path). `adb` (L0) runs on host side.
8. **Result schema**: Explicit split of `action_accepted` (transport-level success) vs `ui_changed` (effect verification). This is the key signal for false success detection.
9. **dump-tree command**: Included — captures a11y tree without executing any action.
10. **Concurrency**: Reject debug action with error if agent session is running. No takeover — too risky for debug isolation.
11. **Sentinel pattern**: Receiver writes `.done` file last; host script polls for it.
12. **No feature flag**: Direct code + manifest gating via `BuildConfig.DEBUG`, clean removal via debug build variant.
13. **`element_index` contract**: `element_index` is Perceptor-derived snapshot index (not stable AccessibilityNodeInfo id). Executor-layer index targeting is valid only against the pre-snapshot captured in the same run.
14. **Screenshot source of truth (Phase 1)**: before/after PNG captured on host via adb (`exec-out screencap`), not app-side screenshot capture.
15. **Tree format default**: `dump-tree` and executor debugging default to Perceptor-processed format (matches executor index semantics). Raw a11y tree is optional artifact for deep platform debugging.

## Why These Decisions Match Current Code

1. `AgentService` (extends `AccessibilityService`) already has `companion object` with `@Volatile var instance: AgentService?` — direct access from receiver.
2. Existing `STOP_AGENT` receiver uses `registerDebugStopReceiverIfNeeded()` in `AgentServiceReceiverHelpers.kt` — same pattern for debug exec receiver.
3. `AccessibilityPlatform` constructor requires `SessionConfig` which is heavyweight. `DebugActionExecutor` sidesteps this by directly composing `NodeActionPerformer` + `AccessibilityGestureInjector` from the service — same primitives, no session dependency.
4. `NodeActionPerformer(rootProvider = { service.rootInActiveWindow })` is the exact pattern used in production `AccessibilityPlatform` constructor (line 46).
5. `AccessibilityGestureInjector(service, visualizer = null)` works with null visualizer — no overlay needed for debug.

## Architecture

```
                     Host Machine (Mac)
               ┌────────────────────────────────┐
               │   scripts/action-test.sh       │
               │                                │
               │   L0 (adb):                    │
               │     screencap → input tap →    │
               │     screencap                  │
               │                                │
               │   L1/L2 (app-side):            │
               │     screencap →                │
               │     am broadcast ──────────────┼──────┐
               │     poll .done →               │      │
               │     pull results               │      │
               └────────────────────────────────┘      │
                                                        │
                     Android Device                     │
               ┌────────────────────────────────┐      │
               │  AgentService                  │      │
               │    └─ ActionDebugReceiver      │◄─────┘
               │         │                      │
               │         └─ DebugActionExecutor │
               │              │                 │
               │   L1 (platform):               │
               │     UIAction → performAction() │
               │     (NodeActionPerformer or     │
               │      AccessibilityGestureInj.) │
               │                                │
               │   L2 (executor):               │
               │     ClickExecutor /            │
               │     ScrollExecutor /           │
               │     SwipeExecutor /            │
               │     LongPressExecutor          │
               └────────────────────────────────┘
```

## Three Execution Layers

| Layer | What It Tests | Entry Point | Bypasses |
|-------|---------------|-------------|----------|
| **L0: adb** | Baseline sanity — "does the screen respond at all?" | `adb shell input tap/swipe` on host | Everything app-side |
| **L1: platform** | Gesture injection, node actions, a11y API reliability | `DebugActionExecutor.performAction(UIAction)` | Target resolution, fallback chains, executors |
| **L2: executor** | Target resolution, fallback logic, full tool pipeline | `ClickExecutor.execute(target, snapshot, platform)` | Only LLM/turn orchestration |

For any given test: L0 alone (adb baseline), L1 alone, L2 alone, or **compare L1 vs L0** to isolate accessibility API issues.

## Intent Contract

**Action**: `com.moonkey.androidagent.ACTION_DEBUG_EXEC`

### Common Extras

| Extra Key | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `exec_layer` | String | No | `platform` | `platform` or `executor` |
| `action` | String | Yes | — | `click`, `tap`, `long_press`, `scroll`, `swipe`, `dump_tree` |
| `capture_tree` | Boolean | No | `true` | Capture a11y tree before/after |
| `settle_ms` | Int | No | `350` | Post-action settle delay before post-capture |

### Action-Specific Extras

| Action | Required | Optional |
|--------|----------|----------|
| `click` | `x`, `y` (Int) | `use_node` (Boolean, default true → ClickNodeAt; false → TapAt) |
| `tap` | `x`, `y` (Int) | — |
| `long_press` | `x`, `y` (Int) | `duration_ms` (Int, default 1000) |
| `scroll` | `direction` (String: up/down/left/right) | `x`, `y` (Int, default screen center) |
| `swipe` | `start_x`, `start_y`, `end_x`, `end_y` (Int) | `duration_ms` (Int, default 400) |
| `dump_tree` | — | `format` (String: `json` or `prompt`, default `json`) |

### Executor-Layer Extras (when `exec_layer=executor`)

| Extra Key | Type | Description |
|-----------|------|-------------|
| `element_index` | Int | Target by element index (requires snapshot) |
| `text` | String | Target by text match |
| `text_index` | Int | Disambiguation for text targets (default 0) |

## Result Artifact Schema

Written to `/sdcard/Android/data/com.moonkey.androidagent/files/action-debug/latest/result.json`:

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
  "action_accepted": {
    "status": "Success",
    "message": "Action completed"
  },
  "ui_changed": {
    "a11y_changed": true,
    "a11y_hash_before": "a1b2c3d4",
    "a11y_hash_after": "e5f6g7h8",
    "verdict": "changed"
  },
  "attempt_trail": [
    "node_action_click: success"
  ],
  "elapsed_ms": 342,
  "settle_ms": 350,
  "timestamp": "2026-02-19T14:32:01.123Z",
  "device": "Pixel_7",
  "files": {
    "pre_tree": "pre_tree.json",
    "post_tree": "post_tree.json",
    "raw_pre_tree": "raw_pre_tree.json",
    "raw_post_tree": "raw_post_tree.json"
  }
}
```

**Key insight**: `action_accepted.status == "Success"` + `ui_changed.verdict == "unchanged"` = **false success**. This is the primary signal we're hunting.

**Verdict rules**:
- Both a11y hash and visual change → `changed`
- Neither changed → `unchanged`
- Only one changed → `inconclusive`
- Capture failed → `unverifiable`

For executor layer, additional fields:

```json
{
  "target": {
    "type": "element_index",
    "value": 5,
    "resolved_to": {"x": 540, "y": 460},
    "element_text": "Wi-Fi",
    "element_bounds": "0,400,1080,520"
  },
  "warnings": ["Element center occluded, using left-center"]
}
```

## Output Directory Layout

```
/sdcard/Android/data/com.moonkey.androidagent/files/action-debug/
  latest/             ← always cleared + overwritten per execution
    result.json
    pre_tree.json      ← if capture_tree=true
    post_tree.json     ← if capture_tree=true
    raw_pre_tree.json  ← optional
    raw_post_tree.json ← optional
    tree_prompt.txt    ← if action=dump_tree && format=prompt
    .done              ← sentinel, written last
```

Host-side pulled to:

```
debug-output/action-test/{tag}/
  result.json
  before_a11y.png / after_a11y.png
  before_adb.png / after_adb.png
  pre_tree.json / post_tree.json
```

## Execution Semantics

### L1: Platform Layer

```
1. Clear latest/ directory
2. Capture pre-snapshot (optional a11y tree)
3. Map intent extras → UIAction:
     click(use_node=true) → UIAction.ClickNodeAt(x, y)
     click(use_node=false) / tap → UIAction.TapAt(x, y)
     long_press → UIAction.Swipe(x, y, x, y, duration_ms)  // swipe-to-self
     scroll → UIAction.ScrollNodeAt(x, y, direction)
     swipe → UIAction.Swipe(start_x, start_y, end_x, end_y, duration_ms)
4. DebugActionExecutor.performAction(uiAction) → ActionResult
5. delay(settle_ms)
6. Capture post-snapshot (optional a11y tree)
7. Compute ui_changed verdict (a11y hash comparison)
8. Write result.json + artifacts
9. Write .done sentinel
```

### L2: Executor Layer

```
1. Clear latest/ directory
2. Capture pre-snapshot (full ScreenSnapshot with elements for target resolution)
3. Build Target from extras:
     element_index → Target.ElementIndex(index)
     text → Target.Text(text, text_index)
     x, y → Target.Coordinate(x, y)
4. Executor.execute(target, snapshot, platform, isCancelled={false})
     → internally does target resolution, node/gesture dispatch, fallback
5. delay(settle_ms)
6. Capture post-snapshot
7. Compute verdict
8. Write result.json + artifacts (including attempt_trail from executor)
9. Write .done
```

### L0: ADB Baseline (Host Script Only)

```
1. adb exec-out screencap -p > before_adb.png
2. adb shell input <tap|swipe> <coords>
3. sleep settle_delay
4. adb exec-out screencap -p > after_adb.png
```

No app-side code involved. Provides stable baseline for comparison.

## Host Script CLI

### Location

`scripts/action-test.sh`

### Usage

```bash
# L0: ADB baseline
./scripts/action-test.sh tap --x 540 --y 1200 --adb
./scripts/action-test.sh swipe --start-x 540 --start-y 1400 --end-x 540 --end-y 600 --adb

# L1: Platform (default layer)
./scripts/action-test.sh click --x 540 --y 1200
./scripts/action-test.sh click --x 540 --y 1200 --use-node false    # gesture only (TapAt)
./scripts/action-test.sh click --x 540 --y 1200 --use-node true     # node action (ClickNodeAt)
./scripts/action-test.sh long_press --x 540 --y 800 --duration 1500
./scripts/action-test.sh scroll --direction down
./scripts/action-test.sh scroll --direction down --x 540 --y 960
./scripts/action-test.sh swipe --start-x 540 --start-y 1400 --end-x 540 --end-y 600

# L2: Executor (target resolution + fallbacks)
./scripts/action-test.sh click --element-index 5 --layer executor
./scripts/action-test.sh scroll --direction down --element-index 3 --layer executor
./scripts/action-test.sh click --text "Wi-Fi" --layer executor

# A11y tree dump (no action)
./scripts/action-test.sh dump-tree
./scripts/action-test.sh dump-tree --format prompt

# Compare: a11y vs adb side-by-side
./scripts/action-test.sh click --x 540 --y 1200 --compare

# Options
./scripts/action-test.sh click --x 540 --y 1200 --tag "wifi_toggle"   # named output dir
./scripts/action-test.sh click --x 540 --y 1200 --open                # auto-open images
./scripts/action-test.sh click --x 540 --y 1200 --no-tree             # skip a11y tree
./scripts/action-test.sh click --x 540 --y 1200 --settle 500          # custom settle delay
```

`element_index` usage note:
- Get index from immediate `dump-tree --format prompt` output.
- Then execute executor action without changing screen state.
- Do not reuse an old index across runs/screens; index is snapshot-relative.

### ADB Command Mapping (for L0 and --compare)

| Action | ADB Command |
|--------|-------------|
| `tap` / `click` | `input tap {x} {y}` |
| `long_press` | `input swipe {x} {y} {x} {y} {duration_ms}` |
| `swipe` | `input swipe {x1} {y1} {x2} {y2} {duration_ms}` |
| `scroll down` | `input swipe {cx} {cy+d} {cx} {cy-d} 400` |
| `scroll up` | `input swipe {cx} {cy-d} {cx} {cy+d} 400` |

### Compare Mode

For `--compare`, the script runs both adb and a11y on the same screen state:

1. Capture before, run adb action, capture after
2. **Pause and prompt** user to reset screen to same state
3. Capture before, run a11y action, capture after
4. Save both sets of screenshots side by side

```
[adb] Action executed. Screenshots saved to debug-output/action-test/{tag}/
Reset the screen to the same state, then press Enter...
[a11y] Action executed. Results saved.
```

Interactive prompt is acceptable — this is a manual debugging workflow.

## App-Side Implementation

### Component 1: ActionDebugReceiver

**Location**: `app/src/main/kotlin/com/moonkey/androidagent/debug/ActionDebugReceiver.kt`

```kotlin
class ActionDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DEBUG_EXEC) return

        // Reject if agent session is active
        val service = AgentService.instance
        if (service == null) {
            writeError("Accessibility service not running")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            try {
                val executor = DebugActionExecutor(service)
                executor.execute(intent, context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_DEBUG_EXEC = "com.moonkey.androidagent.ACTION_DEBUG_EXEC"
    }
}
```

### Component 2: DebugActionExecutor

**Location**: `app/src/main/kotlin/com/moonkey/androidagent/debug/DebugActionExecutor.kt`

Directly composes execution primitives from `AgentService`:

```kotlin
class DebugActionExecutor(private val service: AgentService) {

    private val nodeFinder = AccessibilityNodeFinder()
    private val rootProvider = { service.rootInActiveWindow }
    private val nodePerformer = NodeActionPerformer(rootProvider)
    private val gestureInjector = AccessibilityGestureInjector(service, visualizer = null)

    suspend fun performAction(action: UIAction): ActionResult {
        return when (action) {
            is UIAction.ClickNodeAt -> nodePerformer.performNodeClickAt(action.x, action.y)
            is UIAction.TapAt -> gestureInjector.injectTap(action.x, action.y)
            is UIAction.LongClickNodeAt -> nodePerformer.performNodeLongClickAt(action.x, action.y)
            is UIAction.LongPressAt -> gestureInjector.injectLongPress(...)
            is UIAction.ScrollNodeAt -> nodePerformer.performScrollAt(action.x, action.y, action.direction)
            is UIAction.Swipe -> gestureInjector.injectSwipe(...)
            is UIAction.SystemButton -> gestureInjector.injectSystemButton(action.button)
            is UIAction.Wait -> { delay(action.durationMs); ActionResult.Success() }
            // SetText variants as needed
        }
    }

    suspend fun captureScreenshot(): Bitmap { ... }   // via screenshotCapturer or display API
    suspend fun captureA11yTree(): JSONObject { ... }  // walk rootInActiveWindow
}
```

**Key**: No `SessionConfig`, no `TraceRecorder`, no `PerceptionConfig`, no `ActionVisualizerManager`. Just the raw execution primitives.

### Registration in AgentService

Follow existing `STOP_AGENT` pattern in `AgentServiceReceiverHelpers.kt`:

```kotlin
// New helper functions (same file)
internal fun registerDebugExecReceiverIfNeeded(service: AgentService, receiver: BroadcastReceiver) {
    if (!BuildConfig.DEBUG) return
    val filter = IntentFilter(ActionDebugReceiver.ACTION_DEBUG_EXEC)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        service.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        service.registerReceiver(receiver, filter)
    }
}
```

## Concurrency & Safety

1. **Debug-build only**: Registration gated by `BuildConfig.DEBUG`
2. **Agent session check**: If `session != null` and session is running, write error `{"status": "busy", ...}` and return. No takeover.
3. **Execution timeout**: 10s overall timeout wrapping the full execute flow (action + settle + capture). goAsync() gives 30s, but we should fail fast.
4. **Serial execution**: Simple approach — receiver processes one at a time via `Dispatchers.Main` (inherently serial for gestures).

## Debugging Workflows

### Workflow 1: Spot Check (Fastest Loop)

```bash
# See something on screen, test click
./scripts/action-test.sh click --x 540 --y 1200 --tag test1 --open
# → Opens before.png + after.png, prints result summary
# → If "Success" but UI unchanged: false success found
```

### Workflow 2: A/B — Node vs Gesture vs ADB

```bash
# 1. Node action click
./scripts/action-test.sh click --x 540 --y 1200 --use-node true --tag node
# Reset screen
# 2. Gesture tap
./scripts/action-test.sh click --x 540 --y 1200 --use-node false --tag gesture
# Reset screen
# 3. ADB tap baseline
./scripts/action-test.sh tap --x 540 --y 1200 --adb --tag adb

# Compare results
jq '.action_accepted.status, .ui_changed.verdict' debug-output/action-test/node/result.json
jq '.action_accepted.status, .ui_changed.verdict' debug-output/action-test/gesture/result.json
```

### Workflow 3: Scroll Debugging (Most Failure-Prone)

```bash
# 1. Dump tree to verify scrollable containers
./scripts/action-test.sh dump-tree --format prompt

# 2. L1: a11y scroll (ACTION_SCROLL_DOWN)
./scripts/action-test.sh scroll --direction down --tag scroll_a11y

# 3. L1: gesture scroll (swipe up to scroll down)
./scripts/action-test.sh swipe --start-x 540 --start-y 1400 --end-x 540 --end-y 600 --tag scroll_gesture

# 4. L0: adb baseline
./scripts/action-test.sh swipe --start-x 540 --start-y 1400 --end-x 540 --end-y 600 --adb --tag scroll_adb
```

### Workflow 4: Executor Target Resolution

```bash
# 1. Dump tree to see element indices
./scripts/action-test.sh dump-tree --format prompt
# → [5] Wi-Fi (Switch) bounds=0,400,1080,520

# 2. Test executor path with element_index
./scripts/action-test.sh click --element-index 5 --layer executor --tag wifi_exec

# 3. Check target resolution
jq '.target' debug-output/action-test/wifi_exec/result.json
```

## File-Level Change Plan

### New Files

| File | Description | Est Lines |
|------|-------------|-----------|
| `app/src/main/kotlin/com/moonkey/androidagent/debug/ActionDebugReceiver.kt` | BroadcastReceiver: parse intent → dispatch | ~60 |
| `app/src/main/kotlin/com/moonkey/androidagent/debug/DebugActionExecutor.kt` | Execute action + capture + write results | ~180 |
| `scripts/action-test.sh` | Host-side CLI | ~280 |

### Modified Files

| File | Change |
|------|--------|
| `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt` | Add `debugExecReceiver` field, register/unregister in lifecycle |
| `app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceReceiverHelpers.kt` | Add `registerDebugExecReceiverIfNeeded()` / `unregisterDebugExecReceiverIfNeeded()` |

### Unchanged (Zero Production Impact)

All existing executors, tools, platforms, UIAction types, ActionResult types — untouched.

## Phases

### Phase 1: MVP (Core Debug Loop)

Deliverables:
- `ActionDebugReceiver` + `DebugActionExecutor` — L1 (platform) layer only
- App-side: a11y tree capture + result.json
- Host-side: pre/post screenshot via adb
- `result.json` with `action_accepted` + `ui_changed` split
- `.done` sentinel
- `scripts/action-test.sh` — default mode (broadcast → poll → pull → summary)
- `--adb` mode (L0 baseline)
- `--tag` and `--open` flags

**After Phase 1**: You can do single-action spot checks and L0 vs L1 comparison in <5 seconds.

### Phase 2: Compare + Executor + dump-tree

Deliverables:
- `--compare` mode (adb + a11y side-by-side with prompt)
- `--layer executor` (L2 path with target resolution)
- `dump-tree` command (json + prompt formats)
- `--use-node` flag for click

### Phase 3: Batch + Stats (On-demand)

Deliverables:
- Batch runner: execute N tests from a script, aggregate results
- Success rate matrix per action type × layer
- Flaky action identification

## Verification Gate

### Phase 1 Smoke Test

```bash
# 1. Build + install
./scripts/setup.sh

# 2. Open Settings
adb shell am start -a android.settings.SETTINGS && sleep 2

# 3. Click test
./scripts/action-test.sh click --x 540 --y 400 --tag smoke

# 4. Verify
ls debug-output/action-test/smoke/
# → pre.png  post.png  pre_tree.json  post_tree.json  result.json

jq '.action_accepted.status, .ui_changed.verdict' debug-output/action-test/smoke/result.json
# → "Success"  "changed" (or "unchanged" if false success)

# 5. ADB baseline
./scripts/action-test.sh tap --x 540 --y 400 --adb --tag smoke_adb

# 6. Visual compare
open debug-output/action-test/smoke/post.png
open debug-output/action-test/smoke_adb/after_adb.png
```

## Success Criteria

1. Single action debug loop completes in **<5 seconds** (no agent task, no LLM)
2. One CLI command produces before/after screenshots + structured result.json
3. `action_accepted` vs `ui_changed` split **explicitly identifies false successes**
4. Same coordinates can be tested via both a11y (L1) and adb (L0) for comparison
5. Zero changes to any production execution path

## Alignment Status

Open design disagreements: none.
Next step: implement Phase 1.

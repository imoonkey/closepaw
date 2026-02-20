# Brightness Dialog Swipe Deep Analysis

**Date**: 2026-02-20
**Eval Runs Analyzed**:
- Failing: `20260220_013305` (SystemBrightnessMin — dialog swipe)
- Passing: `20260220_013305` (SystemBrightnessMax — QS panel swipe)
- Re-run: `20260220_014243` (same results after Handler revert)
- Baseline: `20260219_185400` (both brightness tasks passed)

## Summary

The Settings brightness dialog SeekBar does **not respond** to `dispatchGesture`-injected swipe gestures. The gesture machinery reports success (`dispatched=true`, `onCompleted` callback fires), but the SeekBar value remains unchanged at 65535 (100%). Meanwhile, the Quick Settings panel SeekBar responds perfectly to the same gesture code path. The issue is **not in our code** — it's a framework-level or emulator-level behavior difference between the two SeekBar contexts.

## Root Cause Classification

**Category: Execution (platform limitation) + Cognition (suboptimal approach selection)**

The dialog swipe failure is caused by an Android framework behavior where `AccessibilityService.dispatchGesture()` injects touch events that are not properly delivered to the SeekBar within the Settings brightness dialog. This appears to be a **window-layer routing issue** specific to modal dialogs. The cognition component is that the agent chose the Settings dialog approach instead of the more reliable Quick Settings approach.

## Evidence

### 1. PERCEPTION: Correct

Both contexts show identical SeekBar properties:

| Property | Settings Dialog | QS Panel |
|----------|---------------|----------|
| class | SeekBar | SeekBar |
| text | Display brightness | Display brightness |
| range_min | 0 | 0 |
| range_max | 65535 | 65535 |
| bounds width | 996px (42→1038) | 996px (42→1038) |
| bounds height | 126px | 126px |
| rootPkg | com.android.systemui | com.android.systemui |

Dialog bounds: `[42, 149, 1038, 275]`, center: `[540, 212]`
QS bounds: `[42, 357, 1038, 483]`, center: `[540, 420]`

Perception is **correct and accurate** in both cases.

### 2. COORDINATE FLOW: Correct, No Clamping

Full code path: `SwipeExecutor → UIAction.Swipe → AccessibilityPlatform.performSwipe() → clamp check → GestureInjector.injectSwipe() → dispatchGesture()`

- `AccessibilityPlatform.performSwipe()` clamps to `[0, widthPixels-1] x [0, heightPixels-1]` — no clamping logged ("clamped" not found in any logcat)
- Coordinates pass through unchanged: tool_call_args `(1038,212)→(42,212)` matches logcat `from=1038.0,212.0,to=42.0,212.0`
- All within SeekBar bounds `[42, 149, 1038, 275]`

### 3. GESTURE DISPATCH: Succeeds But No Effect

From logcat (`aw_20260220_013305_SystemBrightnessMin_1_0/logcat.log`):

```
01:35:40.664 dispatchGesture start: displayId=0, strokeCount=1,
  strokes=#0(start=0,dur=400,len=996.0,from=1038.0,212.0,to=42.0,212.0),
  rootPkg=com.android.systemui, serviceFlags=65, serviceCapabilities=161
01:35:40.669 dispatchGesture dispatched=true
01:35:41.130 dispatchGesture completed: displayId=0
```

Every swipe attempt:
- `displayId=0` ✓
- `dispatched=true` ✓
- `completed` callback fires ✓
- `rootPkg=com.android.systemui` ✓ (dialog is active window)
- But SeekBar `range_current` stays at 65535

### 4. AGENT RETRY PATTERN: 8+ Swipe Attempts, All Failed

| Timestamp | Coordinates | Duration | Result |
|-----------|------------|----------|--------|
| 01:35:40 | (1038,212)→(42,212) | 400ms | No effect, dialog closes |
| 01:35:54 | (1038,212)→(50,212) | 500ms | No effect |
| 01:36:13 | (42,212)→(45,212) | 100ms | No effect (3px micro-swipe) |
| 01:36:26 | (1038,212)→(42,212) | 800ms | No effect |
| 01:36:39 | (540,212)→(42,212) | 1000ms | No effect (from center) |
| 01:37:17 | (1038,212)→(42,212) | 2000ms | No effect (very slow) |
| 01:37:51 | (1038,212)→(42,149) | 600ms | No effect (diagonal) |

Agent varied: start position, end position, duration, direction — **nothing worked**.

### 5. BASELINE COMPARISON

**Baseline BrightnessMax (20260219_185400)**: Settings dialog swipe `(42,212)→(1038,212)` over 400ms — **SUCCESS**, range_current changed from 0 to 65535.

**Baseline BrightnessMin (20260219_185400)**: Used QS panel approach (NOT dialog). Swipe `(1000,420)→(50,420)` over 400ms — **SUCCESS**, range_current dropped from 65535 to 526.

Key difference: In the baseline, the dialog swipe was LEFT-TO-RIGHT (min→max). In the failure, it's RIGHT-TO-LEFT (max→min). BUT: the center-starting swipe `(540,212)→(42,212)` also failed, ruling out a start-position issue.

### 6. CODE DIFF: Functionally Identical to Baseline

After reverting `setDisplayId()` and `Handler(Looper.getMainLooper())`, the current code differs from the baseline only in:
1. `DEFAULT_TAP_DURATION_MS = ViewConfiguration.getTapTimeout().toLong()` vs `DEFAULT_GESTURE_DURATION_MS = 100L` — affects taps only, not swipes
2. Diagnostic logging (harmless)
3. `buildGesture()` extracted to method (functionally identical inline expansion)

**The swipe code path is functionally identical to baseline.**

### 7. VISUALIZER OVERLAY: Not Interfering

`VisualizerOverlayHost` uses `FLAG_NOT_TOUCHABLE` in `WindowManager.LayoutParams`:
```kotlin
WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
```

Overlay is **transparent to touch events** — confirmed not interfering.

### 8. QS PANEL SWIPE: Works in Same Run

```
01:34:00.578 dispatchGesture start: displayId=0, strokeCount=1,
  strokes=#0(start=0,dur=400,len=900.0,from=100.0,420.0,to=1000.0,420.0),
  rootPkg=com.android.systemui, serviceFlags=65, serviceCapabilities=161
01:34:00.578 dispatchGesture dispatched=true
01:34:01.023 dispatchGesture completed: displayId=0
```

Identical gesture machinery, identical package, same service flags/capabilities. **QS swipe works, dialog swipe doesn't.**

## Analysis: Why Dialog Swipe Fails

### Hypothesis: Dialog Window Touch Routing

The Settings brightness dialog is a **modal dialog** (likely an `AlertDialog` or `DialogFragment`) with a specific window type and layout. Key factors:

1. **Window layering**: The dialog window sits above the Settings activity. `dispatchGesture()` injects MotionEvents at the system level, and the input system routes them to the topmost window at the touch coordinates. The dialog should receive them, but its internal touch handling may differ.

2. **Dialog dismiss-on-touch**: Android dialogs typically have `setCanceledOnTouchOutside(true)`. The dialog's `onTouchEvent` checks if the touch is within the dialog's **decorated area** (including padding/borders). The SeekBar's accessibility bounds `[42,149,1038,275]` may extend beyond the dialog's actual touchable region — causing ACTION_DOWN to trigger outside-touch dismissal.

3. **SeekBar touch delegate**: The SeekBar might use a touch delegate with different bounds than the accessibility-reported bounds. If the touch delegate restricts the interactive area, injected gestures at SeekBar edge coordinates might miss.

4. **Emulator-specific behavior**: This could be an emulator timing artifact where the dialog's window isn't fully interactive when the gesture is dispatched, despite appearing in the accessibility tree.

### Why It Worked in Baseline

Possible explanations:
- **Emulator state**: The emulator may have been in a slightly different state (fresh boot, different system animation scale, etc.)
- **Timing**: The baseline may have had slightly different timing between dialog open and swipe, allowing the dialog to fully settle
- **Flaky behavior**: The dialog swipe might be inherently unreliable, working sometimes and failing other times

## Recommendations

### Immediate Fix: Guide Agent to QS Approach (Cognition)

The most reliable fix is to ensure the agent uses the Quick Settings panel for brightness adjustments. Both baseline and current runs show QS swipes working reliably.

**Option A**: Add a hint to the agent's system prompt or context packing that brightness is best adjusted via Quick Settings.

**Option B**: Add brightness-specific guidance to the tool description or planner policy.

### Medium-term Fix: Node-based SeekBar Interaction (Tool)

Add support for `AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD` / `ACTION_SCROLL_FORWARD` on SeekBars. This bypasses gesture injection entirely and uses the accessibility API's native scroll actions.

```kotlin
// In NodeActionPerformer or a new SeekBarExecutor
fun performScrollOnSeekBar(x: Int, y: Int, direction: ScrollDirection): ActionResult {
    val node = findNodeAt(root, x, y)
    val action = when (direction) {
        ScrollDirection.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        ScrollDirection.BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    }
    return if (node?.performAction(action) == true) {
        ActionResult.Success("SeekBar scrolled $direction")
    } else {
        ActionResult.Failure("Failed to scroll SeekBar")
    }
}
```

Advantages: Works regardless of window type, no coordinate issues, no gesture routing concerns.

### Long-term Fix: Hybrid Gesture + Node Strategy

For slider-type elements (SeekBars), detect when the target element is a SeekBar and:
1. First try `ACTION_SET_PROGRESS` (API 24+) for exact value setting
2. Fall back to `ACTION_SCROLL_BACKWARD/FORWARD` for incremental adjustment
3. Only use gesture swipe as last resort

## Eval Run Status Summary

| Run | Task | Approach | Result | Root Cause |
|-----|------|----------|--------|------------|
| 20260219_185400 | BrightnessMax | Dialog swipe | PASS | — |
| 20260219_185400 | BrightnessMin | QS swipe | PASS | — |
| 20260220_013305 | BrightnessMax | QS swipe | PASS* | *99.7% not exact max |
| 20260220_013305 | BrightnessMin | Dialog swipe | FAIL | Dialog gesture non-responsive |
| 20260220_014243 | BrightnessMax | QS swipe | PASS | — |
| 20260220_014243 | BrightnessMin | Dialog swipe | FAIL | Dialog gesture non-responsive |

## Files Analyzed

### Code Path
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/SwipeExecutor.kt` — raw coord → UIAction.Swipe
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt` — action routing
- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt` — coord clamping → injectSwipe
- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityGestureInjector.kt` — gesture dispatch
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ClickExecutor.kt` — dialog open path
- `app/src/main/kotlin/com/moonkey/androidagent/platform/NodeActionPerformer.kt` — node click impl
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/VisualizerOverlayHost.kt` — overlay flags

### Trace Artifacts
- `eval/results/20260220_013305/artifacts/aw_20260220_013305_SystemBrightnessMin_1_0/` — failing run
- `eval/results/20260220_013305/artifacts/aw_20260220_013305_SystemBrightnessMax_0_0/` — passing QS run
- `eval/results/20260219_185400/artifacts/aw_20260219_185400_SystemBrightnessMax_10_0/` — baseline dialog
- `eval/results/20260219_185400/artifacts/aw_20260219_185400_SystemBrightnessMin_11_0/` — baseline QS

### Git History
- `e745664 fix: harden dispatchGesture tap path and add diagnostics` — introduced setDisplayId + Handler (reverted)
- `17ffc34 fix: stabilize node long-press path and simplify target resolution` — node action changes (not reverted, unlikely relevant)

# Regression Analysis & Refactoring Strategy: Eval Runs Comparison

**Baseline**: eval/results/20260219_185400 — 85.7% (12/14)
**Regression**: eval/results/20260220_000105 — 50.0% (7/14)
**Analysis Date**: 2026-02-20
**Author**: Claude (independent analysis, no Codex docs referenced)

---

## 1. Executive Summary

The 35.7% drop in pass rate (12/14 → 7/14) is caused by three distinct issues:

| Root Cause | Tasks Affected | Fix Complexity |
|-----------|---------------|----------------|
| `dispatchGesture` regression from `setDisplayId()` | SystemBrightnessMax, SystemBrightnessMin | **P0 — Simple revert** |
| OpenRouter API rate limit (403) | SystemWifiTurnOff, SystemWifiTurnOn | **Infrastructure — Not code** |
| LLM cognition limitations (qwen3.5) | SimpleSmsSend, BrowserMultiply | **P1 — Prompt tuning** |

**True code regression**: 2 tasks (14.3% of total), caused by a single commit (`e745664`).

---

## 2. Code Changes Between Runs

Two commits were introduced between the baseline run and the regression run:

### Commit `e745664`: harden dispatchGesture tap path and add diagnostics

**File**: `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityGestureInjector.kt`

Changes:
1. `DEFAULT_GESTURE_DURATION_MS = 100L` → `DEFAULT_TAP_DURATION_MS = ViewConfiguration.getTapTimeout().toLong()`
2. **New `buildGesture()` method** that calls `setDisplayId()` on API 30+
3. **New `resolveDisplayId()`** that queries `service.rootInActiveWindow?.window?.displayId`
4. Changed `dispatchGesture()` callback handler from `null` → `Handler(Looper.getMainLooper())`
5. Added diagnostic logging (`describeGesture()`, display ID logging)

### Commit `17ffc34`: stabilize node long-press path and simplify target resolution

**Files**: LongPressExecutor.kt, TargetResolver.kt, NodeActionPerformer.kt

Changes:
1. LongPressExecutor now tries `UIAction.LongClickNodeAt` (node path) before gesture fallback
2. TargetResolver simplified to pure function
3. NodeActionPerformer `performNodeLongClickAt()` fallback: tries long-clickable node, then clickable node with ACTION_LONG_CLICK

---

## 3. Root Cause: `setDisplayId()` Breaks Gesture Dispatch

### The Mechanism

In `AccessibilityGestureInjector.buildGesture()` (line 157-164):

```kotlin
private fun buildGesture(stroke: GestureDescription.StrokeDescription): GestureDescription {
    val builder = GestureDescription.Builder().addStroke(stroke)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val displayId = resolveDisplayId() ?: Display.DEFAULT_DISPLAY
        builder.setDisplayId(displayId)
    }
    return builder.build()
}
```

`resolveDisplayId()` (line 166-169):
```kotlin
private fun resolveDisplayId(): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return service.rootInActiveWindow?.window?.displayId
}
```

### Why This Breaks Swipes

1. **Before the change**: `GestureDescription.Builder.build()` doesn't call `setDisplayId()`, so the framework routes gestures to the default display implicitly.

2. **After the change**: The code explicitly queries `service.rootInActiveWindow?.window?.displayId`:
   - When a dialog/overlay (like the brightness slider popup) is the active window, `rootInActiveWindow` may report a different window context
   - The `window?.displayId` can return values that don't match the physical display where the SeekBar is rendered
   - Even if `displayId` returns `DEFAULT_DISPLAY` (0), the explicit `setDisplayId(0)` call may interact differently with the gesture dispatch pipeline than omitting it entirely
   - If `rootInActiveWindow` is null at query time (race condition during dialog transitions), the fallback to `Display.DEFAULT_DISPLAY` is used — but this is fragile

3. **The callback still reports "Gesture completed"**: The gesture gets dispatched and acknowledged, but it's injected to the wrong display context or swallowed by the framework.

### Confirming Evidence

From the traces:

**Run 1 (baseline)** — SystemBrightnessMax:
- Turn 4: `swipe (42,212)→(1038,212), 400ms` → **SeekBar moved to 100%** → Task succeeded

**Run 2 (regression)** — SystemBrightnessMax:
- Turn 4: `swipe (42,212)→(1038,212), 400ms` → Result "Success" → **SeekBar still at 0%**
- Turns 5-23: 7+ additional swipe attempts, all report "Success", all have **no UI effect**

**Run 2** — SystemBrightnessMin:
- Turn 6: `swipe (1038,212)→(42,212), 400ms` → Result "Success" → **No UI effect**
- Turns 7-20: Repeated attempts, same pattern

**Control**: Node-based actions (clicks via `performAction()`) work correctly in ALL runs — they bypass `dispatchGesture` entirely.

### Secondary Suspect: `Handler(Looper.getMainLooper())`

The change from `null` handler to explicit `Handler(Looper.getMainLooper())` (line 143) is a secondary suspect. With `null`, the framework uses its own internal handler. The explicit handler forces callbacks onto the main looper, which may have threading implications for gesture completion detection. However, since the gesture is reported as "completed" (not missed), this is less likely to be the root cause than `setDisplayId()`.

---

## 4. The Spaghetti Code Problem

The user correctly identified that iterative modifications to click, swipe, and long_click executors have created tangled code. Here's the current architecture and its problems:

### Current Flow (Gesture Path)

```
MobileActionTool
  ├── ClickExecutor     → TargetResolver → UIAction.ClickNodeAt / UIAction.TapAt
  ├── LongPressExecutor → TargetResolver → UIAction.LongClickNodeAt / UIAction.LongPressAt
  ├── ScrollExecutor    → TargetResolver → UIAction.ScrollNodeAt / UIAction.Swipe (fallback)
  └── SwipeExecutor     → (raw coords)   → UIAction.Swipe O
                                              │
                                              ▼
                                    AccessibilityPlatform
                                    ├── performNodeClick() → NodeActionPerformer
                                    ├── performNodeLongClick() → NodeActionPerformer
                                    ├── performNodeScroll() → NodeActionPerformer
                                    ├── performTap() → AccessibilityGestureInjector.injectTap()
                                    ├── performSwipe() → AccessibilityGestureInjector.injectSwipe()
                                    └── performLongPress() → AccessibilityGestureInjector.injectLongPress()
                                                                    │
                                                                    ▼
                                                              buildGesture() ← setDisplayId() BUG
                                                              dispatchGesture()
```

### Problems

1. **Shared `buildGesture()` affects everything**: The `setDisplayId()` fix for tap broke swipe and long_press because all gesture types share `buildGesture()`.

2. **Inconsistent fallback strategies**: Each executor implements its own cascade differently:
   - ClickExecutor: node_click → gesture_tap
   - LongPressExecutor: node_long_click → gesture_long_press (with UiChangeDetector)
   - ScrollExecutor: node_scroll → gesture_swipe (fallback)
   - SwipeExecutor: gesture_swipe only (no node fallback)
(
    Qi Notes: 
    1. 对于node action和gesture action都存在的情况。Phase 1:先改成 node action -> gesture action 的顺序 （因为现在node action work得更好）。Phase 2:Phase 1需要确保没有regression。然后等fix了dispatchGesture(现在应该已经fix了)，全都work了，再把优先顺序反过来，先gesture action再node action。
    2. 都不带UiChangeDetector，这个行为不该用在任何地方。因为有可能本来就是click了不变。
)
3. **TargetResolver used differently**: ClickExecutor and LongPressExecutor use it for element_index→Point; SwipeExecutor bypasses it entirely and uses raw coords from args; ScrollExecutor partially uses element_index for container targeting.
(Qi Note: 这个尽可能改成一样的)

4. **Action result verification varies**: LongPressExecutor uses UiChangeDetector to verify state change; ClickExecutor and SwipeExecutor do NOT verify; ScrollExecutor validates scroll range state.
(Qi Notes: 
- 都不带UiChangeDetector，这个行为不该用在任何地方。因为有可能本来就是click了不变。
- "ScrollExecutor validates scroll range " 这是什么？应该不需要吧。
)
5. **Coordinate clamping in different places**: `AccessibilityPlatform.performSwipe()` clamps to display bounds; `injectTap()` does not clamp; `performNodeClickAt()` doesn't need clamping.

---

## 5. Refactoring Strategy

### Principle: Separate Node Path from Gesture Path Cleanly

The fundamental architectural issue is that **node-based actions** and **gesture-based actions** have different reliability profiles, failure modes, and display routing requirements, but they're entangled in the same executor cascade.

### Proposed Architecture

```
MobileActionTool
  └── ActionDispatcher (new)
        ├── resolveTarget(args) → TargetInfo { point, elementIndex, nodeRef }
        ├── dispatchAction(action, target) → ActionResult
        │     ├── NodeActionPath (high reliability)
        │     │     └── NodeActionPerformer (clicks, long-clicks, scrolls, text)
        │     └── GestureActionPath (lower reliability, needed for swipe/sliders)
        │           └── GestureInjector (tap, swipe, long-press)
        └── verifyResult(pre, post) → VerificationResult
```

### Key Changes

#### A. Unified TargetResolver (refactor)

All executors should use the same targeting pipeline:

```kotlin
data class TargetInfo(
    val point: Point,
    val elementIndex: Int?,
    val nodeRef: AccessibilityNodeInfo?,  // optional, for node-path actions
    val source: TargetSource  // ELEMENT_INDEX, TEXT_MATCH, RAW_COORDINATE
)

class TargetResolver(private val snapshot: ScreenSnapshot) {
    fun resolve(args: ActionArgs): TargetInfo
}
```

#### B. Unified Action Cascade (new)

Replace per-executor cascade logic with a single dispatcher:

```kotlin
class ActionDispatcher(
    private val nodePerformer: NodeActionPerformer,
    private val gestureInjector: GestureInjector,
    private val verifier: UiChangeDetector
) {
    suspend fun click(target: TargetInfo): ActionResult {
        // 1. Try node path first (if element resolved)
        // 2. Fall back to gesture tap
        // 3. Verify state change
    }

    suspend fun longClick(target: TargetInfo): ActionResult { /* same cascade */ }
    suspend fun scroll(target: TargetInfo, direction: String): ActionResult { /* node scroll → gesture fallback */ }
    suspend fun swipe(start: Point, end: Point, durationMs: Long): ActionResult { /* gesture only */ }
}
```

#### C. Fix GestureInjector Display Routing (immediate)

```kotlin
// REMOVE setDisplayId() from buildGesture() entirely for now.
// Gesture dispatch should use implicit display routing.
private fun buildGesture(stroke: StrokeDescription): GestureDescription {
    return GestureDescription.Builder().addStroke(stroke).build()
}
```

If multi-display support is needed later, add it behind a feature flag with explicit testing on physical multi-display devices.

#### D. Unified Verification (enhance)

All action types should verify state change:

```kotlin
class ActionVerifier(private val detector: UiChangeDetector) {
    suspend fun verify(preState: ScreenState, postState: ScreenState): VerificationResult {
        val changed = detector.hasChanged(preState, postState)
        return VerificationResult(
            stateChanged = changed,
            confidence = detector.changeConfidence(preState, postState)
        )
    }
}
```

Currently only LongPressExecutor verifies state change. ClickExecutor and SwipeExecutor should also verify, so that "false success" patterns are caught early and reported to the LLM.

#### E. Coordinate Clamping in One Place

Move all coordinate clamping to `TargetResolver.resolve()` or a dedicated `CoordinateNormalizer`, rather than having it in `AccessibilityPlatform.performSwipe()`:

```kotlin
class CoordinateNormalizer(private val displayInfo: DisplayInfo) {
    fun clamp(point: Point): Point {
        val maxX = (displayInfo.widthPixels - 1).coerceAtLeast(0)
        val maxY = (displayInfo.heightPixels - 1).coerceAtLeast(0)
        return Point(point.x.coerceIn(0, maxX), point.y.coerceIn(0, maxY))
    }
}
```

### Migration Path

1. **Phase 1 (immediate)**: Remove `setDisplayId()` from `buildGesture()` to fix the regression
2. **Phase 2**: Introduce `ActionDispatcher` that wraps existing executors, keeping backward compatibility
3. **Phase 3**: Migrate executors one-by-one to use `ActionDispatcher`
4. **Phase 4**: Add unified verification to all action types
5. **Phase 5**: Remove old executor classes once `ActionDispatcher` covers all cases

---

## 6. Immediate Fix Recommendation

### Fix 1: Remove `setDisplayId()` (P0)

In `AccessibilityGestureInjector.kt`, line 157-164:

```kotlin
// BEFORE (broken):
private fun buildGesture(stroke: GestureDescription.StrokeDescription): GestureDescription {
    val builder = GestureDescription.Builder().addStroke(stroke)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val displayId = resolveDisplayId() ?: Display.DEFAULT_DISPLAY
        builder.setDisplayId(displayId)
    }
    return builder.build()
}

// AFTER (fixed):
private fun buildGesture(stroke: GestureDescription.StrokeDescription): GestureDescription {
    return GestureDescription.Builder().addStroke(stroke).build()
}
```

### Fix 2: Keep diagnostic logging

The diagnostic logging added in `e745664` is valuable for debugging. Keep `describeGesture()` and the `dispatchGesture` logging but remove the display ID routing logic.

### Fix 3: Consider reverting Handler change

Optionally revert the Handler change from `Handler(Looper.getMainLooper())` back to `null`:

```kotlin
// Consider reverting to null handler:
val dispatched = service.dispatchGesture(gesture, callback, null)
```

The explicit handler is less likely to be the root cause but should be tested independently.

---

## 7. Verification Plan

1. Apply the `setDisplayId()` removal fix
2. Re-run the core eval subset:
   ```
   eval/.venv/bin/python eval/run_eval.py --config eval/config/aw_subset_core.txt
   ```
3. Specifically verify:
   - SystemBrightnessMax: swipe should move SeekBar
   - SystemBrightnessMin: swipe should move SeekBar
   - All other tasks should not regress
4. Re-run with fresh API key to verify Wifi tasks
5. Compare metrics:
   ```
   eval/.venv/bin/python eval/analysis/compare_runs.py --base eval/results/20260219_185400 --new <new_run>
   ```

---

## 8. Risk Assessment

| Change | Risk | Mitigation |
|--------|------|------------|
| Remove `setDisplayId()` | Low — restores pre-regression behavior | Verified by baseline run |
| Revert Handler to `null` | Low — restores pre-regression behavior | Test tap + swipe + long_press |
| Keep diagnostic logging | None — read-only | No mitigation needed |
| Full refactoring (Phase 2+) | Medium — touches core execution path | Incremental migration, per-executor |

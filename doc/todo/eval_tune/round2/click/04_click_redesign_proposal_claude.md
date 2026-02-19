# Click Redesign Proposal

## Design Goals

1. **Simple**: Click = resolve target to (x,y), dispatch gesture, return result. No hidden retry chains.
2. **Honest**: Report what happened truthfully. Let the LLM decide retries.
3. **Reliable**: Use dispatchGesture (synthetic touch) as primary. It's the closest to ADB input tap we can get from AccessibilityService.
4. **Debuggable**: Every decision point is traceable. No 12-attempt opaque trail.

## Architecture: 3 Layers

```
[L1] MobileActionTool.validate() + resolve target
[L2] ActionDispatcher.click(x, y) -> single dispatch + result
[L3] Platform: GestureInjector.injectTap(x, y) or NodePerformer.clickNode(x, y)
```

That's it. 3 layers. ~300 total lines for the click path.

---

## Layer 1: Target Resolution (Simplified)

### Remove occlusion pre-rejection

Current: TargetResolver returns `null` if all 6 candidates are blocked by a smaller clickable element, producing an "occluded" error that the LLM can't recover from.

**Proposed**: Always resolve to a point. Never return null for a valid element_index. The agent asked to click element X; we click element X.

```kotlin
object TargetResolver {
    fun resolve(target: Target, snapshot: ScreenSnapshot?): ResolveResult {
        return when (target) {
            is Target.Coordinate -> ResolveResult.Ok(Point(target.x, target.y))
            is Target.ElementIndex -> resolveElementIndex(target.index, snapshot)
            is Target.Text -> resolveText(target.text, target.textIndex, snapshot)
        }
    }
}

sealed interface ResolveResult {
    data class Ok(val point: Point, val warnings: List<String> = emptyList()) : ResolveResult
    data class NotFound(val reason: String) : ResolveResult
}
```

### Smarter point selection (if occluded)

Instead of rejecting, find the best available point:

```kotlin
private fun resolveElementPoint(element: PerceptionElement, snapshot: ScreenSnapshot): ResolveResult {
    val center = element.center
    val warnings = mutableListOf<String>()

    // Try center first
    if (!isBlocked(center, element, snapshot)) {
        return ResolveResult.Ok(center)
    }

    // Center is blocked. Scan for ANY unblocked point inside element bounds.
    val freePoint = findFreePoint(element, snapshot)
    if (freePoint != null) {
        warnings.add("Center blocked by overlapping element; using offset point")
        return ResolveResult.Ok(freePoint, warnings)
    }

    // ALL points blocked. Click center anyway with a warning.
    warnings.add("Element may be occluded; clicking center anyway")
    return ResolveResult.Ok(center, warnings)
}

private fun findFreePoint(element: PerceptionElement, snapshot: ScreenSnapshot): Point? {
    val b = element.bounds
    val margin = 4
    // Scan left edge, right edge, top edge, bottom edge (beyond just center/quartiles)
    val candidates = listOf(
        Point(b.left + margin, (b.top + b.bottom) / 2),    // left-center
        Point(b.right - margin, (b.top + b.bottom) / 2),   // right-center
        Point((b.left + b.right) / 2, b.top + margin),     // top-center
        Point((b.left + b.right) / 2, b.bottom - margin),  // bottom-center
        element.center                                       // center (fallback)
    )
    return candidates.firstOrNull { !isBlocked(it, element, snapshot) }
}
```

Key difference: **Never return null/failure for a valid element. Always produce coordinates. Warn if occlusion detected.**

---

## Layer 2: Click Dispatch (No Retry Chain)

Replace the current ClickExecutor (214 lines, 12 attempts) with a simple dispatcher:

```kotlin
class ClickDispatcher(private val platform: AndroidPlatform) {

    suspend fun click(point: Point, snapshot: ScreenSnapshot?): ClickResult {
        // Strategy: gesture tap first (more reliable), node click as diagnostic info
        val gestureResult = platform.performAction(UIAction.TapAt(point.x, point.y))

        return when (gestureResult) {
            is ActionResult.Success -> ClickResult.Dispatched(
                point = point,
                method = "gesture_tap",
                message = "Tapped (${point.x},${point.y})"
            )
            is ActionResult.Failure -> ClickResult.Failed(
                point = point,
                reason = gestureResult.reason
            )
            is ActionResult.Cancelled -> ClickResult.Cancelled(gestureResult.reason)
        }
    }

    suspend fun longPress(point: Point, durationMs: Long = 800): ClickResult {
        val result = platform.performAction(UIAction.LongPressAt(point.x, point.y, durationMs))
        return when (result) {
            is ActionResult.Success -> ClickResult.Dispatched(point, "long_press", "Long-pressed (${point.x},${point.y}) for ${durationMs}ms")
            is ActionResult.Failure -> ClickResult.Failed(point, result.reason)
            is ActionResult.Cancelled -> ClickResult.Cancelled(result.reason)
        }
    }
}

sealed interface ClickResult {
    data class Dispatched(val point: Point, val method: String, val message: String) : ClickResult
    data class Failed(val point: Point, val reason: String) : ClickResult
    data class Cancelled(val reason: String) : ClickResult
}
```

### What's removed

| Removed | Why |
|---------|-----|
| 12-attempt retry loop | LLM should decide retries based on screen state |
| Jitter (±12px offsets) | Doesn't address actual failure causes |
| Re-resolve (re-query a11y tree mid-click) | The target hasn't moved; we're just retrying |
| Per-attempt UI change detection | Noisy signal, adds 300ms + full capture per attempt |
| ACTION_CLICK as primary + gesture as fallback | Gesture tap is more reliable; make it primary |
| Attempt trail string building | Simplify to single method + success/fail |

### What's kept

| Kept | Why |
|------|-----|
| Post-action screen capture | LLM needs to see the new state (one capture, after dispatch) |
| dispatchGesture as primary | Most reliable dispatch from AccessibilityService |
| Cancellation support | Agent needs to be stoppable |

---

## Layer 3: Platform (Minimal Changes)

Keep `AccessibilityGestureInjector.injectTap()` and `NodeActionPerformer.performNodeClickAt()` as-is. They're clean implementations.

### One addition: Reliable long press

Current `injectLongPress` uses a moveTo path with configurable duration. Add a fallback that uses swipe-to-same-point (matching what reference implementations do):

```kotlin
suspend fun injectLongPress(x: Int, y: Int, durationMs: Long): ActionResult {
    // Primary: GestureDescription with long stroke duration
    val result = injectGestureWithDuration(x, y, durationMs)
    if (result is ActionResult.Success) return result

    // Fallback: Swipe to same point (mimics ADB "input swipe x y x y duration")
    return injectSwipe(x, y, x, y, durationMs)
}
```

---

## Post-Action Flow

After click dispatch, the observation builder captures the screen ONCE and returns it to the LLM:

```kotlin
// In MobileActionInvocation (simplified):
suspend fun executeClick(target: Target, snapshot: ScreenSnapshot, platform: AndroidPlatform): ToolResult {
    // 1. Resolve target
    val resolved = TargetResolver.resolve(target, snapshot)
    if (resolved is ResolveResult.NotFound) {
        return ToolResult.error(resolved.reason)
    }
    val point = (resolved as ResolveResult.Ok).point
    val warnings = resolved.warnings

    // 2. Dispatch click
    val clickResult = ClickDispatcher(platform).click(point, snapshot)

    // 3. Settle + capture (ONE time)
    delay(UI_SETTLE_MS)  // 300-500ms
    val postSnapshot = platform.captureScreen()

    // 4. Build result message
    val message = buildString {
        when (clickResult) {
            is ClickResult.Dispatched -> append("Success: ${clickResult.message}")
            is ClickResult.Failed -> append("Error: ${clickResult.reason}")
            is ClickResult.Cancelled -> append("Cancelled: ${clickResult.reason}")
        }
        warnings.forEach { append("\nWarning: $it") }
    }

    return ToolResult(
        success = clickResult is ClickResult.Dispatched,
        output = message,
        observation = ObservationBuilder.buildObservation(postSnapshot, platform)
    )
}
```

The LLM sees:
- Success/failure of the click dispatch
- Any occlusion warnings
- The NEW screen state (a11y tree + screenshot)

The LLM then decides: did the click work? Should I try again? Should I try a different approach?

---

## Migration Path

### Phase 1: Gesture-Only Click (implement)

Simplify the entire click path to: resolve target → (x,y) → `dispatchGesture` tap → return result.

**Changes:**
1. **TargetResolver**: Never return null for valid element_index. Always produce (x,y). Warn if occlusion detected, but click center anyway.
2. **ClickExecutor**: Replace 12-attempt retry chain with single `dispatchGesture` tap. Remove jitter, re-resolve, per-attempt UI change detection.
3. **Collapse layers**: Merge MobileActionInvocation + ClickExecutor into single dispatch flow. Remove ClickExecutor/LongPressExecutor as separate classes.
4. **Primary dispatch**: `dispatchGesture` (synthetic touch, equivalent to `adb input tap`). No `node.performAction(ACTION_CLICK)`.
5. **Post-action**: Single screen capture after settle delay. LLM sees new screen state and decides whether to retry.

**Rationale**: All reference implementations (Droidrun, MobileAgent, etc.) use this exact pattern: `element_index → center(bounds) → adb input tap`. No occlusion detection, no retry, no UI change verification. They let the LLM judge the result from the next screen observation.

`dispatchGesture` is our on-device equivalent of `adb input tap` — both inject synthetic touch events into the system input pipeline. This is more reliable than `node.performAction(ACTION_CLICK)` which goes through the a11y framework and can be refused by views that don't register a11y click handlers.

### Phase 2: Node-Based Fallback (design only, implement if needed)

**Decision gate**: Run Android World eval (`aw_subset_core.txt`) after Phase 1. If click execution errors remain (i.e., `dispatchGesture` returns failure — not "screen didn't change", but actual gesture dispatch failure), then implement Phase 2. If not, skip it.

`dispatchGesture` failures are rare — they only happen when the system cancels the gesture or the 5s callback timeout fires. But if they do occur:

**Design:**

When `dispatchGesture` returns an error AND the original target was `element_index` or `text+text_index` (not raw coordinates), fall back to node-based ACTION_CLICK. Critically, resolve the node **directly from the original target**, not via the intermediate (x,y) coordinates:

```
Primary path (always):
  element_index=5 → resolve → (x, y) → dispatchGesture tap → success

Fallback path (only on dispatchGesture error):
  element_index=5 → find a11y node with index 5 directly → node.performAction(ACTION_CLICK)
                     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                     NOT: (x,y) → findClickableNodeAt(x,y, a11yTree)
```

```kotlin
// Phase 2 pseudocode (do not implement yet)
suspend fun clickWithFallback(target: Target, snapshot: ScreenSnapshot, platform: AndroidPlatform): ClickResult {
    // Primary: resolve to (x,y), gesture tap
    val point = TargetResolver.resolve(target, snapshot)
    val gestureResult = platform.performAction(UIAction.TapAt(point.x, point.y))

    if (gestureResult is ActionResult.Success) {
        return ClickResult.Dispatched(point, "gesture_tap", ...)
    }

    // Fallback: direct node click (only for element_index / text targets)
    if (target is Target.ElementIndex || target is Target.Text) {
        val nodeResult = platform.performNodeAction(target, ACTION_CLICK)
        //                                          ^^^^^^
        //                        Pass original target, NOT (x,y) coordinates.
        //                        Platform finds the AccessibilityNodeInfo by index/text match,
        //                        not by coordinate lookup. No intermediate (x,y) → node resolution.
        if (nodeResult is ActionResult.Success) {
            return ClickResult.Dispatched(point, "node_action_click", ...)
        }
    }

    // Both failed
    return ClickResult.Failed(point, "Gesture dispatch failed, node fallback failed")
}
```

**Why direct node lookup instead of coordinate-based node lookup:**

The current code does: `element_index → (x,y) → findClickableNodeAt(x,y)`. This intermediate coordinate step can cause mismatches: the (x,y) may land on a different node than element 5 due to overlapping bounds, z-order, or margin adjustments. Direct index-based lookup eliminates this class of bugs.

**Why this may not be needed:**

`dispatchGesture` injects into the system touch pipeline. It succeeds unless:
- The gesture is cancelled by the system (another gesture in progress)
- The 5s callback timeout fires (system under extreme load)

Both are rare in eval. If Phase 1 eval shows zero gesture dispatch failures, Phase 2 adds complexity for no benefit.

---

## Expected Impact

### On eval failures (Phase 1):
- **I1 (Occluded rejection)**: Fixed. TargetResolver always returns a point. SimpleSmsSend can now click the button.
- **I2 (No UI change loop)**: No longer blocks. Click dispatches once and reports to LLM. LLM sees screen state and decides next action.
- **I3 (Screen edge)**: Still a platform limitation, but LLM gets clear failure message and can try `system_button: back` instead.
- **I4 (Long press)**: Improved with swipe-to-same-point fallback.
- **I6 (Jitter insufficient)**: Removed entirely. LLM-level retry is better.

### On performance:
- Current: up to 12 attempts * (300ms settle + screen capture) = ~6s per click (worst case)
- Proposed: 1 dispatch + 1 settle + 1 capture = ~500ms per click (always)

### On debuggability:
- Current: "Clicked (540,200) via jitter_2_gesture_tap. Attempts: base_ACTION_CLICK: dispatched, no UI change -> jitter: scheduling 4 nearby tap retries -> base_gesture_tap: dispatched, no UI change -> jitter_1: ..."
- Proposed: "Success: Tapped (540,200)" or "Error: Gesture dispatch failed at (540,200)"

---

## Summary

| Aspect | Current | Phase 1 | Phase 2 (if needed) |
|--------|---------|---------|---------------------|
| Layers | 7 | 3 | 3 (same) |
| Lines (click path) | ~2000 | ~300 | ~350 |
| Attempts per click | Up to 12 | 1 | 1 + 1 fallback |
| UI change detection | Per-attempt hash | None (LLM judges) | None |
| Occlusion handling | Reject (return null) | Warn + click anyway | Same |
| Primary dispatch | ACTION_CLICK node | Gesture tap | Gesture tap |
| Fallback dispatch | Gesture tap (reversed!) | None | Direct node ACTION_CLICK |
| Node lookup | via (x,y) coord | N/A | Direct from target (no coord intermediary) |
| Retry intelligence | Jitter + re-resolve | LLM sees screen, decides | Same |
| Long press fallback | Gesture hold only | Gesture hold + swipe-to-self | Same |
| Latency (worst case) | ~6s | ~500ms | ~600ms |

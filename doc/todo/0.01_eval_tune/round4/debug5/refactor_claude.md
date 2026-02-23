status: draft

# Executor Refactoring Design

## Goal

Assuming `dispatchGesture` works (FLAG_NOT_TOUCHABLE fix confirmed — see `dispatchGesture_flag_verification_claude.md`), align all executors to a consistent gesture-first, node-fallback cascade. The only executor not yet aligned is ScrollExecutor.

Qi Notes from `2_regression_analysis_refactoring_claude.md`:
1. After dispatchGesture fix: gesture action first, node action fallback — for ALL executors
2. No UiChangeDetector anywhere (already removed)
3. Unify TargetResolver usage across all executors (ScrollExecutor is the outlier)

## Current State

| Executor | Cascade Order | Target Interface | Uses TargetResolver? |
|----------|--------------|-----------------|---------------------|
| ClickExecutor | gesture_tap → node_click | `Target` | Yes |
| LongPressExecutor | gesture_long_press → node_long_click | `Target` | Yes |
| ScrollExecutor | **a11y_scroll → gesture_swipe** | **`JSONObject`** | **No (own resolveScrollArea)** |
| SwipeExecutor | gesture_swipe only | `JSONObject` (raw coords) | N/A (coordinate-only) |
| TypeExecutor | node-only | `Target?` | Yes |

Click and LongPress are already aligned. Swipe and Type have no dual-path (gesture-only and node-only respectively). **ScrollExecutor is the only one that needs changes.**

## Design

### Change 1: TargetResolver — add bounds to ResolveResult

ScrollExecutor needs element bounds (not just center point) to compute gesture swipe start/end within the scroll area. Add `bounds` to the existing result type:

```kotlin
// TargetResolver.kt
data class Resolved(
    val point: Point,
    val bounds: Bounds? = null,   // ← NEW: element bounds when resolved from element
    val warnings: List<String> = emptyList()
) : ResolveResult
```

One-line change in `resolveElementPoint`:
```kotlin
private fun resolveElementPoint(element: PerceptionElement): ResolveResult.Resolved {
    return ResolveResult.Resolved(element.center, bounds = element.bounds)
}
```

Existing callers (Click, LongPress, Type) are unaffected — they only use `point`.

### Change 2: ScrollExecutor — new interface + gesture-first cascade

**Interface change**: `JSONObject` → `Target?` + `direction: String`, matching the pattern of other executors.

```kotlin
class ScrollExecutor(
    private val targetResolver: TargetResolver = TargetResolver
) {
    suspend fun execute(
        target: Target?,
        direction: String,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform,
        isCancelled: () -> Boolean
    ): ActionOutcome
```

**Cascade change**: gesture-first → a11y-fallback (matching click/long_press):

```
1. Resolve scroll area:
   - target provided → TargetResolver.resolve(target) → use bounds for swipe area, point for a11y
   - no target → full screen bounds, screen center
2. Primary: gesture swipe within scroll area
3. Fallback: a11y scroll (ACTION_SCROLL_*) at center point
4. Post-action capture
```

**Scroll area resolution** uses TargetResolver + bounds:
```kotlin
private fun resolveScrollArea(
    target: Target?,
    snapshot: ScreenSnapshot?,
    platform: AndroidPlatform
): ScrollArea {
    if (target == null) {
        val display = platform.getDisplayInfo()
        return ScrollArea(
            center = Point(display.widthPixels / 2, display.heightPixels / 2),
            bounds = Bounds(0, 0, display.widthPixels, display.heightPixels)
        )
    }
    val resolved = targetResolver.resolve(target, snapshot)
    return when (resolved) {
        is TargetResolver.ResolveResult.Resolved -> {
            val fallbackBounds = platform.getDisplayInfo().let {
                Bounds(0, 0, it.widthPixels, it.heightPixels)
            }
            ScrollArea(
                center = resolved.point,
                bounds = resolved.bounds ?: fallbackBounds
            )
        }
        is TargetResolver.ResolveResult.NotFound -> {
            // fall back to full screen when element can't be resolved
            val display = platform.getDisplayInfo()
            ScrollArea(
                center = Point(display.widthPixels / 2, display.heightPixels / 2),
                bounds = Bounds(0, 0, display.widthPixels, display.heightPixels)
            )
        }
    }
}

private data class ScrollArea(val center: Point, val bounds: Bounds)
```

**computeGestureFallback**: stays the same — computes center-to-edge swipe within bounds.

### Change 3: MobileActionTool — parse target + direction for scroll

```kotlin
"scroll" -> {
    val direction = params.getString("direction")
    val target = parseOptionalTarget(params)  // reuses existing method
    ScrollExecutor().execute(target, direction, snapshot, platform, isCancelled)
}
```

`parseOptionalTarget` already handles `element_index` → `Target.ElementIndex`. For scroll, `element_index` is the scrollable container target. If absent, `target` is null → full screen scroll.

## Why gesture-first for scroll is now safe

The previous regression (scroll "succeeded" without scrolling) was caused by the capsule overlay intercepting gesture events — the same root cause as the click failure. With FLAG_NOT_TOUCHABLE, gesture swipes reach the target view and scroll normally. Confirmed by eval `20260220_145635`: SystemBrightnessMax passed using gesture swipes to scroll and interact with sliders.

## Files Modified

| File | Change |
|------|--------|
| `TargetResolver.kt` | Add `bounds: Bounds?` to `ResolveResult.Resolved`, pass in `resolveElementPoint` |
| `ScrollExecutor.kt` | New interface (`Target?` + `direction`), gesture-first cascade, use TargetResolver |
| `MobileActionTool.kt` | Update scroll invocation to parse target + direction |

No changes to: ClickExecutor, LongPressExecutor, SwipeExecutor, TypeExecutor, AccessibilityPlatform, AccessibilityGestureInjector, UIAction.

## What is NOT changing (and why)

- **ClickExecutor / LongPressExecutor**: Already gesture-first with node fallback. Clean and aligned.
- **SwipeExecutor**: Gesture-only. No node equivalent for raw coordinate swipes.
- **TypeExecutor**: Node-only. Text input requires node-level ACTION_SET_TEXT.
- **AccessibilityPlatform.performSwipe edge clamping**: Correctly placed at the platform level — handles Android gesture-nav back interception, not an executor concern.
- **Post-action capture pattern**: Each executor handles its own delay + capture. Minor duplication but each file is self-contained and under 150 lines. Not worth extracting.

## Verification

1. `./gradlew assembleDebug` — build passes
2. `./gradlew test` — existing tests pass
3. Eval: `eval/.venv/bin/python eval/aw_bridge/runner.py --tasks "SystemBrightnessMax,SystemBrightnessMin"` — both pass (gesture swipe + tap on Settings)

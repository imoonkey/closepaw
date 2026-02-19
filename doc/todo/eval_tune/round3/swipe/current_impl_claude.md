# Current Swipe Implementation Analysis

## Architecture Overview

```
LLM Tool Call (JSON)
    |
    v
MobileActionTool.validate()           -- JSON schema validation
    |
    v
MobileActionTool.createInvocation()    -- Route to SwipeExecutor
    |
    v
SwipeExecutor.execute()                -- Core logic entry
    |--- explicit start/end? --> executeExplicitSwipe()
    |--- direction?          --> executeDirectionalSwipe()
    |                             |-- computeSafeInset()
    |                             |-- parseOptionalTarget() -> TargetResolver
    |                             |-- computeDistancePx(distance, baseSize)
    |                             |-- computeEndpoints(direction, origin, delta, bounds)
    |                             v
    +--> dispatchSwipe(UIAction.Swipe)
              |
              v
         AccessibilityPlatform.performSwipe()
              |-- coordinate clamping to display bounds
              v
         AccessibilityGestureInjector.injectSwipe()
              |-- Path(moveTo, lineTo)
              |-- GestureDescription.StrokeDescription(path, 0, durationMs)
              v
         AccessibilityService.dispatchGesture(gesture, callback)
              |
              v
         delay(300ms)  -- UI_SETTLE_DELAY_MS
              |
              v
         captureScreen() -> UiChangeDetector.detectScrollBoundary()
              |
              v
         ActionOutcome.Success (with observation + warnings)
```

## Tool Definition (JSON Schema)

```json
{
  "action": "swipe",
  "direction": "up|down|left|right",        // directional mode
  "distance": "short|medium|long",          // optional, default "medium"
  "start": [x, y],                          // explicit mode (overrides direction)
  "end": [x, y],                            // explicit mode
  "element_index": 3,                       // optional: swipe origin from element
  "text": "Category",                       // optional: swipe origin from text match
  "duration_ms": 400                        // optional
}
```

**Validation**: explicit start/end takes precedence over direction. Cannot mix.

## Key Constants

| Parameter | Value | Location |
|-----------|-------|----------|
| DEFAULT_SWIPE_DURATION_MS | 400ms | SwipeExecutor:25 |
| UI_SETTLE_DELAY_MS | 300ms | SwipeExecutor:24 |
| GESTURE_TIMEOUT_MS | 5000ms | AccessibilityGestureInjector:27 |
| Safe inset | max(5% min dim, 24dp) | SwipeExecutor:230 |
| Distance "short" | 15% of swipe area | SwipeExecutor:190 |
| Distance "medium" | 40% of swipe area | SwipeExecutor:191 |
| Distance "long" | 70% of swipe area | SwipeExecutor:192 |
| Min swipe distance | max(16dp, 10% area) | SwipeExecutor:196 |
| Max swipe distance | 90% of swipe area | SwipeExecutor:197 |
| Virtual display steps | 20 | VirtualDisplayInputInjector:97 |

## Critical Issues Identified

### Issue 1: Symmetric Delta from Origin = Edge Clamping Bug

**File**: `SwipeExecutor.kt` `computeEndpoints()`

The algorithm spreads symmetrically from origin:
```kotlin
when (direction) {
    "up" -> { startY = originY + delta; endY = originY - delta }
    "down" -> { startY = originY - delta; endY = originY + delta }
    "left" -> { startX = originX + delta; endX = originX - delta }
    "right" -> { startX = originX - delta; endX = originX + delta }
}
// Then clamp to safe bounds
startX = startX.coerceIn(safeLeft, safeRight)
endX = endX.coerceIn(safeLeft, safeRight)
```

**Problem**: When originX is near an edge (e.g., x=158 for a left-positioned RecyclerView):
- `delta` = medium 40% of 972 / 2 = ~194
- For "left": startX = 158 + 194 = 352, endX = 158 - 194 = -36 -> clamped to 54
- For "right": startX = 158 - 194 = -36 -> clamped to 54, endX = 158 + 194 = 352
- Effective distance: 298px instead of intended 389px
- But from eval: actual coords show (63,822) -> (253,822) = 190px = even worse!

**Impact**: Horizontal swipes on edge-positioned views lose 50-75% of intended distance.

**Fix**: Use asymmetric endpoint calculation: start from origin, end at origin +/- fullDistance
(not half-distance each way). Or compute start/end independently using safe bounds as limits.

### Issue 2: No AccessibilityNodeInfo Scroll Action Fallback

**File**: `SwipeExecutor.kt`

Unlike `ClickExecutor` which tries `ClickNodeAt` before falling back to `TapAt` gesture,
`SwipeExecutor` is gesture-only. There's no attempt to use:
- `AccessibilityNodeInfo.ACTION_SCROLL_FORWARD`
- `AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD`
- `AccessibilityAction.ACTION_SCROLL_UP/DOWN/LEFT/RIGHT` (API 23+)

**Impact**: When a container doesn't respond to gesture-based scrolling (custom views,
keyboard blocking, WebView, etc.), there's no fallback. This explains why
RecipeAddSingleRecipe had 8 consecutive failed swipes.

**Evidence**: Click has a 2-layer fallback:
```kotlin
// ClickExecutor
1. Try UIAction.ClickNodeAt -> AccessibilityNodeInfo.performAction(ACTION_CLICK)
2. Fallback: UIAction.TapAt -> gesture tap
```

Swipe should have similar layers:
```kotlin
// Proposed SwipeExecutor
1. Try AccessibilityNodeInfo.performAction(ACTION_SCROLL_FORWARD/BACKWARD)
2. Fallback: gesture-based swipe
```

### Issue 3: Direction Semantics Confusion

**File**: `ExecutorAgentDef.kt` + eval traces

The prompt says:
```
mobile_action(action="swipe", direction="up") to scroll DOWN
```

But eval traces show the agent saying "Scroll down to find Podcasts" then using
`direction="down"` (FilesMoveFile turn 4). The counter-intuitive mapping is a persistent
source of LLM confusion.

**Alternatives**:
- Use `scroll_direction` instead of `direction` with values "scroll_down", "scroll_up"
- Rename to describe content movement: "content_up" means content moves up (page scrolls down)
- Add a `scroll` action alias that maps intuitively

### Issue 4: 300ms Fixed Settle Delay

**File**: `SwipeExecutor.kt:24`

Fixed 300ms may be insufficient for:
- Long list scrolls with animation
- Page transitions
- Heavy RecyclerView relayout
- WebView content loading

droidrun sleeps for the swipe duration itself (e.g., 1000ms for 1s swipe). Our 300ms is
independent of swipe duration.

### Issue 5: Swipe Distance Percentages Are Low

| Distance | Our Factor | autodevice (effective) |
|----------|-----------|----------------------|
| short | 15% | ~25% |
| medium | 40% | ~50% |
| long | 70% | 100% (full screen) |

autodevice "up" sweeps from screen_height to 0 = full screen. Our "long" only covers 70%
of the safe area (which is already 90% of screen), so effective coverage is ~63%.

### Issue 6: No Retry Cap on Consecutive Failures

When the "screen content unchanged" warning fires, there's no mechanism to:
- Limit retries in the same direction
- Escalate to the LLM with "scrolling appears impossible, try alternative navigation"
- Automatically try ACTION_SCROLL as fallback

RecipeAddSingleRecipe wasted 8 turns because nothing stopped the agent from retrying.

## Comparison: Click vs Swipe Fallback Chains

| Aspect | ClickExecutor | SwipeExecutor |
|--------|---------------|---------------|
| Layer 1 | Node action (ACTION_CLICK) | **None** (missing!) |
| Layer 2 | Gesture tap (TapAt) | Gesture swipe |
| Post-action | Element verification | UI change detection |
| On failure | Falls through chain | Returns first result |
| Retry logic | Yes (node -> gesture) | No fallback at all |

## Gesture Injection Details

**AccessibilityGestureInjector.injectSwipe()**:
```kotlin
val path = Path().apply {
    moveTo(startX, startY)
    lineTo(endX, endY)
}
val gesture = GestureDescription.Builder()
    .addStroke(StrokeDescription(path, 0, durationMs))
    .build()
service.dispatchGesture(gesture, callback, null)
```

- Linear interpolation only (no bezier curves)
- Single stroke (no segmentation)
- 5s timeout on gesture completion

**VirtualDisplayInputInjector** (Shizuku path):
```kotlin
// 20-step MotionEvent injection
ACTION_DOWN at start -> 20x ACTION_MOVE interpolated -> ACTION_UP at end
```

## Prompt Instructions (ExecutorAgentDef)

```kotlin
### SCROLL queries ("Scroll down", "Scroll to find X")
1. mobile_action(action="swipe", direction="up") to scroll DOWN
2. If looking for element: check if visible after scroll
3. complete_task(status="success", answer="Scrolled [direction]. [What's now visible]")
```

**Issues with prompt**:
1. Counter-intuitive direction mapping (addressed above)
2. No guidance on what to do when scroll fails
3. No guidance on horizontal scrolling
4. No mention of explicit coordinate mode for precision work
5. No slider/drag-specific guidance

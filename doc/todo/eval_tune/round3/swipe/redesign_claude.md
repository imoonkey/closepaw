# Swipe Action Redesign

## Context

Based on analysis of:
- Eval run `20260219_124436`: 29 swipe actions, 65.5% produced no screen change
- 6 reference mobile agent implementations (minitap, droidrun, autodevice, MobileAgent v3, MAI-UI, Agent-S)
- Current implementation: 4-layer architecture from tool definition through AccessibilityService gesture dispatch

**Core problem**: Swipe actions complete at the tool level but frequently fail to produce
the intended UI effect. 19/29 swipes hit "screen content unchanged" warnings. Three tasks
failed primarily due to ineffective swipes, wasting 15+ agent turns.

## Root Causes (Priority Order)

### P0: No a11y scroll action fallback
SwipeExecutor is gesture-only. When gesture dispatch doesn't trigger scrolling
(focused EditText, keyboard blocking, custom ScrollView, etc.), there's no
`ACTION_SCROLL_FORWARD/BACKWARD` fallback. ClickExecutor has a 2-layer chain
(node action -> gesture); SwipeExecutor has 1 layer.

### P1: Edge-clamped directional swipes
Symmetric `origin +/- delta` calculation loses 50-75% of intended distance when origin
is near a screen edge. Horizontal swipes on left-positioned RecyclerViews get clamped
to ~190px instead of ~389px.

### P2: No retry escalation
Agent retries the same failing swipe 8 times (RecipeAddSingleRecipe) with no strategy
change. No mechanism to cap retries or suggest alternative navigation.

### P3: Direction semantics confusion
`direction="up"` scrolls content DOWN. LLM frequently chooses the wrong direction.
FilesMoveFile agent said "scroll down" but used `direction="down"` (wrong).

### P4: Slider/drag imprecision
Brightness slider needed exact thumb coordinates. First two attempts missed. No
mechanism to identify slider bounds from a11y tree.

---

## Proposed Changes

### Change 1: Add AccessibilityNode Scroll Action Layer (P0)

**What**: For directional swipes, try `performAction(ACTION_SCROLL_*)` on the nearest
scrollable ancestor node before falling back to gesture dispatch.

**Why**: This is analogous to ClickExecutor's node-action-before-gesture pattern. The a11y
scroll actions work at the framework level and bypass gesture interception.

**Implementation**:

```kotlin
// SwipeExecutor.kt - executeDirectionalSwipe()
suspend fun executeDirectionalSwipe(
    params: JSONObject,
    direction: String,
    durationMs: Long,
    snapshot: ScreenSnapshot?,
    platform: AndroidPlatform
): ActionOutcome {
    // NEW: Try a11y scroll action first (for vertical/horizontal scroll)
    val scrollAction = mapDirectionToScrollAction(direction)
    if (scrollAction != null) {
        val scrollTarget = findScrollableNode(snapshot, direction)
        if (scrollTarget != null) {
            val nodeResult = platform.performAction(
                UIAction.ScrollNode(scrollTarget.nodeId, scrollAction)
            )
            if (nodeResult is ActionResult.Success) {
                delay(UI_SETTLE_DELAY_MS)
                val post = platform.captureScreen()
                val boundary = uiChangeDetector.detectScrollBoundary(snapshot, post)
                if (boundary == null) {
                    // Scroll action worked - screen changed
                    return buildScrollSuccess(direction, post, platform)
                }
                // Screen unchanged even with scroll action - fall through to gesture
                attemptTrail += "scroll_action: boundary_reached"
            } else {
                attemptTrail += "scroll_action: ${nodeResult.reason}"
            }
        }
    }

    // EXISTING: Fall back to gesture-based swipe
    return executeGestureSwipe(params, direction, durationMs, snapshot, platform)
}

private fun mapDirectionToScrollAction(direction: String): Int? = when (direction) {
    "up" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD    // scroll DOWN = swipe UP
    "down" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD // scroll UP = swipe DOWN
    "left" -> AccessibilityAction.ACTION_SCROLL_RIGHT.id   // API 23+
    "right" -> AccessibilityAction.ACTION_SCROLL_LEFT.id   // API 23+
    else -> null
}
```

**New UIAction**:
```kotlin
data class ScrollNode(
    val nodeId: String,    // accessibility node to scroll
    val scrollAction: Int  // ACTION_SCROLL_FORWARD, etc.
) : UIAction
```

**Finding scrollable nodes**:
```kotlin
private fun findScrollableNode(
    snapshot: ScreenSnapshot?,
    direction: String
): ScreenElement? {
    if (snapshot == null) return null
    val isVertical = direction == "up" || direction == "down"

    return snapshot.elements
        .filter { it.isScrollable }
        .filter { elem ->
            if (isVertical) elem.bounds.height() > snapshot.screenHeight * 0.3
            else elem.bounds.width() > snapshot.screenWidth * 0.3
        }
        .maxByOrNull { it.bounds.width() * it.bounds.height() }
}
```

**Files to modify**:
- `SwipeExecutor.kt`: Add scroll action layer before gesture
- `UIAction.kt`: Add `ScrollNode` variant
- `AccessibilityPlatform.kt`: Handle `ScrollNode` action
- `ScreenElement.kt` / `ScreenSnapshot.kt`: Expose `isScrollable` flag

### Change 2: Fix Directional Swipe Geometry (P1)

**What**: Replace symmetric origin +/- delta with asymmetric start-to-end calculation.

**Current (broken)**:
```kotlin
// For "up": swipe from below to above
startY = originY + delta   // If origin=512, delta=200 -> start=712
endY = originY - delta     // end=312
// If near top (origin=100, delta=200): start=300, end=-100 -> clamped to 54
// Effective: 300 -> 54 = 246px instead of 400px
```

**Proposed**:
```kotlin
private fun computeEndpoints(
    direction: String,
    originX: Int, originY: Int,
    distancePx: Int,
    safeLeft: Int, safeTop: Int, safeRight: Int, safeBottom: Int
): IntArray? {
    var startX = originX; var startY = originY
    var endX = originX; var endY = originY

    when (direction) {
        "up" -> {
            // Finger moves up: start as low as possible, end as high as possible
            startY = min(originY + distancePx / 3, safeBottom)
            endY = max(startY - distancePx, safeTop)
        }
        "down" -> {
            startY = max(originY - distancePx / 3, safeTop)
            endY = min(startY + distancePx, safeBottom)
        }
        "left" -> {
            startX = min(originX + distancePx / 3, safeRight)
            endX = max(startX - distancePx, safeLeft)
        }
        "right" -> {
            startX = max(originX - distancePx / 3, safeLeft)
            endX = min(startX + distancePx, safeRight)
        }
    }

    return if (startX == endX && startY == endY) null
    else intArrayOf(startX, startY, endX, endY)
}
```

Key changes:
- Start 1/3 of distance from origin in the opposite direction (bias toward content)
- End at full distance from start (not half from origin)
- Clamp start first, then compute end relative to clamped start
- Ensures maximum distance is achieved regardless of origin position

Also increase distance factors:

| Distance | Current | Proposed |
|----------|---------|----------|
| short | 15% | 25% |
| medium | 40% | 50% |
| long | 70% | 80% |

**Files to modify**: `SwipeExecutor.kt` - `computeEndpoints()`, `computeDistancePx()`

### Change 3: Add Consecutive Failure Detection (P2)

**What**: Track consecutive "unchanged" results and include escalation hints in the
tool result message.

**Implementation** (in `dispatchSwipe`):
```kotlin
// Add to result message when boundary detected:
val warningMessage = buildString {
    append("Screen content unchanged after swipe - may have reached scroll boundary")
    // If this is a directional swipe, add guidance:
    if (isDirectional) {
        append(". Consider: (1) try the opposite direction, ")
        append("(2) use a click-based navigation instead, ")
        append("(3) check if the content is already fully visible")
    }
}
```

This is a prompt-level hint, not a code-level retry cap. The LLM should decide whether to
persist. But giving actionable guidance reduces wasted turns.

**Files to modify**: `SwipeExecutor.kt` - `dispatchSwipe()` warning message

### Change 4: Simplify Direction Semantics in Prompt (P3)

**What**: Change prompt to use scroll-intent language and add clarifying examples.

**Current**:
```
mobile_action(action="swipe", direction="up") to scroll DOWN
```

**Proposed**: Keep the tool parameter names unchanged (avoid breaking changes), but improve
the prompt description:

```kotlin
### SCROLL / SWIPE actions
- To scroll page content DOWN (reveal content below): direction="up" (finger moves up)
- To scroll page content UP (reveal content above): direction="down" (finger moves down)
- To scroll horizontally LEFT (reveal content to right): direction="left"
- To scroll horizontally RIGHT (reveal content to left): direction="right"
- For precision/slider drags: use explicit start=[x,y] end=[x,y]
- If scroll has no effect after 2 attempts, try a different approach (click, navigate, etc.)
```

**Files to modify**: `ExecutorAgentDef.kt`

### Change 5: Dynamic Settle Delay (P4, minor)

**What**: Scale settle delay with swipe duration instead of fixed 300ms.

```kotlin
val settleMs = max(UI_SETTLE_DELAY_MIN, (durationMs * 0.75).toLong())
    .coerceAtMost(UI_SETTLE_DELAY_MAX)
delay(settleMs)
```

With MIN=200ms, MAX=800ms. A 400ms swipe settles for 300ms (current behavior).
A 1000ms long swipe settles for 750ms.

**Files to modify**: `SwipeExecutor.kt` - `dispatchSwipe()`

---

## Implementation Priority

| # | Change | Priority | Effort | Impact |
|---|--------|----------|--------|--------|
| 1 | A11y scroll action fallback | P0 | Medium | High - fixes RecipeAddSingleRecipe (8 wasted swipes) |
| 2 | Fix directional geometry | P1 | Low | High - fixes ExpenseAddSingle horizontal swipes |
| 3 | Failure escalation hints | P2 | Low | Medium - reduces wasted turns across all tasks |
| 4 | Simplify direction prompt | P3 | Low | Medium - reduces FilesMoveFile-type direction confusion |
| 5 | Dynamic settle delay | P4 | Low | Low - marginal improvement |

## Changes NOT Proposed (Considered and Rejected)

### Rejected: Switch from AccessibilityService to ADB input
While all reference agents use ADB, our AccessibilityService approach has benefits:
- Works without USB/network ADB connection
- More appropriate for on-device agent
- Adding the scroll action fallback (Change 1) addresses the main gap
- Virtual display path already uses MotionEvent injection

### Rejected: Percentage-based coordinates (minitap)
Nice-to-have but the LLM already sees absolute pixel coordinates in the a11y tree.
Adding percentage mode would require the LLM to do mental math or maintain two
coordinate systems. Not worth the complexity.

### Rejected: Separate `scroll` and `drag` tools
Would increase tool count and confuse tool selection. Better to keep unified `swipe`
with different parameter modes. The a11y scroll action fallback (Change 1) handles
the scroll case; explicit coordinates handle the drag case.

### Rejected: Swipe retry counter in executor
Adding automatic retry logic in the executor blurs the agent-vs-executor boundary.
The agent (LLM) should decide retry strategy. Providing better feedback (Change 3)
is the right approach.

---

## Validation Plan

After implementing changes, re-run the eval suite and verify:

1. **RecipeAddSingleRecipe**: Scroll action fallback should allow form scrolling where
   gesture dispatch failed. Target: 0 "unchanged" warnings.

2. **ExpenseAddSingle**: Fixed geometry should produce 300-400px horizontal swipes instead
   of 190px. Target: fewer swipes needed to find "Health Care" category.

3. **FilesMoveFile**: Improved prompt should prevent direction="down" when intent is
   "scroll down". Scroll action fallback should handle list scrolling.

4. **SystemBrightnessMinVerify**: No regression on slider drag behavior (explicit coords
   are unaffected by changes 1-4).

5. **Overall**: Target <40% "screen unchanged" warnings (from current 65.5%).

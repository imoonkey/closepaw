# Scroll/Swipe Split Implementation Plan

Split `swipe` into `scroll` (content-direction + a11y action) and `swipe` (raw coordinates only). KISS principle throughout.

## Files to Modify (9 files) + 1 New File

### Phase 1: Platform Layer

**1. `UIAction.kt`** — Add `ScrollNodeAt` variant
```kotlin
data class ScrollNodeAt(val x: Int, val y: Int, val direction: String) : UIAction
```
`direction` = content direction ("down" = content moves down = reveal below).

**2. `AccessibilityNodeFinder.kt`** — Add `findScrollableNodeAtLocation`
```kotlin
fun findScrollableNodeAtLocation(root, x, y): AccessibilityNodeInfo? =
    findActionableNodeAtLocation(root, x, y) { it.isScrollable && it.isVisibleToUser }
```
Uses existing `findActionableNodeAtLocation` infrastructure.

**3. `NodeActionPerformer.kt`** — Add `performScrollAt(x, y, direction)`
- Find scrollable node at (x,y) via `AccessibilityNodeFinder.findScrollableNodeAtLocation`
- Map direction to a11y action: prefer API 23+ directional (`ACTION_SCROLL_DOWN` etc.), fall back to `FORWARD/BACKWARD`
- `performAction()` on the node, return `ActionResult`

**4. `AccessibilityPlatform.kt`** — Route `ScrollNodeAt`
```kotlin
is UIAction.ScrollNodeAt -> nodeActionPerformer.performScrollAt(action.x, action.y, action.direction)
```

**5. `VirtualDisplayPlatform.kt`** — Route `ScrollNodeAt`
Same as AccessibilityPlatform — VirtualDisplay has full node-action support via `NodeActionPerformer`.

### Phase 2: Executors

**6. NEW: `ScrollExecutor.kt`** (~80 lines)
Pipeline:
1. Parse direction (required) + optional element_index
2. Get scroll area: if element_index → element bounds from snapshot; else → full screen
3. Try a11y scroll: `UIAction.ScrollNodeAt(centerX, centerY, direction)`
   - If `ActionResult.Failure` → skip to gesture fallback (no screen capture)
   - If `ActionResult.Success` → delay, capture, compare, return if changed
4. Gesture fallback: compute center-to-edge swipe within scroll area, dispatch `UIAction.Swipe`
5. Settle delay 400ms, capture screen, return with boundary warning if unchanged

**7. REWRITE: `SwipeExecutor.kt`** (~30 lines)
Delete ALL direction/distance/target/geometry logic. Keep only:
1. Parse `start` [x,y] and `end` [x,y] and optional `duration_ms` (default 400)
2. Dispatch `UIAction.Swipe(sx, sy, ex, ey, durationMs)`
3. Delay `max(300, durationMs * 0.75)`, capture screen, return

Delete: `computeDistancePx`, `computeEndpoints`, `computeSafeInset`, `parseOptionalTarget`, `executeDirectionalSwipe`, `TargetResolver`/`UiChangeDetector` constructor params.

### Phase 3: Tool Schema

**8. `MobileActionTool.kt`**
- Add `"scroll"` to action enum: `listOf("click", "long_press", "scroll", "swipe", "type")`
- Add `validateScrollAction()`: direction required, element_index optional
- Simplify `validateSwipeAction()`: start+end required only (remove direction/distance support)
- Add scroll routing: `"scroll" -> ScrollExecutor().execute(params, snapshot, platform, isCancelled)`
- Update description text with scroll/swipe examples
- Schema: add `"scroll"` to action enum. Remove `direction`/`distance` description references for swipe.
- Update `buildDescription()` for scroll action

### Phase 4: Action Classification

**9. `TurnExecutionPhaseRunner.kt`** — `classifyAction()`
```kotlin
MobileActionName.Scroll -> "scroll:${direction.ifBlank { "unknown" }}"
MobileActionName.Swipe -> "mobile_action:swipe"
```
This keeps `NavigationState.consecutiveScrollActions` working (it checks `startsWith("scroll:")`).

### Phase 5: Prompt Updates

**10. `ExecutorAgentDef.kt`**
- Change SCROLL section: `mobile_action(action="scroll", direction="down")` to scroll down
- Add note: "Use `scroll` for navigating lists/pages. Use `swipe` with start/end coordinates for precision gestures (sliders, drag-and-drop)."

**11. `PlannerAgentDef.kt`**
- Update delegation examples: `scroll(intent)` uses `action="scroll"`

**12. `StandaloneAgentDef.kt`**
- Add scroll vs swipe guidance section

### Phase 6: Cleanup & Tests

**13. `MobileActionToolTest.kt`** — Update swipe tests, add scroll tests

**No changes needed:**
- `ToolName.kt` — `MobileActionName.Scroll` already exists
- `ToolUi.kt` — icon mapping for `Scroll` already exists
- `NavigationState.kt` — `startsWith("scroll:")` still works
- `ActionOutcome.kt` — unchanged
- `UiChangeDetector.kt` — unchanged, used by ScrollExecutor
- `LoopDetectionPolicy.kt` — unchanged

## Direction Mapping (in NodeActionPerformer)

| scroll direction | Content effect | Primary a11y action (API 23+) | Fallback (API 16+) |
|-----------------|---------------|-------------------------------|---------------------|
| `"down"` | Content scrolls down | `ACTION_SCROLL_DOWN` | `ACTION_SCROLL_FORWARD` |
| `"up"` | Content scrolls up | `ACTION_SCROLL_UP` | `ACTION_SCROLL_BACKWARD` |
| `"right"` | Content scrolls right | `ACTION_SCROLL_RIGHT` | `ACTION_SCROLL_FORWARD` |
| `"left"` | Content scrolls left | `ACTION_SCROLL_LEFT` | `ACTION_SCROLL_BACKWARD` |

Semantics: `direction="down"` means "I want to see content below" = finger swipes UP = `ACTION_SCROLL_DOWN` (viewport moves down).

## Gesture Fallback Geometry (in ScrollExecutor)

Center-to-edge within scroll area bounds (autodevice pattern):
- `"down"` → start at center, end at top (finger up)
- `"up"` → start at center, end at bottom (finger down)
- `"left"` → start at center, end at right (finger right)
- `"right"` → start at center, end at left (finger left)

## Verification

1. `./gradlew assembleDebug` — build succeeds
2. `./gradlew test` — existing + new tests pass
3. Manual check: scroll action enum in schema includes "scroll"
4. Verify VirtualDisplay routing compiles for ScrollNodeAt

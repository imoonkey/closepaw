# P2 Fix Design: Closest-Center Selection + Identity Verification

## Overview

Two-layer fix for `node_action_click` hitting the wrong overlapping node:

1. **Selection**: closest-center heuristic in `AccessibilityNodeFinder` — picks the correct node
2. **Verification**: identity logging & mismatch guard in `NodeActionPerformer` — guarantees 100% accuracy and provides debug observability

## Layer 1: Closest-Center Selection

### Rationale

The upper layer always clicks at the intended element's **center**. So the click point `(73, 191)` IS the center of "Show roots" by construction. The distance from `(73, 191)` to "Show roots" center `(73, 191)` is **0**, while the distance to the file item center `(540, 176)` is **~467 px**. The correct node always wins.

This also handles nested clickable elements naturally (e.g., a button inside a clickable card): the inner button's center is closer to the click point than the outer card's center.

### Change

**File**: `AccessibilityNodeFinder.kt` — `findActionableNodeAtLocation`

Change from "return first DFS match" to "collect all candidates, pick closest center". No API changes upstream.

```kotlin
private fun findActionableNodeAtLocation(
    root: AccessibilityNodeInfo,
    x: Int, y: Int,
    predicate: (AccessibilityNodeInfo) -> Boolean
): AccessibilityNodeInfo? {
    val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Long>>() // node, distanceSquared

    fun collect(node: AccessibilityNodeInfo, shouldRecycle: Boolean) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) {
            if (shouldRecycle) node.recycleCompat()
            return
        }
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i) ?: continue
            collect(child, shouldRecycle = true)
        }
        if (predicate(node)) {
            val cx = (bounds.left + bounds.right) / 2
            val cy = (bounds.top + bounds.bottom) / 2
            val dist = (x - cx).toLong() * (x - cx) + (y - cy).toLong() * (y - cy)
            candidates.add(node to dist)
        } else if (shouldRecycle) {
            node.recycleCompat()
        }
    }

    collect(root, shouldRecycle = false)

    // Pick closest center; recycle all others
    val winner = candidates.minByOrNull { it.second }?.first
    for ((node, _) in candidates) {
        if (node !== winner) node.recycleCompat()
    }
    return winner
}
```

| Click point | "Show roots" center | File item center | Winner |
|-------------|---------------------|------------------|--------|
| (73, 191) | (73, 191) → dist=**0** | (540, 176) → dist=**218,314** | "Show roots" ✓ |

## Layer 2: Identity Verification & Logging

### Rationale

Closest-center is a heuristic. To guarantee 100% accuracy, the found node must be **verified** against the intended element before `performAction(ACTION_CLICK)` is called. And every click — match or mismatch — must be **logged** so we can debug issues from logcat alone.

### Always log (debug observability)

`NodeActionPerformer.performNodeActionAt` logs the found node's identity alongside the intended element info on every click:

Match:
```
NodeAction: intended=["Show roots", ImageButton, [0,128,147,254]]
            found=["Show roots", ImageButton, [0,128,147,254]]  dist=0  → MATCH
```

Mismatch:
```
NodeAction: intended=["Show roots", ImageButton, [0,128,147,254]]
            found=["Oct 15, 2023...", LinearLayout, [0,128,1080,225]]  dist=218314  → MISMATCH
```

### Mismatch = failure, fall through to `gesture_tap`

If the found node's identity doesn't match the intended element, treat `node_action_click` as failure so `PointActionExecutorCore` falls through to `gesture_tap`:

```kotlin
val found = findClickableNodeAtLocation(root, x, y)
if (found != null && !matchesIntended(found, intendedText, intendedClass)) {
    Log.w(TAG, "Node mismatch: found=[${found.text}, ${found.className}] " +
               "intended=[$intendedText, $intendedClass] → falling through to gesture_tap")
    found.recycleCompat()
    return ActionResult.Failure("Node identity mismatch at ($x,$y)")
}
```

`gesture_tap` dispatches a raw touch event at the screen coordinates — Android's own view hit-testing resolves the correct visual target, bypassing the a11y tree overlap issue entirely.

### What to thread through

| Field | Source | Used for |
|-------|--------|----------|
| `text` | `ScreenElement.text` | Match comparison |
| `className` | `ScreenElement.className` | Match comparison |
| `bounds` | `ScreenElement.bounds` | Log output, optional strict match |

The info is already available in the `ScreenElement` that resolved the `element_index`. Thread it through:

```
PointActionExecutorCore.executePointAction(snapshot, elementIndex, ...)
  → reads ScreenElement from snapshot
  → passes (text, className, bounds) to NodeActionPerformer.performNodeClickAt(x, y, intended)
    → finds node via findClickableNodeAtLocation
    → logs intended vs found
    → verifies match before performAction
```

## Files to Change

| File | Change |
|------|--------|
| `platform/AccessibilityNodeFinder.kt` | `findActionableNodeAtLocation` → collect-all + closest-center |
| `platform/NodeActionPerformer.kt` | `performNodeClickAt` / `performNodeActionAt` — add intended element param, add logging, add mismatch guard |
| `tool/action/PointActionExecutorCore.kt` | Pass `ScreenElement` identity info to `performNodeClickAt` |

## Test Plan

- Unit test: `findActionableNodeAtLocation` with overlapping clickable nodes → returns closest-center
- Unit test: `findActionableNodeAtLocation` with nested clickable elements (button inside card) → still returns inner button
- Unit test: identity mismatch → returns `ActionResult.Failure`
- Integration: re-run `FilesMoveFile` eval → no more "Open with" loop

# P2 Fix Design: Click Node Overlap

## Problem Recap

`node_action_click` re-searches the live a11y tree by coordinates. When overlapping clickable nodes exist, the DFS z-order picks the wrong node. The wrong click succeeds, so `gesture_tap` fallback never fires.

## Design

### Part 1: Thread semantic target hint from perception to action

We keep coordinate-only behavior unchanged. For semantic targets (`element_index`, `text`), we thread identity fields from `PerceptionElement` into node-action dispatch.

**Data to thread**: `resourceId`, `text`, `description`, `className`, `bounds`.

**Threading path**:
```
TargetResolver.ResolveResult.Resolved(semanticHint?)
  → PointActionExecutorCore (pass hint to channel action)
    → UIAction.ClickNodeAt / LongClickNodeAt (semanticHint?)
      → AccessibilityPlatform / VirtualDisplayPlatform (pass-through)
        → NodeActionPerformer.performNodeClickAt / performNodeLongClickAt
```

### Part 2: Closest-center selection (AccessibilityNodeFinder)

Change `findActionableNodeAtLocation` internally from "return first DFS hit" to "collect all candidates, return closest center". No new function, no API change.

Algorithm at point `(x, y)`:

1. DFS: collect all candidates that contain `(x, y)` and match the predicate (no early return).
2. Pick the candidate whose center is closest to `(x, y)`.
3. Recycle all others.

Why this is sufficient:

- Click point = intended element's center by construction → distance = 0 → always wins.
- Handles nested clickables correctly (inner element's center is closer).
- Single algorithm for all callers (click, long-click, scroll). Scroll also benefits: nested scrollables → picks inner container, which is correct.
- Semantic correctness is enforced separately in Part 3 (mismatch guard), not duplicated in the finder.

No ranking by `resourceId`/`text`/`className` in the finder. Semantic matching belongs in Part 3's mismatch guard — that's the single place where `SemanticTargetHint` fields are compared against the found node. Mixing selection and verification in the finder adds complexity with no practical benefit (distance=0 already picks the right node; the guard catches any edge case).

### Part 3: NodeAction mismatch guard + logging

Before `performAction(ACTION_CLICK)` (or `ACTION_LONG_CLICK`), validate selected node against semantic hint. If semantic hint exists and selected node has no meaningful match, return `ActionResult.Failure` to trigger gesture fallback.

Logging is mandatory for debugging:

```kotlin
Log.d(
    TAG,
    "NodeAction target=($x,$y) intended=[id=$id text=$text class=$klass bounds=$bounds] " +
        "found=[id=${node.viewIdResourceName} text=${node.text} desc=${node.contentDescription} class=${node.className}]"
)
```

### Semantic hint shape on action

```kotlin
data class SemanticTargetHint(
    val resourceId: String,
    val text: String,
    val description: String,
    val className: String,
    val bounds: Bounds
)

data class ClickNodeAt(
    val x: Int,
    val y: Int,
    val semanticHint: SemanticTargetHint? = null
) : UIAction
```

For `Target.Coordinate`, `semanticHint` is `null`, behavior is unchanged.

## Files to Change

| File | Change |
|------|--------|
| `platform/AccessibilityNodeFinder.kt` | `findActionableNodeAtLocation`: collect-all + closest-center (internal change, no new function) |
| `platform/NodeActionPerformer.kt` | `performNodeClickAt` / `performNodeLongClickAt` accept hint; mismatch guard; structured logs |
| `platform/UIAction.kt` | `ClickNodeAt`, `LongClickNodeAt` add optional `semanticHint` |
| `tool/action/TargetResolver.kt` | `ResolveResult.Resolved` carries optional `semanticHint` |
| `tool/action/PointActionExecutorCore.kt` | Pass resolved semantic hint into channel action factory |
| `tool/action/ClickExecutor.kt` | Build `ClickNodeAt(..., semanticHint)` for semantic targets |
| `tool/action/LongPressExecutor.kt` | Build `LongClickNodeAt(..., semanticHint)` for semantic targets |
| `platform/AccessibilityPlatform.kt` | Pass `semanticHint` to `NodeActionPerformer` |
| `platform/virtualdisplay/VirtualDisplayPlatform.kt` | Pass `semanticHint` to `NodeActionPerformer` |

## Test Plan

- Unit test: overlapping clickable nodes (Show roots vs file row) → semantic finder selects intended toolbar button.
- Unit test: semantic mismatch → `ActionResult.Failure`, and click action is not executed.
- Unit test: no hint (coordinate target) → existing coordinate behavior unchanged.
- Unit test: long-press path uses same semantic guard and fallback behavior.
- Integration: FilesMoveFile eval (`20260224_171706`) no longer loops on "Open with".

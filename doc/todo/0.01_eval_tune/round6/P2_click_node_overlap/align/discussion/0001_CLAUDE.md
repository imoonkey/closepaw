# 0001 CLAUDE — Initial Draft

## Summary

Created initial `final/p2_click_node_overlap_design.md` merging both designs. The two designs agree on the core structure but differ in complexity.

## Where We Agree

1. **Collect all candidates, don't early-return on first DFS hit** — both designs move away from the current first-match-wins algorithm.
2. **Thread element identity from snapshot to node finder** — both agree the identity (text, className, etc.) must travel from `TargetResolver` through `UIAction` to `NodeActionPerformer`.
3. **Mismatch → Failure → gesture_tap fallback** — both agree a wrong-node match should fail and fall through.
4. **Always log intended vs found** — both agree on debug observability.
5. **Fix both click and long-press** — both mention `LongClickNodeAt`.

## Where We Differ

### 1. Selection algorithm: closest-center vs weighted scoring

**Claude**: closest-center (distance = 0 by construction).
**Codex**: 5-field weighted scoring (resourceId +6, className +3, text/desc +3, IoU 0-4, distance 0-2) with confidence thresholds (bestScore < 7 → NotFound, gap < 2 → Ambiguous).

**My position**: closest-center is sufficient and simpler. The click point IS the intended element's center by construction (the upper layer always clicks at center). Distance = 0 is a mathematically guaranteed match. The scoring weights are arbitrary (why +6 for resourceId?) and will need empirical tuning. KISS principle applies.

Scoring could be justified if clicks weren't always at centers, or if the coordinate drifted significantly between perception and action. But the perception→action delay is ~100ms, and UI doesn't restructure in that window.

### 2. Verification: simple match vs MatchResult sealed class

**Claude**: simple `matchesIntended(found, text, className)` → bool. Mismatch → `ActionResult.Failure`.
**Codex**: `MatchResult` sealed class with `Matched(node)`, `Ambiguous(reason)`, `NotFound(reason)`.

**My position**: the `Ambiguous` case doesn't add value. If the finder returned a node (closest-center, distance=0), and identity matches → success. If identity doesn't match → failure. There's no meaningful "ambiguous" state to handle differently from failure. Both lead to gesture_tap fallback. Adding a sealed class for the finder return type is over-engineering.

### 3. Identity fields: 3 vs 5

**Claude**: `text`, `className`, `bounds` (bounds for logging).
**Codex**: `bounds`, `className`, `resourceId`, `text`, `description`.

**My position**: `resourceId` is a good addition — it's locale-independent and stable. I've included `text`, `className`, `bounds` in the initial draft. We could add `resourceId` to the match function if it's available in `PerceptionElement`. Need to check if Perceptor currently captures `viewIdResourceName`.

### 4. File count: 7 vs 8

**Claude**: 7 files (AccessibilityNodeFinder, NodeActionPerformer, UIAction, TargetResolver, PointActionExecutorCore, ClickExecutor, LongPressExecutor).
**Codex**: 8 files (same + AccessibilityPlatform + VirtualDisplayPlatform).

Codex's inclusion of the two platform files is because `performAction(UIAction)` routing happens there. If we add fields to `UIAction.ClickNodeAt`, these platforms need to pass them through. This is correct — I've added the platforms to the file list in the final doc.

**EDIT**: Actually, looking at the code, `AccessibilityPlatform.performAction` and `VirtualDisplayPlatform.performAction` do `when(action) { is ClickNodeAt -> nodeActionPerformer.performNodeClickAt(action.x, action.y) }`. So yes, they'd need to pass the new fields. But that change is mechanical (just add params to the call). No design decision needed.

## Vote: CHANGES

Created initial draft with open questions for Codex to respond to.

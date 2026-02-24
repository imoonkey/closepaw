# 0003 CLAUDE — Simplify Part 2: closest-center, no ranking

## What I changed in `final/`

Replaced Part 2 "deterministic ranking" with pure closest-center selection. Removed the priority-based ranking (resourceId > text > className > distance). Semantic matching stays exclusively in Part 3 (mismatch guard).

## Why

The ranking in Part 2 duplicates the semantic comparison in Part 3. Two different places doing semantic string matching on the same fields is unnecessary complexity.

Separation of concerns:
- **Finder** (Part 2): geometric selection — "which node is at these coordinates?" → closest-center
- **Guard** (Part 3): semantic verification — "is this the right node?" → compare hint fields

Closest-center alone already picks the correct node in the P2 evidence case (distance=0 vs distance=467). The only scenario where ranking could differ from closest-center is:

- Node A: intended, resourceId match, center shifted 5px (rare — perception→action delay ~100ms)
- Node B: not intended, no resourceId match, center distance = 0

With closest-center: picks B → mismatch guard catches it → `gesture_tap` fallback → correct.
With ranking: picks A directly → `ACTION_CLICK` → correct.

Both produce correct outcomes. The ranking saves one fallback hop in an extremely rare case, at the cost of a more complex finder. Not worth it.

## Additional change: apply to all callers, not just click/long-click

Answering Codex's question: "keep semantic finder scoped to click/long-click only?"

Since we changed `findActionableNodeAtLocation` itself (not a new function), it applies to all callers — click, long-click, AND scroll. This is fine:
- For scroll: if nested scrollable containers overlap at a point, closest-center picks the inner one (correct behavior — inner container is what you'd scroll).
- No blast radius concern: closest-center is strictly better than arbitrary first-hit DFS for all use cases.

This also means we don't need a second function (`findBestClickableNodeAtLocation`), keeping `AccessibilityNodeFinder` simpler.

## What I didn't change

- Part 1 (`SemanticTargetHint`, threading path): no changes, fully accepted.
- Part 3 (mismatch guard + logging): no changes, fully accepted.
- `SemanticTargetHint` shape with `resourceId`: accepted. Confirmed `PerceptionElement` has all 5 fields (`resourceId`, `text`, `description`, `className`, `bounds`).
- File list: accepted 9 files. Only changed AccessibilityNodeFinder's description from "ranking" to "closest-center".

## Vote: CHANGES

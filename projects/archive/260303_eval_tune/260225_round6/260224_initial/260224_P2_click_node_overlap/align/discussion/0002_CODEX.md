# 0002 CODEX — Resolve Open Questions with KISS

## What I changed in `final/`

I updated `final/p2_click_node_overlap_design.md` to resolve the two open questions and remove tuning-heavy design parts.

1. Dropped weighted scoring and threshold tuning.
2. Dropped `MatchResult` (`Matched/Ambiguous/NotFound`) as an extra state layer.
3. Kept a simple deterministic semantic ranking strategy:
   - prefer `resourceId` match
   - then text/description match
   - then class match
   - then nearest center
4. Added explicit mismatch guard in `NodeActionPerformer`: semantic mismatch returns `ActionResult.Failure` and falls through to gesture.
5. Added required pass-through file updates for `AccessibilityPlatform` and `VirtualDisplayPlatform`.

## Why

Evidence from code:

- `TargetResolver.resolveElementPoint()` currently resolves semantic targets to element center.
- `ClickExecutor`/`LongPressExecutor` dispatch node action at that resolved point.

So nearest-center is already a strong baseline for this bug class. We still need semantic guardrails to avoid wrong-node success, but we do not need arbitrary score weights and thresholds.

## Remaining concern to confirm

- Are we OK to keep semantic finder scoped to click/long-click only (not scroll), to reduce blast radius?

## Vote

CHANGES

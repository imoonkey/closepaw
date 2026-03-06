# Design Review: Codex Click Reliability Design

**Reviewer**: Claude
**Date**: 2026-03-06
**Reviewing**: `design_codex.md`
**Cross-reference**: `design_claude.md`, updated `history_and_probe.md`

## Overall Assessment

Codex's design correctly identifies the three-contract separation (semantic / delivery / effect) and explicitly separates proven limits from our bugs. The evidence reading is accurate and the "what we should explicitly not do" section is excellent. The architectural direction is sound.

However, the design over-architects the solution relative to the evidence. The primary fix needed is narrow and concrete; the design proposes a broad re-architecture that risks another patch-and-regress cycle by introducing more complexity than the problem requires.

---

## What Codex Gets Right

### 1. Evidence interpretation (strong)

Codex correctly reads the updated probe:
- `node_action_click` at icon (94,761) works; at row/title center doesn't
- This is a hotspot-selection problem, not a broad transport failure
- gesture_tap and adb tap are genuinely weaker for this widget path

This matches my independent analysis exactly. No disagreement on evidence.

### 2. Separation of limits vs. bugs (strong)

The explicit separation in "Proven Facts" is clean and well-evidenced. The classification rules in Phase 3 are generalizable and useful beyond this specific fix.

### 3. "What we should explicitly not do" (strong)

All six points are correct. Especially point 6: "Do not interpret the updated Files probe as broad click transport failure."

---

## Concerns

### Concern 1 (HIGH): Over-architecture — SemanticClickPlan is premature

Codex proposes a `SemanticClickPlan` with `requestedTarget`, `resolvedElement`, `actionableCandidate`, `interactionPoints`, `evidence` — plus a full 7-state click state machine, typed result classification with 6 outcome classes, and a node re-query by semantic identity.

This is architecturally clean but disproportionate to the problem:

**The probe evidence shows a single, specific bug**: container promotion uses `container.center` as the click point, which misses clickable children (like the icon) within the container. The fix is to change how `refinePointActionTarget` selects the click point — prefer smaller clickable children within the container over the container itself.

That's a ~30-line change to `PointActionExecutorCore.kt`. Codex's proposal touches the entire click stack: introduces new data classes, replaces the node-finding mechanism, adds a planning layer, restructures result types, and adds multi-signal effect verification.

**Risk**: More architectural surface = more places for regressions. The archive shows 5 rounds of click work already. Each round added complexity. This round should reduce it.

### Concern 2 (HIGH): "Node re-query by semantic identity" doesn't match the evidence

Design decision 2 proposes replacing `findClickableNodeAtLocation` with semantic matching (bounds + class + resource id + label). The rationale is that point-based re-query is "unnecessary and unstable."

But the evidence shows the opposite: **point-based re-query works perfectly when given the right point.** `node_action_click` at (94,761) finds the icon and ACTION_CLICK works. The problem isn't the finding mechanism — it's which point we feed into it.

Semantic re-query introduces new failure modes:
- Node identity can change between perception and action (stale-node problem from Jan review)
- Resource IDs are optional and often missing
- Bounds can shift due to animations
- The current `logAndGuard` already does semantic verification as a guard, not a finder

The simpler path is: give `findClickableNodeAtLocation` a better point.

### Concern 3 (MEDIUM): Instrumentation before fix inverts the priority

P0 is `ClickAttemptReport` instrumentation. P1 is the actual semantic click re-architecture.

For the Files case, we already have the probe evidence. The hotspot-selection bug is proven. Adding a reporting framework before fixing the bug means another eval round of broken clicks while building diagnostics infrastructure.

Fix first, instrument as part of the fix. Don't create a separate instrumentation phase.

### Concern 4 (MEDIUM): Phase 1 matrix testing delays the fix unnecessarily

Phase 1 proposes building a "click mechanism matrix" across 3 different surfaces before changing any code. The evidence already shows what's broken: container promotion picks the wrong point. Probing more surfaces is useful but should happen after the fix, as validation — not before, as a prerequisite.

### Concern 5 (LOW): Effect verification expansion (Design Decision 5) is correct but scoped too broadly

Adding window/activity change, focus change, and subtree mutation as verification signals is a good idea. But it's independent of the click targeting fix and should be a separate workstream. Bundling it into the click reliability fix increases scope and review complexity.

---

## Specific Technical Feedback

### Design Decision 1: SemanticClickPlan

The concept of separating "which widget" from "which point" is right. But the implementation should be minimal:

```
// Current: refinePointActionTarget returns (container.center, container.hint)
// Fix:     refinePointActionTarget returns (bestChild.center, bestChild.hint)
```

This achieves the same semantic separation without a new data class or planning layer. The "actionable candidate" IS the best clickable child within the container.

### Design Decision 3: Typed result classification

Six outcome classes (`effect_confirmed`, `delivered_but_no_effect`, `target_mismatch`, `transport_blocked`, `verification_unavailable`, `out_of_platform_envelope`) are useful for debugging but shouldn't be a blocking prerequisite. The current `ActionOutcome` (Success/Failed/Cancelled) with `verified` flag and `attemptTrail` already captures most of this information.

If we want richer classification, add it as metadata to the existing `ActionOutcome`, not as a replacement.

### Design Decision 4: 7-state click state machine

The proposed states (PlanTarget → BindActionableCandidate → ChooseHotspot → DispatchChannel → VerifyEffect → Classify → FallbackOrStop) map cleanly to the current code flow:

- PlanTarget = `TargetResolver.resolve()`
- BindActionableCandidate + ChooseHotspot = `refinePointActionTarget()`
- DispatchChannel = the channel loop
- VerifyEffect = `buildPointActionOutcome()`
- Classify + FallbackOrStop = the channel fallback in the loop

The current code already IS this state machine, just not named as such. Refactoring it into an explicit state machine adds indirection without changing behavior.

---

## Alignment Points

Both designs agree on:

1. The Files failure is primarily a hotspot-selection bug in our container promotion, not a broad transport failure
2. The platform limit is that gesture_tap and adb tap are weaker than node_action_click for this widget
3. Overlay interception is a real historical bug but NOT the explanation for the current Files failure
4. App-specific workarounds should come last, after mechanism-level fixes
5. The fix must generalize beyond Files

## Key Disagreement

**Codex**: Fix this by re-architecting the entire semantic click pipeline with new data structures, semantic node re-query, typed result classes, and a formal state machine.

**Claude**: Fix this by changing one function (`refinePointActionTarget`) to prefer smaller clickable children within the container instead of using `container.center`. Everything else is secondary.

The evidence supports the narrow fix. The broad re-architecture addresses real design concerns but is not necessary to fix the proven bug, and risks introducing new regressions in a stack that has already regressed repeatedly from over-engineering.

---

## Recommended Alignment Path

1. **Agree on the primary fix**: Smart child selection in container promotion. Both designs point to this as the root cause. Implement it narrowly in `PointActionExecutorCore.kt`.
2. **Validate with the Files probe**: Re-run after the fix. If icon is now selected as click target and file opens, the hotspot-selection hypothesis is confirmed.
3. **Then decide on architectural expansion**: If the narrow fix works and generalizes, the SemanticClickPlan / typed classification work becomes a "nice to have for observability" rather than a prerequisite. If the narrow fix fails, the broader architecture becomes justified.
4. **Instrumentation as a companion, not a prerequisite**: Add `ClickAttemptReport`-style diagnostics incrementally alongside fixes, not as a blocking Phase 0.

# Design Review: Claude Click Reliability Design

**Reviewer**: Codex
**Date**: 2026-03-06
**Reviewing**: `design_claude.md`
**Cross-reference**: `design_codex.md`, updated `history_and_probe.md`

## Overall Assessment

Claude's revised design is now aligned with the strongest new evidence:

- `node_action_click` still works in Files at the icon hotspot
- the same mechanism fails at row center and title center
- `gesture_tap` and raw `adb tap` stay weak on this widget path

That means the main current issue is not broad click transport failure. It is much more likely a hotspot-selection regression introduced by our own container-promotion logic.

Claude gets that core diagnosis right, and the proposed first fix is appropriately narrow. The remaining disagreement is mostly about scope: I still want slightly more structured evidence around target choice, but I no longer think a broad click-stack rearchitecture should come before fixing the proven regression.

## What Claude Gets Right

### 1. The Files diagnosis is now correct

The strongest claim in the design is also the most important one:

- current `refinePointActionTarget` promotes a non-clickable child to the clickable container
- it then uses `container.center`
- in Files, that center lands on a row node whose `ACTION_CLICK` is a no-op for open
- the icon hotspot remains reachable by `node_action_click`

This is the sharpest current regression story we have.

### 2. The design cleanly separates platform limits from our bugs

Claude's limits section is defensible:

- Files row `gesture_tap` / `adb tap` weakness is a platform-or-app-surface limit for this state
- row-level `ACTION_CLICK` being a no-op for open is also not ours
- choosing `container.center` after promotion is ours

That separation is exactly what we needed from this round.

### 3. The proposed first fix matches the evidence

The suggested change to prefer a smaller actionable child inside the promoted container is the right first move because it directly addresses the proven symptom without adding new mechanism complexity.

The evidence supports:

- fix target/hotspot choice first
- keep node click as the primary semantic channel
- do not jump to double-tap or Files-specific workarounds

### 4. The design explicitly calls out likely regression

The strongest code-level regression candidate is recent:

- `39d836e` introduced container promotion that rewrites the action point to `container.center`

That does not mathematically prove the regression by itself, but combined with the probe matrix it is the best current explanation.

## Concerns

### Concern 1 (MEDIUM): Child selection needs tighter bounds than "smallest and nearest"

The heuristic is directionally right, but the final implementation should avoid drifting into unrelated controls on compound rows.

Examples:

- a row with a main icon plus overflow menu
- a settings row with a switch on the far right
- a card with multiple actionable badges

The design should keep the principle simple:

- only search inside the promoted actionable container
- prefer actionable descendants that are materially smaller than the container
- score by distance to the original resolved point
- fall back to the container if the child candidates are ambiguous or absent

That is still a narrow fix, but it avoids turning "pick any small clickable child" into a new source of regressions.

### Concern 2 (MEDIUM): We still need minimal target-choice diagnostics

I agree we should not block on a large instrumentation framework. But we do need enough evidence to verify the hotspot-selection fix actually did what we think.

Minimum useful diagnostics:

- resolved element bounds
- promoted container bounds
- chosen final target bounds
- chosen click point
- dispatch channel
- post-action effect result

Without that, another regression will again turn into guesswork.

### Concern 3 (LOW): Overlay work should stay separate from the primary fix

Claude already demotes overlay compared with the old draft, which is correct.

I agree overlay should remain in the plan, but only as a separate reliability track:

- it is a real historical bug
- it is not the leading explanation for the current Files symptom

### Concern 4 (LOW): The design should say more clearly that semantic node re-query is not rejected forever

I agree it should not be phase 1. But the broader architectural concern remains valid:

- semantic click currently still resolves to a point and then re-discovers a node by geometry

If the narrow hotspot fix does not generalize, that larger redesign becomes justified. The final aligned plan should preserve that escalation path.

## Alignment Points

Both designs now agree on:

1. This is not a broad click transport regression.
2. The updated Files probe points first to our hotspot-selection logic.
3. The strongest regression candidate is `container.center` promotion in `39d836e`.
4. `gesture_tap` / `adb tap` weakness in Files should be treated as a limit for this surface, not the primary bug.
5. App-specific workarounds should come only after executor-level fixes.

## Recommended Alignment Path

1. Implement the narrow hotspot-selection fix first in `PointActionExecutorCore.kt`.
2. Add minimal target-choice diagnostics alongside that fix.
3. Re-run the clean Files probe and Browser targeted eval.
4. Only if the fix fails to generalize, reopen the broader semantic-click rearchitecture.

## Final Judgment

Claude's revised design is now largely correct and much closer to the right implementation strategy than the earlier broader diagnosis.

The aligned path should be:

- narrow executor fix first
- tight validation second
- broader architecture only if the narrow fix fails

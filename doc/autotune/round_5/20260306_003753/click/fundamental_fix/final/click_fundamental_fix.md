# Click Fundamental Fix Plan

**Run**: `20260306_003753`
**Date**: 2026-03-06
**Inputs**:
- `qi_note.md`
- `history_and_probe.md`
- `mobile_action.md`
- `initial/design_claude.md`
- `initial/design_codex.md`
- `initial/design_review_claude.md`
- `initial/design_review_codex.md`

## Aligned Conclusion

This is not a broad click transport regression.

The current sharp diagnosis is:

> In Files, `node_action_click` still works at the icon hotspot, but our semantic container-promotion logic rewrites the click point to `container.center`, which lands on a row-level node whose `ACTION_CLICK` is a no-op for open. The strongest current regression candidate is the `39d836e` change that introduced this center-based promotion.

That conclusion is evidence-bounded, not speculative:

- clean probe with no visible overlay
- `node_action_click` changed UI at icon hotspot `(94,761)`
- the same mechanism was unchanged at row center and title center
- `gesture_tap` and raw `adb tap` stayed unchanged at all tested points

## What Is Proven

### Proven platform or app limits

- In this Files state, `gesture_tap` and raw `adb tap` are weaker than `node_action_click`.
- In this Files state, row-level `ACTION_CLICK` is not a reliable "open file" action.
- Android can report `ACTION_CLICK=true` even when the widget does not produce the intended effect.

These are real limits. We should detect and classify them, not pretend we can change them.

### Proven our bug

- `refinePointActionTarget()` currently promotes a non-actionable child to a clickable container and then uses `container.center`.
- For the Files row, that center is a dead zone for semantic open.
- Therefore our executor can turn a semantically correct target into a semantically wrong click point.

### Most likely regression

The strongest code-level regression candidate is `39d836e`:

- before that change, the system did not force promoted semantic clicks onto `container.center`
- after that change, text-targeted Files clicks deterministically resolve to the row center
- the clean probe shows row center is exactly where node click no-ops

This is still "most likely" rather than mathematically proven, because earlier successful runs could also have differed in activity state. But this is now the best explanation and the right implementation target.

## Primary Fix

### Fix the hotspot chosen during container promotion

Do not redesign the whole click stack first.

The first patch should stay narrow:

1. Keep current semantic target resolution.
2. If the resolved element is already actionable, keep it.
3. If it is not actionable, promote to the smallest actionable container that contains it.
4. Inside that promoted container, search for contained actionable descendants that are materially smaller than the container.
5. Score those descendants by distance to the original resolved point.
6. If there is a clear best child, use that child as the final semantic target and click point.
7. If candidates are absent or ambiguous, fall back to the container itself.

This keeps the core logic simple:

- semantic intent still starts from the original target
- promotion still handles non-clickable text children
- the final click point becomes hotspot-aware instead of center-biased

### Why this is the right first fix

- It directly addresses the proven Files symptom.
- It generalizes to other compound rows without app-name conditionals.
- It avoids masking the problem with extra fallback channels.
- It is much lower risk than re-architecting the whole semantic click pipeline before proving the narrow fix fails.

## Minimal Diagnostics To Add With The Fix

Do not build a large reporting framework first, but do add enough evidence so this does not regress silently again.

Record at least:

- original resolved element bounds
- promoted container bounds
- chosen final target bounds
- chosen click point
- dispatch channel
- post-action effect result

This can live in debug logs or attempt trail metadata. It does not need a new subsystem.

## Secondary Tracks

### Overlay hygiene

Overlay interference remains real, but it is a separate track from the current Files regression.

We should still make the execution overlay non-touchable during agent execution states, or otherwise guarantee that content-area touches are never intercepted while actions run.

### Verification quality

The recent post-action verification work was still correct and necessary. It should remain.

Possible follow-up improvements:

- activity or window transition signal
- focus or selection change signal
- safer `UiChangeDetector` behavior when image decode fails

But these are not the primary Files fix.

### Broader semantic-click redesign

If the narrow hotspot-selection fix does not generalize, then reopen the larger design track:

- semantic action planning
- semantic node re-query by intended candidate
- richer typed outcome classification

That is an escalation path, not phase 1.

## Things We Should Not Do

- Do not call this a broad click regression.
- Do not add double-tap as the first fix.
- Do not jump straight to Files-specific workarounds.
- Do not blame overlay for the current Files symptom by default.
- Do not keep promoted clicks hard-wired to `container.center`.

## Validation Plan

### Clean Files probe

Re-run the same clean matrix after the fix:

- row center
- title center
- icon center
- semantic click targeting `task.html`

Success criterion:

- semantic click on `task.html` should now choose the actionable child hotspot and open the file via `node_action_click`

### Browser targeted eval

Re-run:

- `BrowserDraw`
- `BrowserMultiply`

Success criterion:

- Browser entry no longer depends on long-press workaround
- early failure does not remain stuck at Files row open

### Compound-row regression check

Probe at least one surface with multiple actionable children in the same row.

Success criterion:

- the new heuristic does not drift into overflow/toggle controls when the main target is the row content

## Recommended Implementation Order

1. Patch `refinePointActionTarget()` to choose a bounded actionable child hotspot instead of `container.center`.
2. Add minimal target-choice diagnostics in the same patch.
3. Re-run the clean Files matrix.
4. Re-run Browser targeted eval.
5. Only if needed, continue to overlay hardening or broader semantic-click redesign.

## Bottom Line

The current click problem is best understood as a likely regression in our own target-to-hotspot mapping, not as a generic failure of Android click transport.

Fix the hotspot selection first. Keep the patch narrow. Validate it hard. Only then decide whether the click stack needs deeper redesign.

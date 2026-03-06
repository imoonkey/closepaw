# Click Execution Alignment And Action Plan

**Run**: `20260306_003753`
**Date**: 2026-03-06
**Aligned From**:
- `browser_click_execution_codex.md`
- `click_execution_scan_codex.md`
- `click_execution_scan_claude.md`
- `click_alignment_review_claude.md`
- `click_alignment_review_codex.md`

## Aligned Conclusion

This is not a broad click regression. All three independent analyses and both cross-reviews reject that framing.

The aligned diagnosis is:

> The action execution layer reports success based on dispatch acknowledgment, not semantic outcome. This **action-success verification gap** causes false-success across multiple action types (click, scroll, gesture_tap) and multiple apps. The clearest click-specific symptom is the Files row open path in the two Browser tasks.

That gives us three layers of problem, ordered by systemic scope:

1. **Action-success verification gap (systemic)**
   The executor treats dispatch acknowledgment as success. It does not verify whether the action produced a meaningful state change. This is the root cause shared by all false-success categories below.

2. **High-confidence localized symptoms**
   - Files `task.html` row click in `BrowserDraw` and `BrowserMultiply`: `node_action_click` reports success but often does not open the file. `long_press` on the same target works. (3 instances, both Browser tasks)
   - `a11y_scroll` on non-scrollable surfaces: reports "Success: Scrolled" when nothing moved. (5 instances across ExpenseAddMultipleFromGallery and ExpenseAddMultipleFromMarkor)
   - OsmAnd "INCREASE SEARCH RADIUS": button click dispatched, spinner flashes, results unchanged. (3 instances across OsmAndMarker and OsmAndTrack)

3. **Environment contamination**
   - Debug/agent overlay intercepts `gesture_tap` in content areas, producing no-op touches. (9 instances, largest single source of false-successes, primarily BrowserDraw)
   - Dev settings like `pointer_location` and `show_touches` add noise.

### Quantification

Claude's independent scan identified **25 high-confidence false-successes across 7 of 10 tasks**. Codex's scan flagged 20 suspicious turns, classifying 5-6 as strong. The divergence is primarily definitional: Claude counts every "Success with no observable effect" regardless of root cause; Codex filters for executor-specific evidence. Both counts are useful — Claude's for measuring agent impact, Codex's for isolating executor bugs.

Three tasks had **zero false-successes**: AudioRecorder, ClockTimerEntry, MarkorMergeNotes.

## What We Should Treat As Real

### High confidence

- Files row `node_action_click` false-success in Browser tasks
- `a11y_scroll` false-success on non-scrollable surfaces
- `gesture_tap` on overlay-covered content areas yielding no effect
- OsmAnd "INCREASE SEARCH RADIUS" no-op clicks

### Medium confidence

- OsmAnd search overlay gesture_taps that acknowledge input but leave results unchanged
- Stale-target clicks after page transitions (BrowserMultiply turn 22)
- Chrome first-run "Use without an account" non-responsive on first attempt

### Lower confidence / do not overfit

- Chooser / Chrome transitions where observation can collapse
- Save-in-place actions that legitimately keep the same screen
- BrowserDraw-only contaminated taps caused by overlay/dev state (real overlay problem, but not executor evidence)

## Action Plan

### P0. Instrumentation First

This is the highest-leverage change. It addresses the root verification gap and unblocks everything else.

1. **Post-action screen-diff signal.**
   After every action that reports Success, compare before/after a11y tree (root hash or top-N node fingerprints). If unchanged, annotate the result as `Success (no screen change detected)` instead of bare `Success`. Do NOT change the success/failure contract yet — just surface the signal so the LLM can self-correct.

2. **a11y_scroll outcome validation.**
   After scroll dispatch, check whether visible node positions shifted. If not, return `Failure: scroll had no effect`. This CAN be a hard failure since a no-op scroll is never useful. Eliminates the 5 scroll false-successes.

3. **Record executor context for debugging.**
   Include method used (`node_action_click`, `gesture_tap`, fallback path), target bounds, and whether the target area overlaps known overlay bounds.

### P1. Reproduce And Fix The Files Click Path

This is the clearest click-specific bug and should be debugged directly.

1. Run a minimal Files repro on a clean emulator.
   Compare:
   - `adb tap`
   - `node_action_click`
   - `gesture_tap`
   - `long_press`

2. Compare subtargets on the same row.
   - row center
   - icon
   - filename text

3. Only after repro, choose the fix:
   - executor bug fix if `node_action_click` is the problem
   - bounded fallback if Files row semantics are the issue

### P2. Remove Environment Confounders

The debug overlay is the single largest source of false-successes (9 instances) and directly blocked BrowserDraw from completing. This is operationally critical even though it is not an executor bug.

1. Set the debug overlay to `FLAG_NOT_TOUCHABLE` during task execution, or exclude its bounds from `gesture_tap` coordinate targeting.
2. Add pre-run hygiene checks for developer overlays like `pointer_location` and `show_touches`.
3. Mark contaminated runs explicitly so they do not get mixed into executor root-cause conclusions.

### P3. Add Minimal Workarounds Only Where Needed

These should come after P0/P1, not before.

1. **Files app workaround:**
   If single-click on a file row returns no observable effect, prefer `long_press -> Open with`. BrowserMultiply already discovered this recovery path.

2. **Let unchanged-success feedback drive recovery elsewhere.**
   Chrome first-run, OsmAnd overlay controls, and similar surfaces should mostly benefit from better action-result semantics (P0.1) rather than bespoke prompt tips.

3. **Chrome first-run and chooser transitions:**
   Monitor after P0.1 lands. If the screen-diff annotation helps the agent retry faster, no dedicated fix needed. Consider a short post-action delay + re-read for transitions that trigger new activities.

## Non-Goals

- Do not frame this as "all clicks regressed."
- Do not immediately add a pile of app-specific prompt rules.
- Do not treat every unchanged screen as identical in severity; some are core executor bugs, some are environment/app-surface limitations.
- Do not hard-fail every unchanged post-action screen — some actions (save, toggle) legitimately leave the screen unchanged. Use annotation, not hard failure (except for scroll).

## Recommended Order

1. Ship P0 instrumentation (screen-diff annotation + scroll validation).
2. Run the isolated Files repro and fix that path.
3. Remove overlay interference.
4. Re-evaluate Browser and OsmAnd traces with the new signals.
5. Only then decide whether any app-specific workaround still earns its keep.

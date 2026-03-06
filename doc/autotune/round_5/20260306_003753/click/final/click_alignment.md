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

This is not a broad click regression.

The aligned diagnosis is:

> The executor has an action-success verification gap. It reports success based on dispatch acknowledgement, but in several cases it does not know whether the action had a meaningful effect. The cleanest click-specific symptom is the Files row open path in the two Browser tasks.

That gives us three layers of problem:

1. High-confidence localized click bug
   Files `task.html` row click in `BrowserDraw` and `BrowserMultiply` reports success but often does not open the file.
2. Broader executor verification gap
   Some `scroll`, `gesture_tap`, and app-specific button actions also report success without meaningful state change.
3. Environment contamination
   Overlay/dev settings can create no-op touches that should not be mistaken for core executor regressions.

## What We Should Treat As Real

### High confidence

- Files row `node_action_click` false-success in Browser tasks
- `a11y_scroll` false-success on non-scrollable surfaces
- overlay-covered `gesture_tap` yielding no effect

### Medium confidence

- OsmAnd buttons/search-overlay actions that acknowledge input but leave results unchanged
- stale-target clicks after page transitions

### Lower confidence / do not overfit

- chooser / Chrome transitions where observation can collapse
- save-in-place actions that legitimately keep the same screen
- BrowserDraw-only contaminated taps caused by overlay/dev state

## Action Plan

### P0. Instrumentation First

This is the highest-leverage change.

1. Add a post-action before/after change signal to action results.
   Record whether the a11y tree or visible screen meaningfully changed after an action.
   Return a distinct outcome like `success_no_observable_effect` or annotate success text accordingly.

2. Make `scroll` outcome-validated.
   If node positions or visible content do not move after scroll dispatch, return failure instead of success.

3. Record executor context for debugging.
   Include method used (`node_action_click`, `gesture_tap`, fallback path), target bounds, and whether the target area overlaps known overlay bounds.

### P1. Reproduce And Fix The Files Click Path

This is the cleanest click-specific bug and should be debugged directly.

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

1. Make the agent/debug overlay non-intercepting during task execution.
2. Add pre-run hygiene checks for developer overlays like `pointer_location` and `show_touches`.
3. Mark contaminated runs explicitly so they do not get mixed into executor root-cause conclusions.

### P3. Add Minimal Workarounds Only Where Needed

These should come after P0/P1, not before.

1. Files app workaround:
   if single-click on a file row returns no observable effect, prefer `long_press -> Open with`.

2. Let unchanged-success feedback drive recovery elsewhere.
   Chrome first-run, OsmAnd overlay controls, and similar surfaces should mostly benefit from better action-result semantics rather than bespoke prompt tips.

## Non-Goals

- Do not frame this as "all clicks regressed."
- Do not immediately add a pile of app-specific prompt rules.
- Do not treat every unchanged screen as identical in severity; some are core executor bugs, some are environment/app-surface limitations.

## Recommended Order

1. Ship P0 instrumentation and scroll validation.
2. Run the isolated Files repro and fix that path.
3. Remove overlay interference.
4. Re-evaluate Browser and OsmAnd traces with the new signals.
5. Only then decide whether any app-specific workaround still earns its keep.

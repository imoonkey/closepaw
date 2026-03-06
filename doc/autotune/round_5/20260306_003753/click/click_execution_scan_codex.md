# Click Execution Scan - Codex

**Scope**: all 10 tasks in eval run `20260306_003753`

**Goal**: scan every `click` turn and identify places where execution likely reported success even though the UI did not actually respond.

## Summary

I scanned all `click` turns across these 10 tasks:

- `AudioRecorderRecordAudioWithFileName`
- `BrowserDraw`
- `BrowserMultiply`
- `ClockTimerEntry`
- `ExpenseAddMultipleFromGallery`
- `ExpenseAddMultipleFromMarkor`
- `ExpenseDeleteDuplicates2`
- `MarkorMergeNotes`
- `OsmAndMarker`
- `OsmAndTrack`

Using a simple first-pass heuristic:
- `tool_result` starts with `Success:`
- post-action `tool_observation_screen` is effectively unchanged from the previous screen

that yields **20 suspicious click turns**.

But after manual inspection, the real conclusion is narrower:

- **High-confidence execution false-success exists**
- It is **not** a broad system-wide click regression
- The strongest pattern is still the **Files row / file-open click** path from the two Browser tasks
- Many other flagged turns are better explained by:
  - observation collapse
  - focus/save actions that do not visibly mutate the sanitized screen
  - overlay/environment obstruction
  - app-specific no-op controls whose effect is not reflected in the visible tree

## Raw Scan Counts

| Task | Total clicks | Raw suspicious success+unchanged clicks |
|------|--------------|------------------------------------------|
| AudioRecorderRecordAudioWithFileName | 5 | 0 |
| BrowserDraw | 19 | 6 |
| BrowserMultiply | 14 | 5 |
| ClockTimerEntry | 5 | 0 |
| ExpenseAddMultipleFromGallery | 6 | 3 |
| ExpenseAddMultipleFromMarkor | 4 | 2 |
| ExpenseDeleteDuplicates2 | 14 | 0 |
| MarkorMergeNotes | 6 | 2 |
| OsmAndMarker | 13 | 1 |
| OsmAndTrack | 15 | 1 |

## High-Confidence Findings

### 1. Files file-open click is the clearest real false-success pattern

This is the same pattern already seen in the Browser-only analysis, and it remains the strongest signal in the full 10-task sweep.

#### BrowserDraw turn 2

- `click(element_index=17)` on `task.html`
- result: `Success: Clicked (296,978) via node_action_click`
- post-screen: still the same Files Downloads screen
- follow-up: `long_press` on the same target later enters selection mode

#### BrowserMultiply turn 2

- `click(element_index=17)` on `task.html`
- result: `Success: Clicked (540,763) via node_action_click`
- post-screen: still the same Files Downloads screen
- follow-up: `long_press` on the same target later enters selection mode

#### BrowserMultiply turn 12

- after clearing selection mode, the agent clicks the same `task.html` row again
- post-action a11y still looks unchanged
- the next screenshot shows Chrome loading the file

This one is weaker than turns 2/2 above, because the screenshots suggest the click may actually have worked and only the post-action observation missed the transition.

**Conclusion**:

The strongest, repeated execution-layer issue in this 10-task set is still:

- **Files row click is accepted**
- but **does not reliably trigger open**
- while long-press on the same target does work

That is a real bug candidate in the click path or target semantics for Files rows.

## Medium-Confidence Findings

### 2. BrowserDraw has extra same-family no-op taps

Additional BrowserDraw turns likely belong to the same family, but with more environmental noise:

- turn 18: tap left icon in Files row, no visible effect
- turn 21: tap filename region in Files row, no visible effect

These are plausibly the same underlying issue as the original Files row click false-success.

### 3. BrowserDraw Chrome-bottom-area taps are contaminated by overlays

BrowserDraw also flagged:

- turn 13: tap `Use without an account`
- turns 26-27: attempts to close visible overlay controls

I would **not** use these as core execution evidence. That run had:

- developer touch overlays enabled
- the agent takeover UI still visible at the bottom

So these turns are better explained by obstructed or contaminated targets.

## Likely False Positives From The Raw Heuristic

These turns were flagged by the raw scan, but they are weak evidence of click regression.

### 4. Chooser / Chrome transitions can collapse the post-action a11y tree

`BrowserMultiply` turns 6 and 7 were flagged because the post-action screen collapsed to only a few status-bar nodes:

- turn 6: `Open with`
- turn 7: choose `Chrome`

This does **not** look like clean no-op execution. The screenshot sequence shows the workflow moving forward into Chrome. The better diagnosis is:

- post-action observation is weak around chooser / Chrome transitions

### 5. Markor "open file" and "save" clicks are mostly observation-lag suspects

Flagged turns:

- `ExpenseAddMultipleFromMarkor` turn 3
- `MarkorMergeNotes` turn 7
- `MarkorMergeNotes` turn 19
- `ExpenseAddMultipleFromMarkor` turn 12

Why these are probably not strong execution bugs:

- after the "open file" clicks, the very next actions write scratchpad content that implies the file content was successfully available
- after the "save" clicks, the screen staying on the editor is not inherently wrong; some editors save in place without a visible transition

The underlying tasks still failed, but these specific turns are not strong evidence of click transport failure.

### 6. ExpenseAddMultipleFromGallery flagged turns are weak

Flagged turns:

- turn 4: click search field
- turn 15: click navigation drawer / menu area
- turn 16: click `Expense Logs`

These are low-confidence execution signals. The sanitized a11y tree after these clicks is very sparse, and the task's real failure was higher-level drift / wrong-source binding. I would not call these click regressions without a direct reproduction.

### 7. OsmAndMarker and OsmAndTrack flagged turns are weak

Flagged turns:

- `OsmAndMarker` turn 7: `INCREASE SEARCH RADIUS`
- `OsmAndTrack` turn 26: gesture tap in address flow

Both are plausible no-op taps, but OsmAnd is already a noisy map/search surface where many actions have weak or delayed visible effects. These should stay low-confidence unless reproduced directly.

## What Did Not Show A Problem

No raw suspicious success+unchanged clicks were found in:

- `AudioRecorderRecordAudioWithFileName`
- `ClockTimerEntry`
- `ExpenseDeleteDuplicates2`

That does not prove the click layer is perfect there, only that this scan did not find the same false-success signature.

## Overall Diagnosis

### Strong signal

- There is a **real localized click execution problem** around **Files row open behavior**
- It appears in both Browser tasks
- It is the only repeated, cross-task, same-surface click false-success pattern in this 10-task set

### Weak / mixed signal

- Several additional tasks have isolated suspicious clicks
- Most of those are better explained by observation collapse or app-specific UI behavior
- I do **not** see evidence for a broad “recent click regression everywhere”

## Recommended Next Debug Step

Do a clean isolated repro on Files only:

1. Open Files to Downloads with `task.html` visible
2. Compare:
   - adb tap on row center
   - a11y node click on row center
   - a11y gesture tap on row center
   - long press on row center
3. Also compare different subtargets:
   - row container center
   - file icon
   - filename text

If adb opens the file but a11y node click does not, the regression is in the a11y click path.
If neither opens reliably, the problem is more likely Files-row semantics or overlay/state contamination.

## Bottom Line

After sweeping all 10 tasks:

- **Yes**, there are clicks that likely reported success without producing the intended effect
- **But** the only strong repeated pattern is still the **Files `task.html` row click**
- The rest are mostly weak or mixed evidence, not enough to claim a general click execution regression

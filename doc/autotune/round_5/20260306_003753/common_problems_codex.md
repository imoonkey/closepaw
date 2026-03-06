# Round 5 GPT-5.4 Smoke Analysis — Common Problems

**Run ID**: `20260306_003753` | **Date**: 2026-03-06 | **Model**: gpt-5.4 (OpenAI)
**Tasks**: 10 evaluated | **Pass Rate**: 2/10 (20%)

## Run Notes

- This was an intentionally limited 10-task smoke from `never_succeeded`, not the full sweep, because parallel eval is still unstable.
- Mid-run environment issue: Android developer overlays `pointer_location=1` and `show_touches=1` were discovered during `BrowserDraw` and turned off before later tasks. `BrowserDraw` is therefore contaminated and should not be treated as a clean model signal.
- Summary metrics from `summary.json`: `goal_claim_precision=0.286`, `tool_failure_rate=0.039`.

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| AudioRecorderRecordAudioWithFileName | 0.0 | 9/30 | FalseCompletion — literal filename mismatch |
| BrowserDraw | 0.0 | 28/30 | Infra / environment contamination |
| BrowserMultiply | 1.0 | 29/30 | Success (fragile) |
| ClockTimerEntry | 1.0 | 7/30 | Success |
| ExpenseAddMultipleFromGallery | 0.0 | 17/30 | DestinationBindingFailure |
| ExpenseAddMultipleFromMarkor | 0.0 | 14/30 | DestinationBindingFailure |
| ExpenseDeleteDuplicates2 | 0.0 | 26/30 | CannotHandle (confirmed) |
| MarkorMergeNotes | 0.0 | 20/30 | FalseCompletion — `.md` extension trap |
| OsmAndMarker | 0.0 | 21/30 | FalseCompletion — map action misbound |
| OsmAndTrack | 0.0 | 28/30 | CannotHandle (confirmed) |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 2 | BrowserMultiply, ClockTimerEntry |
| FalseCompletion / verification gap | 3 | AudioRecorderRecordAudioWithFileName, MarkorMergeNotes, OsmAndMarker |
| Destination binding failure | 2 | ExpenseAddMultipleFromGallery, ExpenseAddMultipleFromMarkor |
| CannotHandle confirmed | 2 | ExpenseDeleteDuplicates2, OsmAndTrack |
| Infra / environment contamination | 1 | BrowserDraw |

## Key Takeaways

- **Net-new passes happened**: `BrowserMultiply` and `ClockTimerEntry` both passed for the first time in this eval line, though `BrowserMultiply` is still fragile at 29/30 turns.
- **The dominant failure mode is still false success**: 7/10 tasks ended with `GoalAchieved`, but only 2 were actually correct. The main remaining problem is not workflow discovery; it is verifying the exact requested end state before completion.
- **Cross-app/import tasks remain weak**: the agent can often read the source correctly, but it still loses the destination object/app and then treats nearby or preexisting state as completion.
- **The old parked capability gaps are still mostly real**: `ExpenseDeleteDuplicates2` and `OsmAndTrack` both failed safely in ways that reconfirm information-asymmetry / map-accessibility limits rather than exposing prompt bugs.

## Common Problems

### P1: Exact-Target Verification Before Completion — HIGH — 3 tasks

**Affected**: `AudioRecorderRecordAudioWithFileName`, `MarkorMergeNotes`, `OsmAndMarker`

**Root Cause**: The agent is still too willing to complete after approximate success:
- `AudioRecorderRecordAudioWithFileName`: claimed success after saving `presentation_fGwr`, not the literal requested `presentation_fGwr.m4a`.
- `MarkorMergeNotes`: merged content correctly but left Markor's extension field at `.md`, creating `mIObBbo4.md`.
- `OsmAndMarker`: reached the bottom action bar, misclicked adjacent `Share` instead of `Marker`, then still completed.

**Implication**: The next improvement should be a generic exact-target / post-action verification fix, not another per-app tip.

### P2: Source/Destination Binding Failure on Import Tasks — HIGH — 2 tasks

**Affected**: `ExpenseAddMultipleFromGallery`, `ExpenseAddMultipleFromMarkor`

**Root Cause**: The agent can extract or infer the source data, but it does not maintain a hard binding to the required destination:
- `ExpenseAddMultipleFromGallery`: drifted off the requested `expenses.jpg`, inspected other images, then treated already-present rows in Pro Expense as proof of success.
- `ExpenseAddMultipleFromMarkor`: extracted the right reimbursable rows, but logged them into Markor `To-Do` instead of the Pro Expense app.

**Implication**: Cross-app tasks need stronger source-artifact and destination-app/object verification before completion.

### P3: Hybrid Eval Hygiene Still Matters — MED — 1 task

**Affected**: `BrowserDraw`

**Root Cause**: This run exposed environment contamination, not a clean cognition failure. Developer touch overlays were enabled, and Chrome also had first-run/setup friction. The agent reported being blocked by an on-screen overlay, which matched the emulator state we verified mid-run.

**Implication**: Re-run `BrowserDraw` only after confirming clean emulator hygiene at task start. Do not use this run as evidence for or against prompt changes on browser-canvas tasks.

### P4: Existing CannotHandle Calls Still Stand — MED — 2 tasks

**Affected**: `ExpenseDeleteDuplicates2`, `OsmAndTrack`

**Root Cause**:
- `ExpenseDeleteDuplicates2`: deletion UI still did not expose enough verified identity to delete safely.
- `OsmAndTrack`: OsmAnd still failed to expose exact locality waypoints reliably through the accessible route/search flows; the route planner degraded the first point to generic `Country Liechtenstein`.

**Implication**: These should stay parked until there is a capability/tooling unlock, not prompt polish.

## Next Steps

### Priority
1. Work on a generic exact-target completion check. The pattern is now broader than the old Markor-specific extension bug.
2. Work on destination binding for cross-app import/logging tasks.
3. Re-run `BrowserDraw` only in a clean environment.

### Do Not Do
- Do not respond to this smoke by adding more task-specific app tips. The dominant failures here are generic verification and binding errors.
- Do not treat `OsmAndTrack` or `ExpenseDeleteDuplicates2` as prompt-tuning targets right now.

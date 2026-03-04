# Common Problems — Round 2 (20260304_033425)

**Agent**: qwen3.5 | **Model**: basic | **Perception**: accessibility_only
**Run**: 20260304_033425 | **Tasks**: 8 | **Score**: 6/8 (75%)

## Summary

Round 2 applied 8 fixes from Round 1's approved next steps. Results show strong improvement: 5 tasks flipped from 0→1, 1 new task passed, 2 tasks remain at 0.

| Task | R1 Score | R2 Score | Delta | Fix Applied |
|------|----------|----------|-------|-------------|
| MarkorAddNoteHeader | 0 | 0 | — | P4 cursor positioning + Q3 keyboard flag |
| MarkorCreateNote | 0 | **1** | +1 | P3 Markor extension tip |
| MarkorEditNote | 0 | **1** | +1 | P4 cursor positioning + Q3 keyboard flag |
| RecipeDeleteMultipleRecipes | — | **1** | new | Q12 write_todos re-enabled |
| RetroCreatePlaylist | 0 | **1** | +1 | P1 hint text fix |
| RetroPlaylistDuration | 0 | 0 | — | P5 Retro Music tips |
| RetroSavePlaylist | — | **1** | +1 | P1 hint text + P5 tips |
| SportsTrackerActivitiesOnDate | 0 | **1** | +1 | P2 completion verification + P5 OpenTracks tips |

## Remaining Problems

### P1: MarkorAddNoteHeader — `\n` vs `\n\n` for blank lines (Reasoning)

**Severity**: High | **Recurrence**: 2 rounds | **Tasks**: MarkorAddNoteHeader

The agent uses `\n` (single newline) when the task requires "a new blank line" (`\n\n` double newline). This is the exact same bug as R1. The agent:
1. Correctly used Special Key > Document Start (P4 tip worked!)
2. Typed header text with `\n` instead of `\n\n`
3. Verified the file content but didn't notice the missing blank line
4. Declared success — false positive (worse than R1's exhausted-turns-trying-to-fix behavior)

**Root cause**: Reasoning — "blank line" → `\n\n` interpretation is missing.

### P2: RetroPlaylistDuration — Wrong strategy for duration-constrained tasks (Reasoning)

**Severity**: High | **Recurrence**: 3+ rounds | **Tasks**: RetroPlaylistDuration

The agent tries to pre-read individual song durations (via Details dialogs, ~3 turns each) instead of using the iterative add-and-check approach. The playlist card shows a running total ("N Songs / MM:SS") that updates after each addition, but the agent never exploits this.

Used 30/30 turns, added only 2 songs, then self-reported failure.

**Root cause**: Reasoning — chose wrong strategy despite visible running total.

### Q1: False-positive completion on MarkorAddNoteHeader

The R2 agent is more confident but wrong on MarkorAddNoteHeader. In R1 it at least detected the content problem and tried to fix it (failing after 30 turns). In R2 it declares success at turn 15 without noticing the missing blank line. The P2 completion verification checklist may not be specific enough about whitespace/formatting.

### Q2: write_todos not used by RecipeDeleteMultipleRecipes

The Q12 fix re-enabled write_todos and added soft guidance, but RecipeDeleteMultipleRecipes (3 items) completed successfully without using it. This makes sense — 3 items is simple enough. The tool's value will be clearer on larger delete/add tasks.

### Q3: keyboard_visible flag not observed

The keyboard_visible flag was implemented in Perceptor (Phase D) but was NOT present in any MarkorAddNoteHeader observation. Need to verify the APK was rebuilt and installed before the eval run. If the APK wasn't updated, this feature wasn't actually tested.

## What Worked

1. **P3 Markor extension tip** → MarkorCreateNote: 0→1. Agent correctly handled the filename.
2. **P4 cursor positioning tip** → MarkorEditNote: 0→1 in just 7 turns. Agent used Special Key > Document Start efficiently.
3. **P1 hint text fix** → RetroCreatePlaylist: 0→1, RetroSavePlaylist: new→1. Playlist name typing no longer has hint contamination.
4. **P2 + P5 completion verification + OpenTracks tips** → SportsTrackerActivitiesOnDate: 0→1. Agent correctly used Edit dialog to find activity type.
5. **Q7 agent_thought ordering** → 0 tool failures across 101 calls in 6 passing tasks. Clean execution.

## Next Steps

### Must Fix (for Round 3)

1. **Blank line interpretation rule** (P1 recurrence): Add explicit system prompt guidance: "When the task says 'blank line' or 'empty line', use `\n\n` (double newline). A single `\n` creates a line break, NOT a blank line."

2. **Duration-constrained playlist strategy** (P2 recurrence): Add system prompt tip for Retro Music: "For playlist duration targets, use iterative add-and-check: add songs one by one, navigate to playlist view to read the running total (visible as 'N Songs / MM:SS'), stop when target is reached. Do NOT pre-read individual song durations."

3. **Verify APK rebuild** (Q3): Confirm the keyboard_visible flag is actually present in the deployed APK. Run a quick debug test with a text input task to verify the flag appears.

### Should Fix

4. **False-positive guard for formatting tasks**: Strengthen the completion verification to explicitly check whitespace/formatting: "For text editing tasks: verify exact whitespace, newlines, and formatting match the requirement."

5. **Never self-report failure with budget remaining**: Add guidance: "Never call complete_task(status='failure') if you have turns remaining and can still make progress. Always attempt the best possible outcome."

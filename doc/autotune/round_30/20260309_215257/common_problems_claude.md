# Round 30 Eval Analysis — Common Problems (Final)

**Run ID**: `20260309_215257` | **Date**: 2026-03-09 | **Model**: gpt-5.4
**Tasks**: 2 evaluated | **Pass Rate**: 1/2 = 50%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| ExpenseAddMultipleFromGallery | 0.0 | 19 | Error — image reading unreliable, field alignment issues |
| RecipeAddMultipleRecipesFromImage | 1.0 | 27 | Success — hybrid perception + 60 turns |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 1 | RecipeAddMultipleRecipesFromImage |
| FieldAlignment + ImagePerception | 1 | ExpenseAddMultipleFromGallery |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| RecipeAddMultipleRecipesFromImage max_turns=60 | **Working** | PASSED (0.0→1.0, 27/60 turns) |
| ExpenseAddMultipleFromGallery max_turns=60 | Ineffective | Error at turn 19 — didn't even use extra turns |

## Common Problems

### P1: ExpenseAddMultipleFromGallery — persistent failures — HIGH — 1 task

**Affected**: ExpenseAddMultipleFromGallery

**Root Cause**: 3rd failure (R28, R29, R30). Agent hit Error at turn 19/60. Only added 2 expenses (Mortgage, Shoes) before failing. Persistent issues: (1) image reading unreliable for expense amounts, (2) Pro Expense name/note field confusion, (3) Specialty Foods amount not readable from image.

**Proposed Fix**:
- **RE-PARK**: Persistent field alignment issues + image-to-form data entry unreliable across 3 attempts with increasing turn budgets. No remaining fix ideas without code changes.

## Next Steps

**None — all tasks resolved.** Unpark loop complete.

### Final Loop Summary (R22-R30)

| Status | Count | Tasks |
|--------|-------|-------|
| **FIXED** | 4 | BrowserMaze (R22), SportsTracker (R25), RecipeDeleteDuplicateRecipes2 (R28), RecipeAddMultipleRecipesFromImage (R30) |
| **Re-parked** | 9 | MarkorDeleteNewestNote, OsmAndTrack, BrowserDraw, AudioRecorderRecordAudioWithFileName, RetroPlaylistDuration, OsmAndMarker, RecipeDeleteDuplicateRecipes3, ExpenseDeleteDuplicates2, ExpenseAddMultipleFromGallery |

### Accept as Capability Gap
- **ExpenseAddMultipleFromGallery**: Field alignment + image perception unreliable (re-parked)

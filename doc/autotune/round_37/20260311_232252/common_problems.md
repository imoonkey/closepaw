# Round 37 Eval Analysis — Common Problems

**Run ID**: `20260311_232252` | **Date**: 2026-03-11 | **Model**: gpt-5.4
**Tasks**: 8 evaluated | **Pass Rate**: 2/8 = 25.0%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| BrowserMultiply | 1.0 | 26 | Success (flaky) |
| ExpenseAddMultipleFromGallery | 1.0 | 31 | Success (flaky) |
| ExpenseAddMultipleFromMarkor | 0.0 | 23 | ActionFailure |
| RecipeDeleteDuplicateRecipes2 | 0.0 | 25 | FalseCompletion |
| RecipeDeleteDuplicateRecipes3 | 0.0 | 51 | FalseCompletion |
| RetroCreatePlaylist | None | 21 | ScorerBug |
| RetroPlaylistDuration | None | 20 | PerceptionGap + ScorerBug |
| RetroSavePlaylist | None | 28 | ScorerBug |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 2 | BrowserMultiply, ExpenseAddMultipleFromGallery |
| FalseCompletion | 2 | RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3 |
| ActionFailure | 1 | ExpenseAddMultipleFromMarkor |
| ScorerBug | 2 | RetroCreatePlaylist, RetroSavePlaylist |
| PerceptionGap | 1 | RetroPlaylistDuration |

## Previous Round Comparison (R36 → R37)

| Task | R36 | R37 | Change |
|------|-----|-----|--------|
| BrowserMultiply | 0.0 | 1.0 | Flaky pass (no skill changes) |
| ExpenseAddMultipleFromGallery | 0.0 | 1.0 | Flaky pass (no skill changes) |
| ExpenseAddMultipleFromMarkor | 0.0 | 0.0 | Same failure pattern |
| RecipeDeleteDuplicateRecipes2 | 0.0 | 0.0 | Same failure pattern |
| RecipeDeleteDuplicateRecipes3 | 0.0 | 0.0 | Same failure pattern |
| RetroCreatePlaylist | None | None | Same scorer bug |
| RetroPlaylistDuration | None | None | Same perception gap |
| RetroSavePlaylist | None | None | Same scorer bug |

**Changes applied for R37**: Added "compare all fields" one-liner to Broccoli skill, "re-read fields before Save" to Expense skill. Neither prevented the underlying failures.

## Common Problems

### P1: Duplicate Deletion Count Tracking — MED — 2 tasks

**Affected**: RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3
**Root Cause**: Same as R36. Agent follows "compare all fields" guidance but loses count during high-multiplicity deletion. List reindexing shifts items. Agent claims GoalAchieved without accurate verification.
**Status**: 2nd consecutive failure. Will be confirmed as accepted regression if R38 also fails.

### P2: Cross-App Form Field Mismatch — MED — 1 task

**Affected**: ExpenseAddMultipleFromMarkor
**Root Cause**: Same as R36. Text routes to wrong field during Markor → Expense workflow. "Re-read fields before Save" guidance added but insufficient — agent recovers target field but doesn't re-verify corrupted field.
**Status**: 2nd consecutive failure. Action-layer bug, not knowledge gap.

### P3: Retro Music Scorer SQL Bug — LOW — 2 tasks

**Affected**: RetroCreatePlaylist, RetroSavePlaylist
**Root Cause**: Same SQL syntax error as R36. Infrastructure issue.
**Status**: Parked as infra issue after 2 consecutive None scores.

### P4: Song Duration Inaccessible — LOW — 1 task

**Affected**: RetroPlaylistDuration
**Root Cause**: Same as R36. Song durations not in accessibility tree.
**Status**: Accepted regression (eval-specific algorithm removed in generalization).

## Next Steps

- BrowserMultiply and ExpenseAddMultipleFromGallery removed from active list (passed).
- RetroCreatePlaylist, RetroPlaylistDuration, RetroSavePlaylist parked as scorer/infra/perception issues.
- RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3, ExpenseAddMultipleFromMarkor: final confirmation round in R38.

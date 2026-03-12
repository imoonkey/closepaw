# Round 38 Eval Analysis — Common Problems

**Run ID**: `20260312_000547` | **Date**: 2026-03-12 | **Model**: gpt-5.4
**Tasks**: 3 evaluated | **Pass Rate**: 0/3 = 0.0%

## Scorecard

| Task | Score | Root Cause |
|------|-------|------------|
| ExpenseAddMultipleFromMarkor | 0.0 | ActionFailure |
| RecipeDeleteDuplicateRecipes2 | 0.0 | FalseCompletion |
| RecipeDeleteDuplicateRecipes3 | 0.0 | FalseCompletion |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| FalseCompletion | 2 | RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3 |
| ActionFailure | 1 | ExpenseAddMultipleFromMarkor |

## Previous Round Comparison (R37 → R38)

All 3 tasks failed in R37 as well. No new skill changes were applied for R38 — this was a confirmation round.

| Task | R36 | R37 | R38 | Status |
|------|-----|-----|-----|--------|
| RecipeDeleteDuplicateRecipes2 | 0.0 | 0.0 | 0.0 | Accepted regression |
| RecipeDeleteDuplicateRecipes3 | 0.0 | 0.0 | 0.0 | Accepted regression |
| ExpenseAddMultipleFromMarkor | 0.0 | 0.0 | 0.0 | Accepted regression |

## Common Problems

### P1: Duplicate Deletion Count Tracking — 2 tasks

**Affected**: RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3
**Root Cause**: Agent loses count during high-multiplicity duplicate group deletion. List reindexing shifts items after each delete. Agent claims GoalAchieved without accurate post-deletion verification.
**Status**: Accepted regression. General "compare all fields" guidance already in skill. Further fix would require prescriptive deletion algorithm (eval-specific).

### P2: Cross-App Form Field Mismatch — 1 task

**Affected**: ExpenseAddMultipleFromMarkor
**Root Cause**: Text entry routes to wrong field during Markor → Expense cross-app workflow. Agent recovers target field but doesn't re-verify corrupted field. General "re-read fields before Save" guidance already in skill but insufficient.
**Status**: Accepted regression. Root cause is action execution reliability, not skill knowledge.

## Next Steps

All 3 tasks accepted as regressions after failing 3 consecutive rounds (R36-R38). `generalize_active.txt` emptied. Skill generalization loop complete.

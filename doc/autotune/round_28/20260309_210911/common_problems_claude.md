# Round 28 Eval Analysis — Common Problems

**Run ID**: `20260309_210911` | **Date**: 2026-03-09 | **Model**: gpt-5.4
**Tasks**: 4 evaluated | **Pass Rate**: 1/4 = 25%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| ExpenseAddMultipleFromGallery | 0.0 | 30 | TurnExhaustion — added 3/6 expenses, Error |
| ExpenseDeleteDuplicates2 | 0.0 | 33 | Found duplicate pair but evaluator still says wrong |
| RecipeAddMultipleRecipesFromImage | 0.0 | 30 | Incomplete recipe data from image |
| RecipeDeleteDuplicateRecipes2 | 1.0 | 41 | Success — Broccoli field comparison skill + 50 turns |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 1 | RecipeDeleteDuplicateRecipes2 |
| TurnExhaustion | 2 | ExpenseAddMultipleFromGallery, RecipeAddMultipleRecipesFromImage |
| ScrollGap | 1 | ExpenseDeleteDuplicates2 |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| RecipeDeleteDuplicateRecipes2 max_turns=50 | **Working** | PASSED (0.0→1.0, 41 turns) |
| Pro Expense perturbed group strategy | Improved | Agent found "Pest Control" duplicate pair, but evaluator disagrees — may be wrong pair |
| Broccoli field comparison | Working | RecipeDeleteDuplicateRecipes2 passed |

## Common Problems

### P1: ExpenseDeleteDuplicates2 — wrong duplicate identified — HIGH — 1 task

**Affected**: ExpenseDeleteDuplicates2

**Root Cause**: 3rd real failure (R25, R26, R28). Agent found 41/42 items, identified "Pest Control | 234.63" as duplicate pair, deleted one. But evaluator says wrong. Either (a) wrong pair identified, or (b) scroll gap caused agent to miss the actual duplicate.

**Proposed Fix**:
- **RE-PARK**: Scroll gap prevents reliable 100% list coverage. 1 missing item is always the duplicate's partner. R27 excluded as unfair test.

---

### P2: ExpenseAddMultipleFromGallery — turn budget tight — MED — 1 task

**Affected**: ExpenseAddMultipleFromGallery

**Root Cause**: 1st attempt. Agent added 3 expenses (Mortgage, Shoes, Furnishing) but couldn't finish remaining items. 30 turns insufficient for 6 expenses. Image reading partially successful.

**Proposed Fix**:
- Increase max_turns to 50. Add hybrid perception for image reading.
- Owner layer: default.yaml task_overrides

---

### P3: RecipeAddMultipleRecipesFromImage — incomplete data — MED — 1 task

**Affected**: RecipeAddMultipleRecipesFromImage

**Root Cause**: 1st attempt. 30 turns used. Agent added recipes but evaluator said wrong — likely incomplete or incorrect recipe data read from image.

**Proposed Fix**:
- Increase max_turns to 60. Hybrid perception already active.
- Owner layer: default.yaml task_overrides

## Next Steps

### Must Fix
1. **ExpenseAddMultipleFromGallery max_turns=50**: Turn budget too tight for 6 expenses
   - Targets: ExpenseAddMultipleFromGallery
   - Owner layer: default.yaml task_overrides

2. **RecipeAddMultipleRecipesFromImage max_turns=60**: More turns for image reading + data entry
   - Targets: RecipeAddMultipleRecipesFromImage
   - Owner layer: default.yaml task_overrides

### Accept as Capability Gap
- **ExpenseDeleteDuplicates2**: Scroll gap prevents reliable full-list coverage (re-parked)

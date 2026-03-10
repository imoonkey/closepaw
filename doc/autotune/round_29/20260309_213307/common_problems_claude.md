# Round 29 Eval Analysis — Common Problems

**Run ID**: `20260309_213307` | **Date**: 2026-03-09 | **Model**: gpt-5.4
**Tasks**: 2 evaluated | **Pass Rate**: 0/2 = 0%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| ExpenseAddMultipleFromGallery | 0.0 | 36 | Field alignment issues — added expenses but wrong data |
| RecipeAddMultipleRecipesFromImage | 0.0 | 29 | Incomplete — only added 2 recipes (Avocado Toast, Veggie Soup) |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| FieldAlignment | 1 | ExpenseAddMultipleFromGallery |
| IncompleteData | 1 | RecipeAddMultipleRecipesFromImage |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| ExpenseAddMultipleFromGallery max_turns=50 | Partial | 36 turns used, agent finished but wrong data |
| RecipeAddMultipleRecipesFromImage max_turns=50 | Partial | 29 turns used, only 2 recipes added |

## Common Problems

### P1: Pro Expense field alignment — HIGH — 1 task

**Affected**: ExpenseAddMultipleFromGallery

**Root Cause**: 2nd attempt. Agent claimed GoalAchieved at 36/50 turns. Added expenses but evaluator says wrong — persistent name/note field confusion in Pro Expense. Agent also created a duplicate Mortgage entry and tried to fix it.

**Proposed Fix**:
- Increase max_turns to 60. Add explicit field targeting in Pro Expense skill.
- Owner layer: app skill: `com.arduia.expense` + default.yaml

---

### P2: RecipeAddMultipleRecipesFromImage — incomplete recipe extraction — HIGH — 1 task

**Affected**: RecipeAddMultipleRecipesFromImage

**Root Cause**: 2nd attempt. Only added 2 recipes (Avocado Toast, Veggie Soup) but image likely has more. Agent claimed success. Image reading needs improvement or more turns for all recipes.

**Proposed Fix**:
- Increase max_turns to 60. Ensure hybrid perception reads entire image.
- Owner layer: default.yaml task_overrides

## Next Steps

### Must Fix
1. **Increase max_turns to 60 for both tasks**: More budget needed
   - Targets: ExpenseAddMultipleFromGallery, RecipeAddMultipleRecipesFromImage
   - Owner layer: default.yaml task_overrides

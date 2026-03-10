# Round 27 Eval Analysis — Common Problems

**Run ID**: `20260309_204943` | **Date**: 2026-03-09 | **Model**: gpt-5.4
**Tasks**: 4 evaluated | **Pass Rate**: 0/4 = 0%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| ExpenseDeleteDuplicates2 | 0.0 | 15 | Bad guidance — name sort doesn't exist in Pro Expense |
| OsmAndMarker | 0.0 | 27 | Infra failure + search can't find Planken |
| RecipeDeleteDuplicateRecipes2 | 0.0 | 30 | TurnExhaustion — compared Caprese recipes, ran out of turns |
| RecipeDeleteDuplicateRecipes3 | 0.0 | 49 | False success — claimed no duplicates found |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| BadGuidance | 1 | ExpenseDeleteDuplicates2 |
| InfraFailure + SearchFailure | 1 | OsmAndMarker |
| TurnExhaustion | 1 | RecipeDeleteDuplicateRecipes2 |
| FalseCompletion | 1 | RecipeDeleteDuplicateRecipes3 |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| Pro Expense name sort guidance | **Harmful** | Name sort doesn't exist — agent wasted all 15 turns |
| Broccoli field comparison skill | Not tested on Recipe2 yet (1st attempt) | Agent ran out of turns at 30 |

## Common Problems

### P1: Incorrect Pro Expense name sort guidance — HIGH — 1 task

**Affected**: ExpenseDeleteDuplicates2

**Root Cause**: Told agent to sort by NAME in Filter, but Pro Expense Filter only supports date sorting. Agent wasted all 15 turns trying to find nonexistent option. **Not a fair failure** — guidance was wrong.

**Proposed Fix**:
- Revert to scroll-based approach with "perturbed group" strategy. Remove name-sort instruction.
- Owner layer: app skill: `com.arduia.expense`

---

### P2: OsmAndMarker — search + infra unreliable — HIGH — 1 task

**Affected**: OsmAndMarker

**Root Cause**: 3rd consecutive failure (R24, R26, R27). Infra failure on attempt 0. Retry: searched "Planken, Liechtenstein" but OsmAnd opened blank/wrong view. Search doesn't reliably resolve small localities. Marker button not in a11y tree.

**Proposed Fix**:
- **RE-PARK**: OsmAnd search unreliable for small localities + Marker button not accessible in a11y tree + recurring infra failures.

---

### P3: RecipeDeleteDuplicateRecipes3 — turn budget insufficient — HIGH — 1 task

**Affected**: RecipeDeleteDuplicateRecipes3

**Root Cause**: 3rd consecutive failure (R23, R26, R27). 49/60 turns, false success — claimed no duplicates found but evaluator disagrees. 30+ recipes to scan, compare, and delete. Complexity 3.4.

**Proposed Fix**:
- **RE-PARK**: Turn budget structurally insufficient for this task's complexity.

---

### P4: RecipeDeleteDuplicateRecipes2 — insufficient turns — MED — 1 task

**Affected**: RecipeDeleteDuplicateRecipes2

**Root Cause**: 1st attempt. Agent identified "Caprese" recipes appearing multiple times, started comparing fields but ran out of turns at 30. Broccoli skill field-comparison guidance is working but needs more turns.

**Proposed Fix**:
- Increase max_turns to 50 for RecipeDeleteDuplicateRecipes2.
- Owner layer: default.yaml task_overrides

## Next Steps

### Must Fix
1. **RecipeDeleteDuplicateRecipes2 max_turns=50**: Agent nearly succeeded, just needs more turns
   - Targets: RecipeDeleteDuplicateRecipes2
   - Owner layer: default.yaml task_overrides

2. **Pro Expense skill revert**: Remove name-sort, use perturbed group scroll strategy
   - Targets: ExpenseDeleteDuplicates2
   - Owner layer: app skill: `com.arduia.expense`

### Accept as Capability Gap
- **OsmAndMarker**: Search unreliable + marker button not in a11y tree (re-parked)
- **RecipeDeleteDuplicateRecipes3**: Turn budget structurally insufficient (re-parked)

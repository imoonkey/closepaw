# RecipeDeleteDuplicateRecipes2 — R36 Analysis

**Score**: 0.0 | **Turns**: 22 | **Stop**: GoalAchieved | **Model**: gpt-5.4

## Task Goal

Delete exactly one of two duplicate Caprese Salad Skewers entries in Broccoli while leaving all other recipes (including visually identical noise rows) intact.

## Agent Behavior

- Opened Broccoli and immediately saw 5 copies of "Caprese Salad Skewers | An ideal recipe for experimenting with different flavors and ingredients." plus 1 copy of "Caprese Salad Skewers | A delicious and healthy choice for any time of the day." — never scrolled before acting.
- Recorded "visible count 5" in scratchpad and treated all 5 as exact duplicates of each other, planning to delete 4 and keep 1.
- Executed 4 delete cycles (turns 4–7, 8–11, 12–15, 16–19), each opening a Caprese card → overflow → Delete → DELETE confirm.
- Final screen shows 1 Caprese "An ideal recipe", 1 Caprese "A delicious and healthy", 3 Pan-Seared Salmon variants, 1 Raspberry Almond Smoothie (6 of ~8 remaining rows visible).
- Declared GoalAchieved after turn 20 scratchpad confirmed "no repeated exact title+description pairs visible."

## Root Cause

The task setup (RecipeDeleteDuplicateRecipes2, n_rows_noise=10) deliberately fills noise with same-title-same-description Caprese entries that differ only in hidden DB fields (servings, preparationTime, ingredients, directions). The agent can only perceive title + description via the accessibility tree, so 5 visually identical cards looked like 5 duplicates. The scorer's `validate_rows_removal_integrity` requires that exactly 1 of the 2 target rows is deleted and ALL other rows — including the 3 visually-identical noise rows — remain untouched. Deleting 4 total (3 noise rows + 1 legitimate duplicate) causes the integrity check to fail because 3 noise rows are missing from the "after" state.

## Classification

ActionFailure

> Specific failure sub-type: **Over-deletion of visually-identical noise rows** — the agent cannot distinguish target duplicate rows from noise rows that share the same title+description but differ in non-visible DB fields (servings, ingredients, directions).

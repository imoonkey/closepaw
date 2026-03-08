# RecipeAddMultipleRecipesFromMarkor — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 29/50 | **Reason**: GoalAchieved (false) | **Duration**: 137s

**Goal**: Add the recipes from recipes.txt in Markor to the Broccoli recipe app.

## Root Cause

**Category**: ReasoningError + FalseCompletion

The agent overwrite-edited recipe 1 (Thai Peanut Noodle Salad) in-place to become recipe 2 (Garlic Butter Shrimp) instead of creating a new entry. Result: only 2 recipes exist in Broccoli (Garlic Butter Shrimp, BBQ Chicken Quesadillas) — Thai Peanut Noodle Salad was destroyed. The agent then claimed success and verified only 2 of 3 recipes.

## Multi-Action Assessment (R8 Key Question)

Multi-action batching **worked mechanically** — turns 8, 16, and 21 each batched 5 type actions to fill form fields in a single turn. This cut per-recipe field-filling from ~6 turns to ~2 turns, dropping total turns from 50 (R7) to 29. However, the turn savings were **negated by a reasoning error** that destroyed a recipe.

| Metric | R7 | R8 |
|--------|----|----|
| Turns used | 50/50 | 29/50 |
| Recipes completed | 2 (intact) | 2 (1 overwritten, 1 new) |
| Multi-action turns | 0 | 3 (turns 8, 16, 21) |
| Score | 0.0 | 0.0 |

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `write_todos` | N | G | G | G | Plan task |
| 2 | `open_app(Markor)` | Y | G | G | G | — |
| 3 | `click(recipes.txt)` | Y | G | G | G | Opened note |
| 4 | `scratchpad(write)` | N | G | G | G | Stored all 3 recipes |
| 5 | `open_app(Broccoli)` | Y | G | G | G | — |
| 6 | `click(FAB)` | Y | G | G | G | New recipe form |
| 7 | `type(title: Thai Peanut...)` | Y | G | G | G | — |
| 8 | `type x5 (desc, servings, time, ingredients, directions)` | Y | G | G | G | Multi-action batch for recipe 1 |
| 9 | `click(save)` | Y | G | G | G | Recipe 1 saved |
| 10 | `click(element 22)` | Y | B | B | G | **Entered Cook mode instead of navigating to create new recipe** |
| 11 | `system_button(back)` | Y | G | G | G | Recovery from Cook mode |
| 12 | `click(more options)` | Y | B | B | G | **Chose to edit existing recipe instead of creating new** |
| 13 | `click(Edit)` | Y | B | B | G | **Entered edit mode for recipe 1** |
| 14 | `type(Garlic Butter Shrimp, clear=true)` | Y | B | G | G | **Overwrote recipe 1 title** |
| 15 | `scratchpad(read)` | N | G | G | G | Re-read recipes |
| 16 | `type x5 (all fields, clear=true)` | Y | B | G | G | **Overwrote all recipe 1 fields with recipe 2 data** |
| 17 | `click(save)` | Y | G | G | G | Saved — but this replaced recipe 1, not created recipe 2 |
| 18 | `click(Navigate up)` | Y | G | G | G | Back to list |
| 19 | `click(FAB)` | Y | G | G | G | New recipe form for recipe 3 |
| 20 | `type(BBQ Chicken Quesadillas)` | Y | G | G | G | — |
| 21 | `type x5 (all fields)` | Y | G | G | G | Multi-action batch for recipe 3 |
| 22 | `click(save)` | Y | G | G | G | Recipe 3 saved |
| 23-27 | Navigate back, scroll, search to verify | Y | G | G | G | Verification |
| 28 | `write_todos(all completed)` | N | B | — | — | **Marked complete despite only 2 recipes** |
| 29 | `complete_task(success)` | N | B | — | — | **FalseCompletion — claims Thai Peanut still exists** |

### Key Turns

**Turns 10-14** — After saving recipe 1, the agent stumbled trying to create recipe 2. It clicked "Cook" (turn 10), backed out (turn 11), then chose "More options > Edit" (turns 12-13) on the existing recipe rather than navigating back to the list and clicking the FAB. In turn 14 it typed "Garlic Butter Shrimp" with `clear=true`, overwriting the saved title. This was a reasoning error: the agent thought "reusing the form" was equivalent to creating a new entry, but editing an existing recipe replaces it.

**Turn 29** — The agent claimed success mentioning "Thai Peanut Noodle Salad" exists, but it had been overwritten in turns 14-16. The verification (turns 23-27) searched for "BBQ Chicken Quesadillas" and scrolled the list, but never searched for "Thai Peanut Noodle Salad" — it would not have found it.

## What Worked

- Multi-action batching reduced field-filling from ~6 turns to ~2 turns per recipe (turns 8, 16, 21 each had 5 actions).
- Scratchpad strategy: stored recipe data before switching apps, eliminating app-switching overhead.
- Total turn count (29) is well within budget — the agent had 21 turns to spare, proving multi-action solves the turn exhaustion problem from R7.
- Recipe 3 (BBQ Chicken Quesadillas) was created cleanly via FAB on the correct flow (turns 19-22).

## What Didn't Work

- **Destructive edit instead of create**: The agent edited recipe 1 in-place for recipe 2, destroying recipe 1. This is the sole reason for failure.
- **No recovery awareness**: After editing recipe 1, the agent never realized it was overwriting rather than creating. The `clear=true` flag was a clue — clearing fields implies editing, not creating.
- **Incomplete verification**: The agent searched for "BBQ Chicken Quesadillas" but never verified "Thai Peanut Noodle Salad" still existed.

## Recommendations

- **Prompt fix — recipe creation flow**: Add guidance that creating a new recipe always requires navigating to the list and using the FAB/add button. Editing an existing recipe replaces it; it does not create a duplicate.
- **Prompt fix — verification completeness**: When adding N items, the agent should verify all N exist, not just the last one added.
- **Multi-action verdict**: The batching works and solves the R7 turn exhaustion. The failure here is orthogonal — a reasoning error about edit vs. create. With correct navigation, this task would complete in ~22 turns (well under budget).

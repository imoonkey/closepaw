# RecipeDeleteDuplicateRecipes2 — R41 (20260312_173354) — FAIL

**Score**: 0.0 | **Turns**: 19/50 | **Stop**: GoalAchieved | **Model**: gpt-5.4

## Flow Summary

| Turns | Phase |
|-------|-------|
| 1 | open_app Broccoli |
| 2-3 | write_todos + scratchpad: record visible recipes from first screen |
| 4 | scroll down, scratchpad: record mid-list recipes |
| 5 | scroll down again (list ends — same content, no new recipes) |
| 6 | write_todos: conclude only one duplicate group (Caprese "An ideal recipe...") |
| 7 | scroll up to return to Caprese cards |
| 8-11 | open Caprese duplicate -> overflow menu -> Delete -> confirm DELETE (1st deletion) |
| 12-15 | open another Caprese duplicate -> overflow menu -> Delete -> confirm DELETE (2nd deletion) |
| 16 | scroll down to verify — concludes "single exact duplicate Caprese card still visible" |
| 17 | write_todos: mark all complete after bottom-half rescan |
| 18 | remember_experience |
| 19 | complete_task(success) |

## Root Cause: Under-deletion (known issue pattern)

The agent deleted only **2** of the 5 identical "Caprese Salad Skewers | An ideal recipe..." cards. After the two deletions, the turn-16 screen still shows at least **2** cards with that exact title+description (indices 14 and 21), plus the distinct "A delicious and healthy choice..." variant. The agent misread the post-deletion state and declared success prematurely.

**Initial state** (reconstructed from a11y trees):
- "Caprese Salad Skewers | An ideal recipe for experimenting..." x5
- "Caprese Salad Skewers | A delicious and healthy choice..." x1
- "Pan-Seared Salmon with Quinoa" x3 (all different descriptions — not duplicates)
- Raspberry Almond Smoothie x1, Tomato Basil Bruschetta x1, Zucchini Noodles with Pesto x1

**Required**: delete 4 of the 5 "An ideal recipe..." Capreses, keeping 1. (But only 1 is the true DB duplicate — the other noise rows share title+desc but differ in hidden DB fields like servings/ingredients.)

**Actual**: deleted 2, left 3 remaining.

## Why the Agent Stopped Early

1. **Counting error during scan**: the scratchpad noted "Caprese An ideal = 5 visible" on the first screen but never formalized how many deletions were needed.
2. **No deletion counter**: the agent did not track how many it had deleted vs. how many remained. After 2 deletions it scrolled and concluded the job was done.
3. **Misread of post-deletion screen**: turn 16 still shows 2+ "An ideal recipe..." cards, but the agent's thought says "A single exact duplicate Caprese card is still visible here, so deletion appears complete." This is a perception error — it saw 2 remaining copies but interpreted them as the one allowed survivor.
4. **No prescriptive algorithm**: in R28 (the only pass), a Broccoli skill guided the agent to open each same-titled recipe, compare ALL visible fields, and identify the true duplicate pair. That prescriptive algorithm was removed in subsequent prompt changes. Without it, the agent falls back to title+description matching, which cannot distinguish noise rows from real duplicates.

## Historical Context

This is the same failure mode seen in R36-R39: over- or under-deletion due to inability to distinguish noise rows (same title+description, different hidden DB fields). The task was already in `cannot_handle_group.txt` after R38. R41 confirms the regression persists.

| Round | Score | Failure Mode |
|-------|-------|-------------|
| R28 | 1.0 | Only pass — prescriptive skill guided field-by-field comparison |
| R36 | 0.0 | Over-deleted (4 instead of 1) |
| R37 | 0.0 | Over-deleted |
| R38 | 0.0 | Over-deleted → accepted regression |
| R39 | 0.0 | Under/over-deleted |
| R41 | 0.0 | Under-deleted (2 of 4 needed) |

## Verdict

Confirmed known issue. Without the prescriptive field-comparison algorithm (removed post-R28), the agent cannot reliably solve this task. The noise rows are designed as a trap — identical title+description but different hidden fields — and pure visual matching will always produce wrong deletion counts. No new regression; status remains `cannot_handle`.

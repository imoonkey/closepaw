# RecipeDeleteDuplicateRecipes3 — R41 (20260312_173354) — FAIL

**Score**: 0.0 | **Turns**: 59/80 | **Stop**: GoalAchieved | **Model**: gpt-5.4

## Flow Summary

| Turns | Phase |
|-------|-------|
| 1 | open_app Broccoli |
| 2 | write_todos: plan scan, delete, verify |
| 3 | scratchpad: record visible recipes (Avocado x2 same-desc, BSS noted) + scroll |
| 4 | scratchpad: update — BSS "seen five times", Avocado Toast duplicate noted |
| 5-24 | BSS deletion loop: 10 open-menu-delete-confirm cycles, but a11y shows count stuck at 5 through seq 405 (only 2 actually registered by seq 416) |
| 25-31 | Search-based BSS cleanup attempt — filter by title, delete from search results |
| 32-44 | Back to list, continue BSS deletions — count drops 4->3->2->1 over turns 33-44 |
| 45 | Scroll up to rescan top of list |
| 46-49 | Delete one Avocado Toast duplicate (2->1 of "A quick and easy meal" variant) |
| 50-56 | Bottom-half rescan — spot and delete Pan-Seared Salmon duplicate |
| 57 | write_todos: mark all complete |
| 58 | remember_experience |
| 59 | complete_task(success) |

## Root Cause: Over-deletion of BSS noise pool (known issue)

The dataset contains a noise pool of BSS entries that share the same title+description ("Butternut Squash Soup | A quick and easy meal, perfect for busy weekdays.") but differ in hidden DB fields (ingredients, servings, etc.). The agent treated all 5 visible BSS cards as exact duplicates and deleted 4, keeping 1. However, only a subset were true database-level duplicates; the rest were unique recipes that happened to share a title+description.

**Initial state** (reconstructed from a11y trees):
- "Avocado Toast with Egg | A quick and easy meal..." x2 (1 true duplicate)
- "Avocado Toast with Egg | An ideal recipe..." x1
- "BBQ Chicken Quesadillas" x2 variants (different descriptions — not duplicates)
- "Butternut Squash Soup | A quick and easy meal..." x5 (noise pool — only some are true duplicates)
- "Pan-Seared Salmon with Quinoa | A quick and easy meal..." x2 (1 true duplicate)
- ~15 other unique recipes

**Agent's deletions**: 4 BSS + 1 Avocado Toast + 1 Pan-Seared Salmon = 6 total.

**Likely scoring failure**: the BSS over-deletion destroyed non-duplicate recipes that shared visual identity but differed in hidden fields.

## Ghost-Delete Problem

A secondary issue compounded the BSS over-deletion: many deletion attempts silently failed. The agent performed ~10 delete-confirm cycles on BSS in turns 5-24, but the a11y tree count stayed at 5 through seq 405. Only after turn 25 (search-based approach) and turns 33-44 (continued list-level attempts) did the count actually drop. This suggests Broccoli's delete confirmation dialog sometimes does not complete the deletion, or the a11y tree refresh races with the deletion. The agent's scratchpad never tracked a deletion counter, so it couldn't detect the ghost deletes.

## Why This Keeps Failing

1. **Noise pool trap**: same title+description, different hidden DB fields. Visual/a11y-based matching cannot distinguish real duplicates from noise rows.
2. **No field-by-field comparison**: the prescriptive algorithm from R32 (which compared all 7 fields) is no longer active. The agent defaults to title+description matching.
3. **Ghost deletes waste turns**: unreliable deletion confirmation means the agent burns 20+ turns on BSS alone, leaving less budget for careful verification.

## Historical Context

| Round | Score | Failure Mode |
|-------|-------|-------------|
| R32 | 1.0 | Only pass — scratchpad tracking + 80 turn budget + compare all 7 fields |
| R36 | 0.0 | Over-deleted (11 deletions, 9 BSS noise) |
| R37 | 0.0 | Over-deleted |
| R38 | 0.0 | Over-deleted -> accepted regression |
| R39 | 0.0 | GoalAchieved, scorer 0.0 (same pattern) |
| R41 | 0.0 | Over-deleted BSS noise pool (4 of 5 removed, some were unique) |

## Verdict

Confirmed known issue. Same noise-pool over-deletion pattern as R36-R39. The agent correctly identified and deleted Avocado Toast and Pan-Seared Salmon duplicates, but over-deleted BSS entries that were not true duplicates. Without prescriptive field-comparison guidance, the task remains unsolvable via visual matching. Status: `cannot_handle`.

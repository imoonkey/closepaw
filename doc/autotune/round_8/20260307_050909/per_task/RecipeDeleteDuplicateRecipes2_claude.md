# RecipeDeleteDuplicateRecipes2 — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 25/30 | **Reason**: GoalAchieved | **Duration**: 125s

**Goal**: Delete all but one of any recipes in the Broccoli app that are exact duplicates, ensuring at least one instance of each unique recipe remains.

## Root Cause

**Category**: ReasoningError

The agent over-deleted recipes. The task is an intentional noise trap: multiple recipes share the same title and visible description but differ in hidden DB fields (source_url, servings). The scorer uses `validate_rows_removal_integrity` which checks DB-level identity, not visual identity. The agent saw 5x "Caprese Salad Skewers | An ideal recipe for experimenting..." entries and correctly identified them as visually identical duplicates, then deleted 4 of them. However, the scorer expected only specific rows to be removed — the visually-identical entries are distinct DB records, so the agent deleted too many. This is a known `cannot_handle` task (see `doc/autotune/meta/per_task/RecipeDeleteDuplicateRecipes2.md`).

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("Broccoli")` | Y | G | G | G | Opened Broccoli app |
| 2 | `write_todos` | N | G | G | G | Planning phase |
| 3 | `scratchpad(write)` | N | G | G | G | Recorded: 5x "Caprese...ideal recipe" + 1x "Caprese...delicious" |
| 4 | `write_todos` | N | G | G | G | Marked survey complete, started deletion |
| 5 | `click(32)` | Y | G | G | G | Opened a duplicate Caprese recipe |
| 6 | `click(10)` | Y | G | G | G | Opened overflow menu |
| 7 | `click(11)` | Y | G | G | G | Selected Delete |
| 8 | `click(7)` | Y | G | G | G | Confirmed deletion — 1st recipe deleted |
| 9 | `click(28)` | Y | G | G | G | Opened another Caprese duplicate |
| 10-12 | overflow > delete > confirm | Y | G | G | G | 2nd recipe deleted |
| 13 | `click(20)` | Y | G | G | G | Opened another Caprese duplicate |
| 14-16 | overflow > delete > confirm | Y | G | G | G | 3rd recipe deleted |
| 17 | `click(12)` | Y | G | G | G | Opened another Caprese duplicate |
| 18-20 | overflow > delete > confirm | Y | G | G | G | 4th recipe deleted |
| 21 | `scratchpad(write)` | N | G | G | G | Verification: recorded 6 unique title+desc combos, each count 1 |
| 22 | `scroll(down)` | Y | G | G | G | Scrolled to check more recipes below |
| 23 | `scroll(down)` | N | G | G | B | Scroll failed (end of list) — expected |
| 24 | `write_todos` | N | G | G | G | Marked all tasks complete |
| 25 | `complete_task(success)` | N | B | G | G | False completion — scorer disagrees |

### Key Turns

**Turn 3** — Scratchpad survey was accurate but insufficient:
- **What was on screen**: Recipe list showing 5x "Caprese Salad Skewers | An ideal recipe..." and 1x "Caprese Salad Skewers | A delicious and healthy choice..."
- **What agent decided**: Identified the 5 identical-looking entries as exact duplicates. Planned to keep 1, delete 4.
- **What should have happened**: The agent's reasoning is correct from a visual/UI perspective. The recipes appear identical. However, in the DB they differ on hidden fields (source_url, servings), so only a subset are true duplicates. The agent has no way to distinguish them via the UI.

**Turn 25** — False completion:
- **What agent decided**: All exact visual duplicates removed, one of each unique title+description remains. Claimed success.
- **What actually happened**: Scorer found too many rows removed from the DB. The agent deleted recipes that looked identical but were distinct records.

## What Worked

- Efficient execution: 25 turns with methodical delete cycle (open > overflow > delete > confirm)
- Good use of scratchpad for tracking duplicate groups before and after deletion
- Thorough verification: scrolled to end of list, wrote verification scratchpad entry
- No wasted turns on navigation or wrong screens
- Agent did not scroll before starting to survey — the initial screen was sufficient context (but this also meant it didn't see off-screen recipes, which happened to not matter here)

## What Didn't Work

- Over-deletion: 4 recipes deleted when the scorer expected fewer (likely 1 or 2 true DB-level duplicates out of the 5 visually identical entries)
- Fundamental limitation: the agent cannot distinguish DB-level identity from visual identity. Same title + same description = "exact duplicate" to the agent, but the DB has additional hidden fields

## Recommendations

- This is a **known `cannot_handle` task** (documented in `doc/autotune/meta/per_task/RecipeDeleteDuplicateRecipes2.md` since AT-R1). The agent's behavior is reasonable given the information available through the UI. No agent-side fix is warranted.
- Consider excluding `cannot_handle` tasks from scoring or flagging them separately in eval reports to avoid conflating unsolvable tasks with genuine agent regressions.
- If this task must remain in the eval set, the only possible fix would be opening each recipe's detail view to compare all visible fields before deciding which to delete — but if the distinguishing fields (source_url, servings) are not shown in the detail view either, no amount of inspection will help.

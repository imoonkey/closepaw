# RecipeDeleteDuplicateRecipes2

**Status: FIXED R28** — Broccoli field comparison skill guided agent to open each same-titled recipe, compare ALL visible fields, and identify true duplicate pair.


## R38 (20260312_000547) — gpt-5.4 — FAIL → ACCEPTED REGRESSION
- Score: 0.0 | FalseCompletion. 3rd consecutive failure. Agent loses count during high-multiplicity duplicate deletion. Accepted regression.

## R37 (20260311_232252) — gpt-5.4 — FAIL
- Score: 0.0 | GoalAchieved but scorer 0.0. Same pattern — duplicate count tracking failure during list reindexing.

## R36 (20260311_215019) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 22 | GoalAchieved but scorer 0.0. Agent compared title+desc only, missed field-level differences

## R28 (20260309_210911) — gpt-5.4 — FIXED
- Score: 1.0 | Turns: 41/50 | Success — Broccoli skill field comparison worked. Opened each same-titled recipe, compared all visible fields, found true duplicate.

## R27 (20260309_204943) — gpt-5.4
- Score: 0.0 | Turns: 30 | TurnExhaustion — 1st unpark attempt. Identified "Caprese" recipes, started comparing but ran out of turns at 30. Fix: increase max_turns to 50.

## R8 (GPT-5.4, 20260307)
Score: 0.0 | Turns: 25 | ReasoningError — deleted wrong recipes.

**Status: cannot_handle** — Intentional same-description noise trap. Agent over-deleted (4 instead of 1) because visually identical entries differ only in hidden DB fields (source_url, servings). Moved AT-R1.

## AT-R1 (20260303_232753) — FAIL → cannot_handle
- Score: 0.0
- Turns: 45/50
- Over-delete: DB had 12 recipes (2 exact duplicates + 10 noise including 3 same-title-same-description variants with different hidden fields). Agent deleted 4 instead of 1. Scorer requires exactly 1 target removed + all others intact (`validate_rows_removal_integrity`).
- See `round_1/20260303_232753/per_task/RecipeDeleteDuplicateRecipes2_claude.md` for details.

## R3 (20260301_012237) — FAIL
- Score: 0.0
- Root cause: Index shift + turn exhaustion

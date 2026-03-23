## R57 (20260317_194418/20260317_205840) — gpt-5.4 — FAIL
- Score: 0.0 | Context: app skill rewrite validation sweep (-65.6% skill tokens)
- Persistent failure — over-deletion of noise rows with same title+desc but different hidden DB fields.

# RecipeDeleteDuplicateRecipes2

**Status: FIXED R28** — Broccoli field comparison skill guided agent to open each same-titled recipe, compare ALL visible fields, and identify true duplicate pair.

### R44 (20260313_095409)
- Score: 0.0 | Turns: 50 | Full-field comparison followed but ran out of turns (50 max)

## R41 (20260312_173354) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 19/50 | GoalAchieved — under-deleted (2 of 4 needed). Deleted 2 "An ideal recipe..." Capreses, left 3 remaining. Agent misread post-deletion screen as complete. Same known issue: no prescriptive field-comparison algorithm, cannot distinguish noise rows from true duplicates.

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 27 | Failed — GoalAchieved. Answer: Done — I deleted the extra exact duplicate recipes and ve... (unchanged)

## R38 (20260312_000547) — gpt-5.4 — FAIL → ACCEPTED REGRESSION
- Score: 0.0 | Over-deletion of visually-identical noise rows. 3rd consecutive failure. Accepted regression.

## R37 (20260311_232252) — gpt-5.4 — FAIL
- Score: 0.0 | GoalAchieved but scorer 0.0. Same over-deletion pattern — noise rows share title+desc but differ in hidden DB fields.

## R36 (20260311_215019) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 22 | Over-deleted: 4 deletions (3 noise + 1 target) when 1 needed. Noise rows share title+desc but differ in hidden DB fields (servings, ingredients).

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

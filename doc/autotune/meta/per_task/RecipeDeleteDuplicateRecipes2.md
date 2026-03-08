# RecipeDeleteDuplicateRecipes2

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

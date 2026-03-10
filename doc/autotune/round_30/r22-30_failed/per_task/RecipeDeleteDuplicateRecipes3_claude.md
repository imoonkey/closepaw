# RecipeDeleteDuplicateRecipes3 — Failure Analysis

## Scripted Success Criteria

From `DeleteMultipleRows.is_successful` (inherited via `RecipeDeleteDuplicateRecipes`):

1. Query the SQLite DB (`broccoli`) after the agent finishes.
2. Call `validate_deletion_integrity(before, after)`.
3. `RecipeDeleteDuplicateRecipes.validate_deletion_integrity` checks: exactly ONE of the two duplicate `recipeId`s must be removed, AND every other row must remain unchanged. No new rows allowed.
4. Returns `1.0` only if one duplicate is deleted and nothing else is touched.

Key: "duplicate" means two rows identical on ALL fields (title, description, servings, preparationTime, source, ingredients, directions, favorite) — they differ only in the auto-incremented `recipeId`.

## Task Setup

`RecipeDeleteDuplicateRecipes3.generate_random_params`:

1. Generate 22 unique random recipes, filtering OUT "Avocado Toast with Egg".
2. Pop one as `target`.
3. Add 3 "Avocado Toast with Egg" noise recipes (red herrings — same title, different fields).
4. Fill remaining noise slots (up to `n_rows_noise=30`) with recipes sharing `target.title` AND `target.description` but differing in other fields (servings, preparationTime, ingredients, directions). These are near-duplicates designed to confuse the agent.
5. Insert `ROW_OBJECTS = [target, target]` — two perfectly identical rows.

Result: 32 total recipes. Among the group sharing the target's title+description, most have different servings/time/ingredients/directions. Exactly 2 are perfect clones. The agent must find and delete one of them.

## Agent's Last Attempt (R27, run 20260309_204943)

- **Model**: gpt-5.4, **Turns used**: 49/60, **Duration**: 238s
- **Agent conclusion**: "No exact duplicates were found, so no recipes were deleted"
- **Scripted score**: 0.0 (failure — the 2 exact duplicates still exist in DB)

### Agent strategy

1. Opened Broccoli app, scrolled through full recipe list recording title+description pairs.
2. Identified repeated-title groups: Butternut Squash Soup (~5+ entries), Avocado Toast with Egg (3 entries), BBQ Chicken Quesadillas (2), Pan-Seared Salmon with Quinoa (2).
3. For each repeated-title group, opened individual recipe detail views and recorded a 4-field signature: `title | description | servings | preparationTime`.
4. Found all checked entries differed on at least one of these 4 fields.
5. Concluded no exact duplicates exist. Called `complete_task` with `GoalAchieved`.

### Scratchpad evidence (signatures recorded)

| Group | Entry | Signature |
|-------|-------|-----------|
| Butternut Squash Soup | #1 | 6 servings, 4 hrs |
| Butternut Squash Soup | #2 | 3-4 servings, 1 hrs |
| Butternut Squash Soup | #3 | 8 servings, 4 hrs |
| Butternut Squash Soup | #4 | 2 servings, 45 mins |
| Butternut Squash Soup | #5 | 8 servings, 2 hrs |
| Avocado Toast | #1 | 6 servings, 4 hrs |
| Avocado Toast | #2 | 3-4 servings, 1 hrs |
| BBQ Chicken | #1 | 1 serving, 20 mins |
| BBQ Chicken | #2 | 3-4 servings, 10 mins |
| Pan-Seared Salmon | #1 | 6 servings, 1 hrs |
| Pan-Seared Salmon | #2 | 1 serving, 1 hrs |

## Gap Analysis

### Root cause: incomplete enumeration of same-title entries

The task inserts 32 recipes total. The target group (likely Butternut Squash Soup given the high count) contains 2 exact duplicates PLUS multiple near-duplicates with different servings/time. The agent opened 5 BSS entries in detail but the group contains more (2 exact duplicates + multiple noise entries sharing the title+description = 7+ entries). The agent did not open ALL entries in the target group — it likely stopped after finding that the ones it checked all differed, and concluded no duplicates exist.

### Contributing factor: shallow field comparison

The agent only compared 4 fields (title, description, servings, preparationTime) per recipe. It did NOT include `ingredients` or `directions` in its signatures. While this didn't directly cause the failure (the exact duplicates match on ALL fields), it means the agent's comparison strategy was incomplete and could also fail in edge cases where two entries share the same 4 fields but differ on ingredients/directions.

### Contributing factor: false-negative conclusion

The agent declared `GoalAchieved` despite not having verified ALL entries in every repeated-title group. With 30+ recipes and limited turns, it ran out of budget to exhaustively compare every pair, but still declared success rather than acknowledging uncertainty.

## Verdict

**Fixable — but challenging within turn budget.**

The core issue is combinatorial: 32 recipes, many sharing the same title+description, requiring the agent to open each detail view and compare ALL fields exhaustively. At 49 turns, the agent already used most of its budget just checking a subset.

Potential fixes:
1. **App skill guidance**: Add a Broccoli skill instructing the agent to use a systematic grouping strategy — first list all titles, identify groups with 2+ entries sharing the same title+description, then exhaustively compare ALL entries within each group on every visible field (including ingredients and directions).
2. **Efficiency**: Instead of opening every recipe individually, the agent could use scroll-and-compare on the list view first (title+description are visible there) to narrow candidates, then only open pairs that match on list-visible fields.
3. **Shell shortcut**: If the agent has shell access and the DB is readable, it could query SQLite directly to find exact duplicates — bypassing the UI entirely. However, Broccoli's DB is in its private data directory, likely inaccessible without root.

The task is structurally hard (complexity 3.4) by design — high noise, scrolling required, near-duplicates as distractors. A skill-guided systematic approach is the most promising path.

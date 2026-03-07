# RecipeAddMultipleRecipesFromMarkor

## R6 (run 20260307_003831, gpt-5.4)
- **Score**: 0.0 | **Turns**: 22/50
- **Behavior**: FalseCompletion. Added 1/3 recipes, self-terminated citing hallucinated loop warning with 28 turns remaining.
- **Delta vs R1**: No hint text contamination, but new premature termination — agent fabricated a loop-detection signal.

## AT-R1 (20260303_232753) — FAIL
- Score: 0.0
- Turns: 35/50
- FalseCompletion: Agent completed all 3 recipes but every field suffered hint text contamination (e.g., "TitleThai Peanut Noodle Salad"). Scorer checks exact values → 0.0. Not a turn budget issue.
- See `round_1/20260303_232753/per_task/RecipeAddMultipleRecipesFromMarkor_claude.md` for details.

## R3 (20260301_012237) — FAIL
- Score: 0.0
- Root cause: Turn exhaustion (almost done)

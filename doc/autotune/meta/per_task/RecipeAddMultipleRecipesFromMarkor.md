## R57 (20260317_194418/20260317_205840) — gpt-5.4 — PASS
- Score: 1.0 | Context: app skill rewrite validation sweep (-65.6% skill tokens)
- Clean pass, rewritten skill injected correctly.


## R40 (20260312_122824) — gpt-5.4 — PASS
- Score: 1.0 | Turns: 30 | Clean pass in 30 turns. (unchanged)

## R39 (20260312_094821) — gpt-5.4 — PASS
- Score: 1.0 | Turns: 22 | Clean pass in 22 turns. (unchanged)

## R20 (20260309_150119) — gpt-5.4
- Score: 1.0, Turns: 27
- PASS. Broccoli skill worked.

## R19 (20260309_143418) — gpt-5.4
- Score: 0.0, Turns: 32
- Reasoning: Edit-as-create — same pattern, overwrote recipe 1

# RecipeAddMultipleRecipesFromMarkor


## R36 (20260311_215019) — gpt-5.4 — PASS
- Score: 1.0 | Turns: 22 | Success

## R8 (GPT-5.4, 20260307)
Score: 0.0 | Turns: 29 | Multi-action batching worked (5-action turns). Turn count dropped 50->29. But reasoning error: overwrote recipe 1 instead of creating recipe 2.

## R7 (run 20260307_025501, qwen3.5)
- **Score**: 0.0 | **Turns**: 50/50 | MaxTurnsReached
- **Behavior**: TurnExhaustion. Completed 2/3 recipes (Thai Peanut Noodle Salad, Garlic Butter Shrimp saved successfully). 3rd recipe (BBQ Chicken Quesadillas) was mid-fill at turn 50 — needed ~5 more turns for ingredients, directions, save.
- **Delta vs R6**: Major behavioral improvement. Loop detection warnings removed; agent used all 50 turns instead of self-terminating at 22. Went from 1 partial recipe to 2 complete + 1 partial. Score unchanged (0.0) because scorer requires all 3.
- **Key issue**: 2 turns wasted on Cook button misclick (turn 22). Per-recipe cost (~15 turns) makes 3 recipes in 50 turns extremely tight.

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
- R58 (20260323): score=1.0, turns=32, reason=GoalAchieved

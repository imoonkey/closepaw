# RecipeAddMultipleRecipesFromImage

## R6 (run 20260307_003831, gpt-5.4)
- **Score**: 0.0 | **Turns**: 32/50
- **Behavior**: NavigationFailure. Extracted 3 recipes from image, saved 1 to Broccoli, got trapped in detail view trying to add recipe 2.
- **Delta vs R1**: No hint text contamination (gpt-5.4 model switch), but new navigation trap after first save.

## AT-R1 (20260303_232753) — FAIL
- Score: 0.0
- Turns: 35/50
- InfraError: LLM provider SSE crash at turn 35. Agent had completed 2/3 recipes. Also suffered hint text contamination in description fields. Retry infra exists (CloudStreamRetryRunner, MAX_RETRIES=5) but SSE error likely classified as non-retryable by OpenAIErrorClassifier.
- See `round_1/20260303_232753/per_task/RecipeAddMultipleRecipesFromImage_claude.md` for details.

## R3 (20260301_012237) — FAIL
- Score: 0.0
- Root cause: Turn exhaustion (2/3 done)

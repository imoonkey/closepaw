# RecipeDeleteDuplicateRecipes3

## R4 (AT-R4, run 20260305_151648)
- **Score**: 0.0 | **Turns**: 50/50 | **Agent**: qwen3.5
- **Behavior**: TurnExhaustion. write_todos over-use (9 calls = 18% budget waste). 8 deletions vs ~4 in R3, still insufficient for 10+ duplicates.
- **R4 fix result**: max_turns=50 + repetitive-task guidance applied. Marginal improvement only; recommend park.

## AT-R3 (20260304_152742) - FAIL
- Score: 0.0
- Turns: 30/30
- Root cause: TurnExhaustion. Agent correctly identified duplicates but ran out of turns mid-deletion. Many duplicates to process.
- R4 fix: Set max_turns=50. Park if still failing.

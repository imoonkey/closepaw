# RecipeDeleteDuplicateRecipes3

## AT-R3 (20260304_152742) - FAIL
- Score: 0.0
- Turns: 30/30
- Root cause: TurnExhaustion. Agent correctly identified duplicates but ran out of turns mid-deletion. Many duplicates to process.
- R4 fix: Set max_turns=50. Park if still failing.

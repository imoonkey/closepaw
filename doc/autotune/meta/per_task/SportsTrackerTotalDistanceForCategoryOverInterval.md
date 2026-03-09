# SportsTrackerTotalDistanceForCategoryOverInterval

## R16 (gpt-5.4, 20260309_125810)
- Score: 0.0 | Turns: 24/50 | ReasoningError — date mapping. Confused "Yesterday" (Oct 14) with target range (Oct 9-10). Never checked Tuesday tracks. Answer: 4828 (wrong).

## R4 (AT-R4, run 20260305_151648)
- **Score**: 0.0 | **Turns**: 30/30 | **Agent**: qwen3.5
- **Behavior**: ReasoningError. REGRESSED from 1.0 in R3. Agent checked only 2/5 tracks then pivoted to shell (5 failed find commands). Natural variance + premature shell pivot.
- **R4 fix result**: Shell restriction not the cause (commands were file ops). Regression due to nondeterminism.

## AT-R3 (20260304_152742) - PASS (fragile)
- Score: 1.0
- Turns: 28/30 (near turn limit — fragile pass)
- Root cause: Success but turn-inefficient. Repeated recounting and exploratory loops.
- R4 fix: Shell restriction + repetitive-task guidance should reduce turn waste.

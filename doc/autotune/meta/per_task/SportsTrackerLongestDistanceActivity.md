# SportsTrackerLongestDistanceActivity

## R9 (GPT-5.4, 20260307, full baseline) — FAIL
- Score: 0.0 | Turns: 30 | TurnExhaustion — checked 4/7 tracks, didn't reach mountain biking one. Same pattern as R4.

## R4 (AT-R4, run 20260305_151648)
- **Score**: 1.0 | **Turns**: 30/30 | **Agent**: qwen3.5
- **Behavior**: Success (fragile). Same as R3 -- still at turn limit. Agent checked 4 of 7 tracks.
- **R4 fix result**: Repetitive-task guidance had no measurable effect. Still fragile pass at turn limit.

## AT-R3 (20260304_152742) - PASS (fragile)
- Score: 1.0
- Turns: 30/30 (at turn limit — fragile pass)
- Root cause: Success but turn-inefficient. Exploratory loops and shell detours wasted turns.
- R4 fix: Shell restriction + repetitive-task guidance should reduce turn waste.

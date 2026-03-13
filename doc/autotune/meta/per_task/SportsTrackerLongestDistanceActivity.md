# SportsTrackerLongestDistanceActivity

### R44 (20260313_095409)
- Score: 0.0 | Turns: 50 | Full 50 turns used, inexact type match or incomplete coverage

## R41 (20260312_173354) — gpt-5.4 — FAIL — WRONG_ANSWER / ActivityTypeMismatch
- Score: 0.0 | Turns: 16 | GoalAchieved. Answer: "18668" (wrong)
- Agent checked 2 of 8 tracks this week. Found "Scenic Cycling" with Edit activity type "biking" (11.60 mi) and equated "biking" with "mountain biking". These are distinct types in OpenTracks. The correct mountain biking track was likely among the unchecked tracks ("Road Bike Ride", etc.).
- Different failure mode than R40 (which answered "0"): R41 found a biking track but used inexact type matching. Efficient at 16 turns but wrong answer.
- Root fix needed: exact activity type matching + exhaustive candidate verification for extremum queries.

## R40 (20260312_122824) — gpt-5.4 — FAIL — FALSECOMPLETION / PERCEPTION
- Score: 0.0 | Turns: 25 | GoalAchieved. Answer: "0" (regressed from R39 1.0)
- Agent spent 25 turns and 28 tool calls but could not recognize the mountain biking activity type label in the a11y tree. Answered "0" instead of reporting uncertainty. The a11y tree label for mountain biking may use a variant the agent didn't recognize (e.g. "Cycling", "MTB", icon-only).
- Non-deterministic — R39 passed in 29 turns with the same prompt. Not caused by R40 tool description cuts.

## R39 (20260312_094821) — gpt-5.4 — PASS
- Score: 1.0 | Turns: 29 | Clean pass in 29 turns. (unchanged)

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

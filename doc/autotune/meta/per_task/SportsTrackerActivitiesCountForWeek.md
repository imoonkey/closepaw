# SportsTrackerActivitiesCountForWeek

## R41 (20260312_173354) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 5/50 | GoalAchieved. Answer: 0
- **Behavior**: FalseCompletion (name-based inference). Agent opened OpenTracks, dismissed 2 intro screens, saw 14 this-week tracks, scrolled once, found no track names containing "run"/"running" within the week (Mon Oct 9 -- Sun Oct 15), and answered "0" without checking a single track's Edit view.
- **Info-extraction guidance present**: System prompt line 42 ("scroll the full list, cross-check totals against scratchpad, verify date boundaries") was present but ignored. App Skill directive ("verify via Edit") also ignored.
- **Delta vs R39**: Same failure pattern (name-based, 5 turns). R39 found 1 run-like name outside the week and answered "1". R41 found 0 run-like names in-week and answered "0". Neither verified via Edit.
- **Structural issue**: Even with correct Edit-based approach, 14 tracks x ~4 turns = 56 turns exceeds 50-turn budget. Task remains structurally infeasible via UI. Should be in cannot_handle_group.txt.

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 5 | Failed — GoalAchieved. Answer: 1 (regressed from 1.0)

## R8 (GPT-5.4, 20260307)
Score: 0.0 | Turns: 4 | Much fewer turns (4 vs 30). FalseCompletion persists.

## R7 (run 20260307_025501, qwen3.5)
- **Score**: 0.0 | **Turns**: 30/30
- **Behavior**: TurnExhaustion + FalseCompletion. Agent correctly used edit flow (R6 skill fix landed), checking 5 of 14 tracks via More -> Edit. Found 1 running ("Trying and failing to keep up with John"). But 14 tracks x 4-5 turns/track = 56-70 turns needed vs 30 available. Answered "1" on final turn with 9 tracks unchecked.
- **Delta vs R6**: Major improvement in approach — agent no longer guesses from titles, correctly follows edit flow. But the fundamental turn budget problem makes this task unsolvable via UI-only inspection. Needs shell/DB query path or honest incomplete reporting.
- **Key insight**: This task is structurally infeasible with the edit-flow approach at 30 max turns. The only viable path is shell-based SQLite query to retrieve all activity types at once.

## R6 (run 20260307_003831, gpt-5.4)
- **Score**: 0.0 | **Turns**: 5/30
- **Behavior**: ReasoningError. Inferred activity type from title text, answered "1" (wrong). A11y tree doesn't expose sport type icon.
- **Delta vs R4**: Fewer turns (5 vs 30) but same core issue — agent guesses type instead of opening each track's detail view.

## R4 (AT-R4, run 20260305_151648)
- **Score**: 0.0 | **Turns**: 30/30 | **Agent**: qwen3.5
- **Behavior**: FalseCompletion. Infra fix (P3) worked -- session_stopped now fires. But agent answered "0" after checking only 2 of 14 tracks. Activity type requires 4 turns per track via Edit workflow.
- **R4 fix result**: Timeout observability applied. Infra fixed, cognition issue remains.

## AT-R3 (20260304_152742) - FAIL
- Score: 0.0 (timeout; trace shows ~25 turns)
- Turns: 0/30 (bridge timeout — no session_stopped event)
- Root cause: InfraError + TurnExhaustion. Agent ran many actions checking each track's type but session didn't terminate cleanly.
- R4 fix: Timeout finalization observability (session_stopped instrumentation + run summary flush).

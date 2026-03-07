# SportsTrackerActivitiesCountForWeek

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

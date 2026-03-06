# TasksCompletedTasksForDate

## R4 (AT-R4, run 20260305_151648)
- **Score**: 0.0 | **Turns**: 30/30 | **Agent**: qwen3.5
- **Behavior**: NavigationFailure. Same as R3 -- never found completed-tasks view. Wasted 30 turns exploring menus.
- **R4 fix result**: Date-label tip irrelevant (problem is navigation, not dates).

## AT-R3 (20260304_152742) - FAIL
- Score: 0.0
- Turns: 30/30
- Root cause: FalseCompletion. Looped trying to find completed tasks for a specific date, submitted unverified answer. Tasks app shows relative date labels; agent can't map absolute dates to UI.
- R4 fix: Generic date-label tip + QA scratchpad guardrail.

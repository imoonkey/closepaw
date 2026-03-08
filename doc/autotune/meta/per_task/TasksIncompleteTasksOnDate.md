# TasksIncompleteTasksOnDate

## R8 (GPT-5.4, 20260307)
Score: 0.0 | Turns: 11 | False completion. App skill injection status unclear.

## R6 (run 20260307_003831, gpt-5.4)
- **Score**: 0.0 | **Turns**: 2/30
- **Behavior**: ReasoningError. Listed 8 items without filtering by date cutoff or completion status.
- **Delta vs R4**: Same shallow scan (2-3 turns), lost R4's date-label filtering improvement — model switch lost learned tips.

## R4 (AT-R4, run 20260305_151648)
- **Score**: 0.0 | **Turns**: 3/30 | **Agent**: qwen3.5
- **Behavior**: ReasoningError. Date-label tip worked -- agent computed date map and filtered tasks. Improvement from R3 (zero filtering to correct filtering). But still 0.0: no scroll, answered from single viewport in 3 turns.
- **R4 fix result**: Date-label tip effective for date reasoning. Still fails due to shallow scan (no scroll).

## R7 (run 20260307_025501, qwen3.5)
- **Score**: 0.0 | **Turns**: 11/30
- **Behavior**: GoalAchieved (wrong answer). Agent engaged with app (11 turns, up from 2 in R6). Date-label resolution correct. Scrolled once but only recorded 6 of ~16+ tasks to scratchpad. Critically: clicked checkboxes to probe state, which toggled them (destructive exploration), wasting 6 turns on undo attempts. Final answer missed many tasks due on or before Oct 19.
- **Delta vs R6**: Major engagement improvement (2->11 turns, scrolling, scratchpad use). Date mapping now correct. New failure mode: destructive checkbox probing. Still incomplete scan.
- **Fix needed**: Add to org.tasks skill: "Do NOT click checkboxes -- this toggles them. Tasks in default 'My Tasks' view are all incomplete." Also need systematic scroll-and-record per viewport.

## AT-R3 (20260304_152742) - FAIL
- Score: 0.0
- Turns: 2/30
- Root cause: FalseCompletion. Answered immediately (2 turns) without any date filtering — included tasks past cutoff. No evidence gathering.
- R4 fix: Generic date-label tip + QA scratchpad guardrail.

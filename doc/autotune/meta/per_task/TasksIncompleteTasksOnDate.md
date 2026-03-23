## R57 (20260317_194418/20260317_205840) — gpt-5.4 — FAIL
- Score: 0.0 | Context: app skill rewrite validation sweep (-65.6% skill tokens)
- Persistent failure — a11y gap on completion checkboxes, can't distinguish complete from incomplete.

# TasksIncompleteTasksOnDate

### R44 (20260313_095409)
- Score: 0.0 | Turns: 14 | Skipped toggle protocol, listed all visible tasks as incomplete

## R41 (20260312_173354) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 8 | GoalAchieved (wrong answer). Agent scanned full list, date reasoning correct, but included completed tasks. A11y tree checkbox `ImageView` elements expose no `checked`/`checkable` state — agent cannot distinguish complete from incomplete. Same root cause as R39. Behavior improved (systematic scroll + scratchpad, only 8 turns), but platform gap remains blocking.

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 18 | Failed — GoalAchieved. Answer: Change Air Filter, Clean Out Fridge, Renew Driver's Licen... (regressed from 1.0)

## R17 (gpt-5.4, 20260309_132808)
- Score: 1.0 | Turns: 10 | GoalAchieved — NEW PASS. Date-label fix from R16→R17 worked. Agent correctly used standalone date label, ignored chip text.

## R16 (gpt-5.4, 20260309_125810)
- Score: 0.0 | Turns: 8 | ReasoningError — included "Back Up Computer Files" (due Sat/Oct 21) because "Tmrw" chip confused as due date instead of actual "Sat" label.

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
- R58 (20260323): score=0.0, turns=30, reason=GoalAchieved, ans="Attend training session"

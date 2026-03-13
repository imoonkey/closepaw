# TasksCompletedTasksForDate

## R41 (20260312_173354) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 12/30 | GoalAchieved (false completion). Answer: "Schedule Dentist Appointment, Water Plants"
- Agent correctly read "Show completed" checkbox as checked=true; planned scratchpad diff strategy (ON vs OFF list)
- Turn 6: text-match for "More options" failed. Turn 7: recovered via coords but landed in date picker instead of overflow menu
- Root cause: NavigationDerailment — accidental date picker activation broke the diff workflow. Agent guessed answer from date proximity
- A11y gap persists: per-task completion checkboxes (ImageView) expose no checked/checkable state
- Lifetime: 2/10 (20%). Remains in cannot_handle_group

## R39 (20260312_094821) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 6 | Failed — GoalAchieved. Answer: Water Plants (regressed from 1.0)

## R16 (gpt-5.4, 20260309_125810)
- Score: 1.0 | Turns: 11 | GoalAchieved — NEW PASS. gpt-5.4 correctly toggled "Show completed", identified completed tasks. 2 tool failures but recovered.

## R8 (GPT-5.4, 20260307)
Score: 0.0 | Turns: 8 | App skill now injected but a11y tree lacks `checked` property; agent still can't distinguish completed tasks. Accidental checkbox click persists.

## R7 (run 20260307_025501, qwen3.5)
- **Score**: 0.0 | **Turns**: 14/30
- **Behavior**: StateCorruption + SelfContradiction. Date mapping fixed (Tmrw = Oct 16 correct). Agent clicked checkboxes to "inspect" state, which toggled them and corrupted app state. Wrote "Organize files and folders = COMPLETED" to scratchpad, then answered "No completed tasks" -- self-contradiction.
- **Delta vs R6**: Date mapping improved (no longer picks wrong date). New failure mode: state mutation via checkbox clicks + contradicts own evidence.
- **Skill injection**: org.tasks app skill was **not injected** despite existing in assets. Skill guidance was unavailable.
- **Fix needed**: (1) Fix skill injection pipeline for org.tasks. (2) Add read-only rule for information tasks: never click toggles to inspect state. (3) Scratchpad consistency check before completion.

## R6 (run 20260307_003831, gpt-5.4)
- **Score**: 0.0 | **Turns**: 11/30
- **Behavior**: FalseCompletion. Misread relative date labels, picked task from wrong date.
- **Delta vs R4**: Reached completed-tasks view (navigation fixed), but date-label mapping still broken.

## R4 (AT-R4, run 20260305_151648)
- **Score**: 0.0 | **Turns**: 30/30 | **Agent**: qwen3.5
- **Behavior**: NavigationFailure. Same as R3 -- never found completed-tasks view. Wasted 30 turns exploring menus.
- **R4 fix result**: Date-label tip irrelevant (problem is navigation, not dates).

## AT-R3 (20260304_152742) - FAIL
- Score: 0.0
- Turns: 30/30
- Root cause: FalseCompletion. Looped trying to find completed tasks for a specific date, submitted unverified answer. Tasks app shows relative date labels; agent can't map absolute dates to UI.
- R4 fix: Generic date-label tip + QA scratchpad guardrail.

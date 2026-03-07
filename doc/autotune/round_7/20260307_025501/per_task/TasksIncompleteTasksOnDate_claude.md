# TasksIncompleteTasksOnDate - R7 Analysis

## Run Summary
- **Run ID**: aw_20260307_025501_TasksIncompleteTasksOnDate_0_0 (actual: _5_0)
- **Score**: 0.0 | **Turns**: 11/30 | **Stop**: GoalAchieved (wrong answer)
- **Model**: qwen3.5
- **Duration**: 148s

## Goal
"What incomplete tasks do I have still have to do by October 19 2023 in Tasks app?"

## Agent Answer
"Research market trends, Call client for follow-up, Attend training session, Change Air Filter, Renew Driver's License, Clean Out Fridge"

## Turn-by-Turn Trace

| Turn | Action | Observation |
|------|--------|-------------|
| 1 | `open_app("Tasks")` | My Tasks list visible, 9 tasks in first viewport |
| 2 | `scroll(down, RecyclerView)` | Sees more tasks: Renew Driver's License (Yest), Grocery Shopping (Tmrw), Learn to Play Guitar (Tue), Submit Expense Report (Fri), Take Dog to the Vet (Sat), Water Plants (Yest), Research Vacation Destinations (Thu), Read 'The Martian' (Tmrw) |
| 3 | `scroll(up)` | Back to top of list. Wasted turn. |
| 4 | `click(index 7, ImageView)` | Tried to check checkbox state of "Attend training session" -- **accidentally toggled checkbox**, completing the task |
| 5 | `click(index 48)` | Tried to undo by clicking checkbox on "Renew Driver's License" -- triggered date picker dialog instead |
| 6 | `click("CANCEL")` | Dismissed date picker |
| 7 | `scratchpad(write)` | Stored date mapping (correct: Thu=Oct 19) and 6 tasks. **Missed many tasks from scroll viewport** |
| 8 | `click(index 6, ImageView)` | Tried to check "Call client for follow-up" checkbox -- **again toggled it** |
| 9 | `click(index 47)` | Tried to undo -- triggered date picker again |
| 10 | `click("CANCEL")` | Dismissed date picker again |
| 11 | `complete_task` | Submitted 6-item answer |

## Failure Analysis

### Root Cause: Destructive Checkbox Probing + Incomplete Scan

**Three compounding failures:**

1. **Destructive exploration of checkbox state** (Critical):
   - Agent clicked ImageView checkbox icons to determine checked/unchecked state
   - In org.tasks, clicking a checkbox toggles it -- this mutated task state
   - The a11y tree does not expose `checked` attribute on these ImageViews
   - Agent realized the mistake and tried to undo, triggering date picker dialogs
   - This consumed 6 of 11 turns on failed state-checking attempts

2. **Incomplete data collection from scroll** (Critical):
   - Agent scrolled down once (turn 2) seeing ~8 more tasks
   - But when writing to scratchpad (turn 7), only recorded 6 tasks
   - Missed: Grocery Shopping (Tmrw=Oct 16), Learn to Play Guitar (Tue=Oct 17), Research Vacation Destinations (Thu=Oct 19), Read 'The Martian' (Tmrw=Oct 16), Water Plants (Yest=Oct 14), Renew Driver's License (Yest=Oct 14)
   - All of these have due dates on or before Oct 19

3. **No completion-status filtering** (Fundamental):
   - Agent assumed all visible tasks were incomplete (ImageViews appeared empty)
   - Could not distinguish completed from incomplete via a11y tree
   - Some tasks in the list may have been completed, making the answer wrong

### App Skill Adherence

| Skill Guidance | Followed? |
|---------------|-----------|
| Resolve relative dates using device date | Yes -- correctly mapped Thu=Oct 19, Tue=Oct 17, etc. |
| Scroll through full list before answering | Partial -- scrolled once but didn't catalog all items |
| Checkbox state = completion status | Attempted but failed -- clicked checkboxes destructively |

### R6 -> R7 Delta
- **Improvement**: Agent now uses 11 turns (up from 2 in R6), meaning it engages with the app rather than answering immediately
- **Improvement**: Date-label resolution works correctly (device date -> absolute dates)
- **Improvement**: Agent scrolled at least once (R6 did zero scrolling)
- **Regression**: Destructive checkbox clicking mutated task state and wasted 6 turns
- **Persistent gap**: Still doesn't gather complete task list before answering

## Recommendations

1. **Checkbox state detection**: The a11y tree ImageView checkboxes in org.tasks do not expose checked/unchecked state. Add to app skill: "Do NOT click checkboxes to check state -- this toggles them. All tasks visible in the default 'My Tasks' list are incomplete (completed tasks are hidden by default)."

2. **Systematic scroll-and-record**: Agent needs to scroll through entire list, recording ALL tasks to scratchpad on each viewport, before forming an answer. Current behavior records a subset.

3. **Scratchpad discipline**: The turn-7 scratchpad write only captured 6 of ~16+ visible tasks across both viewports. Agent should write findings per viewport as it scrolls.

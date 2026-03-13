# TasksIncompleteTasksOnDate — R41 (20260312_173354) — FAIL

**Score**: 0.0 | **Turns**: 8/30 | **Stop**: GoalAchieved (wrong answer)

## Flow Summary

| Turns | Phase |
|-------|-------|
| 1 | write_todos + open_app (Tasks) |
| 2 | click "More options" (overflow menu) |
| 3 | system_button back (menu showed "Show completed" already on) |
| 4 | scratchpad: record first viewport tasks with day labels |
| 5 | scroll down |
| 6 | scroll down |
| 7 | scroll down |
| 8 | complete_task (wrong answer) |

## Root Cause Analysis

**NOT the a11y `checked` property gap.** This is a different failure mode.

The agent correctly opened the app, enabled "Show completed" view, scrolled through the full list, and gathered tasks. However, the a11y tree exposes each task's checkbox as a plain `ImageView` with `clickable: true` but **no `checked` property, no `checkable` property, and no content description** indicating checked/unchecked state. The checkboxes (indices 10, 16, 21, 26, etc.) are all bare `ImageView` elements with no semantic state.

The result: the agent **could not distinguish completed tasks from incomplete tasks** in the list view. It included all tasks due by Oct 19 regardless of completion status.

### Agent's answer (11 tasks)
Attend training session, Call client for follow-up, Clean Out Fridge, Renew Driver's License, Grocery Shopping, Learn to Play Guitar, Research Vacation Destinations, Read 'The Martian', Reply to Emails, Submit Expense Report, Change Air Filter

### What went wrong
The agent treated every task in the list with a due date on or before Oct 19 as incomplete. Some of these tasks are actually completed (checked off), but the a11y tree provides no way to determine checkbox state. The task list includes both incomplete and completed items (since "Show completed" was enabled), and the agent had no signal to filter out the completed ones.

### A11y evidence
Checkbox elements appear as:
```json
{"index": 10, "class": "ImageView", "clickable": true, ...}
```
No `checked`, `checkable`, `selected`, or `desc` field. Compare to the chip/tag elements which DO have `checkable: true`:
```json
{"index": 31, "text": "Tmrw", "class": "View", "clickable": true, "checkable": true, ...}
```

### What the agent did well
- Checked "Show completed" status in overflow menu (good instinct)
- Used scratchpad to track tasks across viewports
- Scrolled through the entire list (3 scrolls to reach bottom)
- Correctly excluded "Water Plants (Oct 22)" and "Organize Closet (Oct 8)" based on date
- Date label reasoning was correct (Thu/Tue/Fri mapped to correct dates relative to "Today" = Oct 15)
- Completed in only 8 turns with systematic scanning

## Verdict

This is still the known a11y gap: task checkbox state is not exposed in the accessibility tree. The agent cannot distinguish complete from incomplete tasks without clicking into each task's detail view (which would be prohibitively expensive in turns). The failure mode shifted from R39 (which also over-included completed tasks) but the root cause is identical.

**Status**: Confirmed a11y platform gap. Cannot be fixed by prompt/skill tuning alone. Requires either:
1. Accessibility tree enhancement to expose checkbox `checked` state
2. A workaround strategy (e.g., click each task to inspect detail view for completion indicator)

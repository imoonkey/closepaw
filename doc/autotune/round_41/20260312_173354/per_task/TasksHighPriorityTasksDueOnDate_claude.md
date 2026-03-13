# TasksHighPriorityTasksDueOnDate — R41 (20260312_173354) — FAIL

**Score**: 0.0 | **Turns**: 19/50 | **Stop**: GoalAchieved (false) | **Verdict**: Due-date disambiguation regression

**Goal**: Which tasks with high priority are due October 18 2023 in the Tasks app?

**Agent answer**: "Marketing Campaign Launch, Back Up Computer Files"
**Expected answer**: "Marketing Campaign Launch" (only)

## Flow Summary

| Turns | Phase |
|-------|-------|
| 1 | open_app → Tasks |
| 2 | List view: identify tasks with "Wed" label as Oct 18 candidates |
| 3 | Open "Learn to Play Guitar" (Wed) → priority = 3rd radio = Medium → dismiss overlay |
| 4-5 | Back → discard dialog → return to list |
| 6-7 | write_todos (plan), scratchpad: record Wed-labeled tasks + Learn priority=medium |
| 8 | Scroll list to find more Wed tasks |
| 9-10 | Open "Marketing Campaign Launch" (Wed) → priority = 4th radio = High → scratchpad: marketing=high |
| 11-12 | Back → open "Renew Driver's License" (clicked wrong instance — detail shows due="Today", not Wed) → priority = 1st radio = None |
| 13-14 | scratchpad: renew=none → back |
| 15 | Discard dialog → click DISCARD |
| 16 | Scrolled list: see "Back Up Computer Files" with "Wed" label at bottom → click it |
| 17 | Detail view: due="Saturday, October 7", start="Wednesday", priority = 4th radio = High → scratchpad: backup=high |
| 18 | write_todos: all checked |
| 19 | complete_task: "Marketing Campaign Launch, Back Up Computer Files" |

## Root Cause

**Category**: ReasoningError — due-date disambiguation failure (skill guidance ignored)

The agent included "Back Up Computer Files" because it relied on the list-view "Wed" label and never verified the due date in the detail view. The detail view clearly showed:

- **"Saturday, October 7"** (full-format date) = **due date** (Oct 7, NOT Oct 18)
- **"Wednesday"** (bare day name) = **start date**

The skill explicitly states: *"The due date is the line showing full date format (day-of-week + month + day). A standalone day name below it is the start date, not due date."* This guidance was injected into the LLM context at every turn, but the agent did not apply it when inspecting "Back Up Computer Files" at turn 17.

The correct answer is "Marketing Campaign Launch" only — the sole task that is both High priority AND due on Wednesday, Oct 18, 2023.

## Why This Is a Regression

This is the exact same failure mode as **R21 retry1** (score 0.0): agent correctly identifies High priority but includes "Back Up Computer Files" due to misreading the "Wednesday" field as due date instead of start date. The due-date disambiguation fix added after R21 retry1 (which made R21 final and R39 pass) did not prevent this recurrence.

## Detail: "Back Up Computer Files" Appears Twice in List

The initial list view (turn 2) shows "Back Up Computer Files" with **"Today"** (Oct 15). After scrolling (turn 16), a second entry shows "Back Up Computer Files" with **"Wed"**. This is likely a recurring or duplicate task entry. The agent only opened the "Wed"-labeled entry, whose detail revealed due date = Oct 7 (already past), not Oct 18.

## Cognition Assessment

| Aspect | Rating | Notes |
|--------|--------|-------|
| Plan formation | Good | Correct 4-step plan: record Wed tasks, scroll, check each, answer |
| Priority reading | Good | Correctly mapped radio buttons: 1st=None, 3rd=Medium, 4th=High |
| List-view date identification | Adequate | Correctly targeted "Wed"-labeled tasks as Oct 18 candidates |
| Due-date verification in detail | **FAIL** | Did not cross-check detail-view full-format date against list-view label |
| Skill adherence | **FAIL** | Skill text about due-date format was visible but not applied at turn 17 |
| Scratchpad usage | Good | Stored priority findings consistently |

## Why the Skill Guidance Failed

The skill says: *"The due date is the line showing full date format (day-of-week + month + day)."* For "Back Up Computer Files," the full-format date is "Saturday, October 7" — clearly not October 18. The agent wrote `{"backup_priority":"high"}` to scratchpad without ever recording or checking the due date. The agent's mental model was: "I already know this task is Wed (Oct 18) from the list view, so I only need to check priority." This shortcut bypassed the due-date verification step.

## Recommendation

**Skill enhancement**: The current skill text explains how to READ the due date in detail view but does not instruct the agent to VERIFY it when the goal filters by date. Add an explicit verification step:

> When the task requires filtering by due date, ALWAYS verify the due date in the detail view using the full-format date line. Do NOT rely on the list-view day label alone — it may show the start date for tasks with a past due date.

This would make the verification mandatory rather than optional, addressing the root cause of this recurring failure.

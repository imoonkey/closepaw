# TasksIncompleteTasksOnDate — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 2/30 | **Reason**: GoalAchieved | **Duration**: 13s

**Goal**: List the titles of incomplete tasks due by October 19, 2023 in the Tasks app, as a comma-separated list.

## Root Cause

**Category**: ReasoningError

The agent opened the Tasks app and saw the task list, but failed to filter correctly on two dimensions: (1) it included tasks with due dates after Oct 19 (Water Plants on Fri Oct 20, Back Up Computer Files on Sat Oct 21, Water Plants on Oct 22), and (2) it included "Change Air Filter" which was visibly checked/completed. The agent treated the entire visible list as the answer without reasoning about dates or completion status, producing 8 items instead of the correct ~5.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("Tasks")` | Y | G | G | G | Correctly opened Tasks app |
| 2 | `complete_task(answer=...)` | N/A | B | B | G | Listed all visible tasks without filtering by date or completion status |

### Key Turns

**Turn 2** — The agent saw the Tasks app showing "My Tasks" with 8 task entries. The a11y tree contained date indicators (Thu, Fri, Sat, Oct 22, Today, Tue) and a checkbox state for "Change Air Filter" (which was checked/completed in the screenshot). The agent needed to: (a) determine which day-of-week abbreviations map to which dates relative to "Today" (Oct 15, 2023 = Sunday), (b) exclude tasks due after Oct 19, and (c) exclude completed tasks. Instead, it dumped all visible task titles verbatim. The correct answer should have excluded Water Plants (Fri=Oct 20), Back Up Computer Files (Sat=Oct 21), Water Plants (Oct 22), and Change Air Filter (completed). Additionally, the list was scrollable (RecyclerView, scrollable=true) so there could have been more tasks below the fold that the agent never checked.

## What Worked

- Fast task execution: opened the correct app in turn 1, answered in turn 2
- Correctly identified the Tasks app (org.tasks package)
- Successfully read task titles from the a11y tree

## What Didn't Work

- No date reasoning: the agent did not map day-of-week abbreviations (Thu, Fri, Sat, Tue) to actual calendar dates relative to "Today" (Oct 15) to determine which tasks fall on or before Oct 19
- Ignored completion status: "Change Air Filter" had a visible checkmark (and the a11y tree showed `checkable: true` on a related element) but was still included
- No scrolling: the RecyclerView was scrollable, meaning more tasks could exist below the fold; the agent never scrolled to verify completeness
- Premature completion: called `complete_task` on turn 2 without any filtering or verification

## Recommendations

- Add system prompt guidance for date-filtering tasks: when a question asks "by [date]", the agent must resolve relative dates (day-of-week abbreviations, "Today", "Tmrw") to absolute dates and filter accordingly
- Add guidance to check completion status: unchecked vs checked checkboxes in task lists indicate incomplete vs complete
- For list-based queries, always scroll to the end of a scrollable container before answering to ensure all items are seen
- Consider a "reason before answering" step for information-retrieval tasks where the agent must explicitly list which items pass/fail the filter criteria before calling `complete_task`

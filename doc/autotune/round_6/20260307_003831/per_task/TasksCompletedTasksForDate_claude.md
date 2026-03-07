# TasksCompletedTasksForDate — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 11/30 | **Reason**: GoalAchieved | **Duration**: 65s

**Goal**: Find completed tasks for October 16, 2023 in the Tasks app and report their titles.

## Root Cause

**Category**: FalseCompletion

The agent answered "Create Budget for Next Month" but this task had a "Tue" (Oct 17) due date, not Oct 16. The emulator date was Oct 15 (Sunday), meaning Oct 16 = "Tmrw" in the app's relative date labels. The agent misread the relative date mapping and picked a task from the wrong date. Additionally, the agent never found a proper "completed tasks" filter — it was looking at a mixed list of completed and uncompleted tasks sorted by due date, making it hard to distinguish completed from uncompleted tasks via the a11y tree alone.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("Tasks")` | Y | G | G | G | Opened Tasks (org.tasks) successfully |
| 2 | `mobile_action(click, "Search")` | Y | B | G | G | Search won't find completed tasks by date |
| 3 | `mobile_action(type, "Oct 16")` | Y | B | G | G | Text search for a date — no results matched |
| 4 | `system_button(back)` | N | G | G | G | Dismissed keyboard after failed search |
| 5 | `mobile_action(click, "Collapse")` | Y | G | G | G | Exited search, back to main list |
| 6 | `mobile_action(click, "Sort")` | Y | G | G | G | Opened sort settings — good pivot strategy |
| 7 | `mobile_action(click, "Completed \| By completion time \| Descending")` | Y | G | G | G | Opened completed task sort options |
| 8 | `mobile_action(click, "By due date")` | Y | G | G | G | Changed sort to due date — reasonable |
| 9 | `system_button(back)` | Y | G | G | G | Returned to task list |
| 10 | `mobile_action(scroll, down)` | Y | G | G | G | Scrolled to see more tasks |
| 11 | `complete_task("Create Budget for Next Month")` | — | B | B | G | Wrong answer — "Tue" = Oct 17, not Oct 16 |

### Key Turns

**Turn 2-3** — Searching "Oct 16" by text was a dead end. The Tasks app uses relative date labels (Today, Yest, Tmrw, Thu, etc.) not absolute dates, so text search cannot match a specific date.

**Turn 10** — After scrolling, the screen showed a mixed list of completed (checked) and uncompleted (unchecked) tasks. The a11y tree does not expose checked/unchecked state of the checkbox ImageViews, so the agent could only distinguish them visually (strikethrough text in screenshots). The agent saw "Create Budget for Next Month" with label "Tue" and incorrectly mapped it to Oct 16. Since the emulator date was Oct 15 (Sunday), "Tue" = Oct 17 and "Tmrw" = Oct 16.

**Turn 11** — The agent submitted "Create Budget for Next Month" as its answer. The tasks labeled "Tmrw" on screen were "Organize files and folders" and "Participate in brainstorming session" — but these appeared to be uncompleted (empty checkboxes). The agent may have needed to scroll further or find a dedicated completed-tasks view to identify which tasks were actually completed for Oct 16.

## What Worked

- Successfully opened the Tasks app and navigated to sort settings
- Good recovery from failed search strategy by switching to sort-based exploration
- Changed completed task sort to "By due date" — a reasonable approach to find tasks by date

## What Didn't Work

- **Date mapping failure**: The agent did not compute the relationship between emulator date (Oct 15 = "Today") and relative labels. "Tue" = Oct 17, not Oct 16. Oct 16 = "Tmrw" (Monday).
- **No completed-task filter**: The app's sort settings had a "Completed" section and "Move completed tasks to bottom" toggle, but the agent never toggled a view that shows only completed tasks. It viewed a mixed list where completed/uncompleted status is only distinguishable by checkbox fill state — which the a11y tree does not expose.
- **Premature answer**: The agent answered after a single scroll without verifying whether the identified task was actually completed (checked) or confirming the date mapping.
- **Text search for dates**: Searching "Oct 16" by text was always going to fail since the app uses relative date labels.

## Recommendations

- **Date arithmetic in system prompt**: When the emulator date is known, the agent should compute the mapping between relative labels (Today, Yest, Tmrw, Mon, Tue...) and absolute dates before navigating. Oct 15 (Sun) means Mon=Tmrw, Tue=day-after-tomorrow, etc.
- **Checkbox state perception**: The a11y tree does not expose whether task checkboxes are checked or unchecked. Either (a) enhance perception to detect checkbox fill state from screenshots, or (b) teach the agent to use the "Completed" filter/section in Tasks app to isolate completed tasks.
- **Scroll more before answering**: The agent should scroll the full list (or at minimum verify the date label matches) before committing an answer for date-filtered queries.

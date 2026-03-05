# TasksIncompleteTasksOnDate — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 2/30 | **Reason**: FalseCompletion | **Duration**: 28s

**Goal**: What incomplete tasks do I have still have to do by October 19 2023 in Tasks app? Answer with the titles only. If there are multiples titles, format your answer in a comma separated list.

## Root Cause

**Category**: ReasoningError

The agent completed in only 2 turns, answering with 8 task titles from the initial screen view. It failed because it did not properly reason about which tasks are due "by October 19, 2023." The agent appears to have included all visible incomplete tasks without filtering by the October 19 cutoff date. The Tasks app showed relative date labels (Thu, Fri, Sat, Today, Tue, Oct 22) that the agent needed to map to absolute dates and then filter for those on or before Oct 19. Tasks with labels like "Oct 22" and "Sat" (Oct 21) and potentially "Tue" (Oct 24 if the following week) should have been excluded. The agent also never scrolled to check for additional tasks below the fold.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | open_app("Tasks") | Yes | OK | OK | OK | Correctly opens Tasks app |
| 2 | scratchpad + complete_task | N/A | Fail | Partial | OK | Wrote answer and completed immediately without filtering by date or scrolling |

### Key Turns

**Turn 2** — This is the critical failure turn. The agent saw the initial task list showing 8-9 tasks with relative date labels and immediately dumped all visible task titles into the answer without any date filtering. The visible tasks were:

- Attend training session (Thu)
- Call client for follow-up (Thu)
- Water Plants (Fri)
- Back Up Computer Files (Sat, Tmrw)
- Research market trends (Thu)
- Water Plants (Oct 22)
- Change Air Filter (Today)
- Renew Driver's License (Tue)
- Clean Out Fridge (Tue)

The agent included all of these except possibly Water Plants (Oct 22). But "by October 19" means the agent should have calculated: Today = Oct 15, so Thu = Oct 19, Fri = Oct 20, Sat = Oct 21, Tue = Oct 17 or Oct 24. Tasks due on Fri (Oct 20), Sat (Oct 21), and Oct 22 are AFTER October 19 and should have been excluded. The agent included "Water Plants" (Fri=Oct 20), "Back Up Computer Files" (Sat=Oct 21), and others that may be past the cutoff.

Additionally, the agent never scrolled down to see if there were more tasks below the initial view, potentially missing qualifying tasks.

## What Worked

- Fast app launch on turn 1
- Used scratchpad to record answer before completing (good practice, but content was wrong)

## What Didn't Work

- **No date filtering**: The agent listed all visible tasks without computing which fall on or before October 19
- **No scrolling**: Only saw the first screenful of tasks — may have missed tasks due on Oct 16-19 that were below the fold
- **Premature completion**: Answered on turn 2 without any exploration or verification. For a date-filtering task, the agent should have computed the date mapping first
- **Ambiguous relative date interpretation**: "Thu" could be Oct 19 (next Thu) or Oct 12 (last Thu). The agent did not reason about which Thursday was meant
- **No verification**: Did not click into individual tasks to confirm their actual due dates

## Recommendations

- **Mandatory date computation before answering**: For any "by date X" query, the agent must first determine today's date, then map all relative labels to absolute dates using scratchpad, then filter
- **Scroll before answering**: Always scroll the full task list to ensure all tasks are seen before answering a counting/listing question
- **Click into ambiguous tasks**: When relative date labels are ambiguous (is "Thu" this week or next?), click into the task to see the exact due date
- **Add "by" vs "on" distinction**: "By October 19" means all tasks with due dates up to and including Oct 19. The agent should parse this temporal constraint carefully
- **Avoid premature completion**: For information-retrieval tasks, require at minimum: (1) full list scan, (2) date computation, (3) filtering, (4) verification pass before calling complete_task

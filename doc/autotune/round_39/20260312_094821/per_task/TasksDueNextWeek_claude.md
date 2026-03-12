# TasksDueNextWeek — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 5/30 | **Reason**: GoalAchieved | **Duration**: 50s

**Goal**: List tasks due next week in Tasks.org.

## Root Cause

**Category**: FalseCompletion

Agent answered in only 5 turns — far too few for a Tasks.org date-range query that requires scrolling through the task list, reading due dates from detail views, and computing "next week" boundaries. The agent likely read visible tasks and guessed the answer without verifying due dates. Known pattern: off-by-1 count or wrong date range for "next week."

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1-2 | Launch Tasks.org, view task list | Y | G | G | G | Task list displayed |
| 3-4 | Read visible tasks | Y | B | B | G | No scroll, no date verification |
| 5 | complete_task with answer | — | B | B | FalseCompletion | Wrong answer |

### Key Turns

**Turn 3** — Agent read task titles from the visible list without opening individual tasks to check their due dates. "Next week" requires precise date boundary computation.

**Turn 5** — Premature GoalAchieved. The answer was either the wrong count or wrong task list because due dates were not verified.

## What Worked

- Tasks.org launched successfully

## What Didn't Work

- No scrolling through task list
- No due date verification in task detail views
- "Next week" date boundary not computed/verified
- Premature completion in 5 turns
- Known issue: off-by-1 date counting

## Recommendations

- Known issue: FalseCompletion epidemic
- Tasks.org skill has date guidance but agent ignored it
- For date-range queries, agent must: (1) compute exact date boundaries, (2) scroll through full task list, (3) open each candidate to verify due date in detail view

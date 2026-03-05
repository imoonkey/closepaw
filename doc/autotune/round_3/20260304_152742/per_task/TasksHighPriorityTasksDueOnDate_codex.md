# TasksHighPriorityTasksDueOnDate - Cog-Tune Analysis

**Score**: 1.0 | **Turns**: 21/30 | **Reason**: GoalAchieved | **Duration**: 203s

**Goal**: Which tasks with high priority are due October 18 2023 in the Tasks app? Answer with the title only. If there are multiples titles, format your answer in a comma separated list.

## Root Cause

**Category**: Success

Task passed with correct title but took 21 turns due to search/back/discard churn. The agent eventually validated due date and priority state correctly. Result quality is good; execution efficiency is not.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1-8 | `open task details and recover from view churn` | Y | G | B | G | Multiple unnecessary back/discard paths |
| 9-15 | `search/date checks + priority inspection` | Y | G | G | G | Found reliable evidence path |
| 16-20 | `confirm competing candidates` | Y | G | G | G | Narrowed to single high-priority task |
| 21 | `complete_task` | Y | G | G | G | Returned correct title |

## What Worked

- Priority and due-date verification eventually converged.
- Final answer matched scorer.

## What Didn't Work

- Extra recovery turns from UI thrash.
- Search flow included avoidable resets.

## Recommendations

- Add a quick path: filter by date once, then inspect priority only for matching rows.

# Round 13 Analysis

**Run**: 20260309_012417 | **Model**: qwen3.5 | **Tasks**: 7 | **Score**: 2/7
**Cumulative**: 18/22 tasks passing (+2 from R13)

## Results

| Task | Score | Reason | Notes |
|------|-------|--------|-------|
| ExpenseAddMultipleFromMarkor | **1.0** | GoalAchieved | First ever pass! Cross-app rule working |
| SportsTrackerActivitiesCountForWeek | 0.0 | MaxTurnsReached (50) | Edit skill correct but too slow — 14 tracks, checked 6 in 50 turns |
| SportsTrackerLongestDistanceActivity | 0.0 | MaxTurnsReached (50) | Same bottleneck |
| SportsTrackerTotalDistanceForCategoryOverInterval | 0.0 | MaxTurnsReached (50) | Same bottleneck |
| TasksCompletedTasksForDate | 0.0 | GoalAchieved (wrong) | Toggle "Show completed" clicks don't register |
| TasksDueNextWeek | 0.0 | GoalAchieved (wrong) | Date range correct (Oct 16-22) but off-by-1 count (7 vs 8) |
| TasksHighPriorityTasks | **1.0** | GoalAchieved | 50-turn budget + partial answer guidance worked |

## Key Findings

### ExpenseAddMultipleFromMarkor — FIXED
The cross-app destination rule (rule #10 from R10) successfully guided the agent to open Pro Expense and enter data there. First pass after multiple rounds of failure.

### TasksHighPriorityTasks — FIXED
50-turn budget gave the agent enough headroom to check all task priorities. The skill's "submit partial answer" guidance may have helped.

### SportsTracker: Edit-based strategy too slow
14 tracks × ~8 turns/track (agent averages 8, not 4) = ~112 turns needed. Even at 50 turns, agent only checked 6/14 tracks. The "check every track" approach is fundamentally unscalable.

**Fix for R14**: Smart name-based filtering — skip tracks with names clearly unrelated to the target activity type (e.g., "Alpine Skiing" for a running query). This reduces checks from 14 to ~5.

### TasksDueNextWeek: Date reasoning improved
The concrete example ("If today is Sunday Oct 15, next week = Mon Oct 16 – Sun Oct 22") fixed the date range calculation. But the agent miscounted: excluded "Create Budget for Next Month" (Fri = Oct 20) despite it being in range. Off by 1.

### TasksCompletedTasksForDate: Action execution issue
The "Show completed" toggle in the overflow menu doesn't change state when clicked via accessibility. The agent toggled it 4 times but the list never changed. This is an action execution issue, not a cognition issue.

## R14 Changes
1. OpenTracks SKILL.md → smart name-based filtering to prioritize/skip tracks
2. SportsTracker tasks + TasksCompletedTasksForDate + TasksDueNextWeek in R14 config

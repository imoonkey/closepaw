# Round 12 Analysis

**Run**: 20260309_004521 | **Model**: qwen3.5 | **Tasks**: 7 | **Score**: 0/7 = 0%
**Cumulative**: 16/22 tasks passing from R10-R11 (unchanged)

## Results

| Task | Score | Reason | Root Cause |
|------|-------|--------|------------|
| SportsTrackerActivitiesCountForWeek | 0.0 | MaxTurnsReached (30) | Shell strategy failed (permissions), only 30 turns |
| SportsTrackerLongestDistanceActivity | 0.0 | MaxTurnsReached (30) | Same |
| SportsTrackerTotalDistanceForCategoryOverInterval | 0.0 | MaxTurnsReached (30) | Same |
| TasksCompletedTasksForDate | 0.0 | Error (turn 5) | Provider SSE error (infra failure) |
| TasksDueNextWeek | 0.0 | GoalAchieved (wrong) | Date reasoning: "next week" on Sunday = Oct 23-29 instead of Oct 16-22 |
| TasksHighPriorityTasks | 0.0 | MaxTurnsReached (30) | Found 4/8 priorities but never submitted answer |
| ExpenseAddMultipleFromMarkor | 0.0 | GoalAchieved (wrong) | Agent entered data in Pro Expense but scoring still 0 |

## Key Findings

### Shell strategy was wrong approach (SportsTracker x3)
The R12 skill told the agent to use `sqlite3` to query OpenTracks database. This fails because:
1. The agent's shell runs in the agent app's process context
2. No root/run-as access to other apps' private data directories
3. Agent oscillated between shell and UI approaches, wasting turns on both

**Fix**: Reverted to Edit-based UI approach with clear turn budgeting guidance.

### max_turns:50 not applied
The default.yaml was modified after the eval started, so SportsTracker tasks still ran with 30 turns instead of 50.

### TasksDueNextWeek: Date reasoning error
On Sunday Oct 15, "next week starting Monday" = Oct 16-22. Agent calculated Oct 23-29. The Tasks.org skill said "next Monday-Sunday range" but the model still misinterpreted.

**Fix**: Added concrete example: "If today is Sunday Oct 15, next week = Mon Oct 16 – Sun Oct 22."

### TasksHighPriorityTasks: Never submitted answer
Found 4 high-priority tasks in scratchpad but kept navigating. Got stuck clicking Grocery Shopping 5 times. Even on FINAL TURN warning, didn't submit partial answer.

**Fix**: Added to skill: "If running low on turns, submit your best answer from scratchpad evidence." Also increased max_turns to 50.

## R13 Changes Applied
1. OpenTracks SKILL.md → Edit-based approach (reverted from shell)
2. Tasks.org SKILL.md → concrete date example + partial-answer guidance
3. default.yaml → max_turns:50 for SportsTracker + TasksHighPriorityTasks
4. Commit: fdbb921

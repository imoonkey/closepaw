# Round 14 Analysis

**Run**: 20260309_020714 | **Model**: qwen3.5 | **Tasks**: 5 | **Score**: 2/5 (2/2 non-infra)
**Cumulative**: 20/22 tasks passing (+2 from R14)

## Results

| Task | Score | Turns | Reason | Notes |
|------|-------|-------|--------|-------|
| SportsTrackerActivitiesCountForWeek | **1.0** | 18 | GoalAchieved | Smart filtering: 18 turns vs 50+ previously |
| SportsTrackerLongestDistanceActivity | **1.0** | 48 | GoalAchieved | Close to limit but succeeded |
| SportsTrackerTotalDistanceForCategoryOverInterval | 0.0 | 38 | Error (402) | OpenRouter credits exhausted mid-task |
| TasksCompletedTasksForDate | 0.0 | 1 | Error (402) | Credits exhausted before starting |
| TasksDueNextWeek | 0.0 | 1 | Error (402) | Credits exhausted before starting |

## Key Findings

### Smart name filtering worked perfectly
SportsTrackerActivitiesCountForWeek went from MaxTurnsReached@50 to GoalAchieved@18 turns. The agent correctly used track names to skip obviously unrelated activities and only Edit-checked ambiguous ones.

### OpenRouter credit limit hit
The last 3 tasks failed with 402: "This request requires more credits, or fewer max_tokens. You requested up to 65536 tokens, but can only afford 25435." This is purely an infrastructure issue.

## Remaining Failures (2 genuine, 1 untested)

### Likely to pass on rerun:
- **SportsTrackerTotalDistanceForCategoryOverInterval**: Had 38 turns of good progress before credits died. Smart filtering should work.

### Genuine failures (need investigation):
- **TasksCompletedTasksForDate**: Toggle "Show completed" click doesn't register (action execution issue). Needs /action-debug investigation.
- **TasksDueNextWeek**: Off-by-1 counting error (model reasoning). Hard to fix via prompts.

## Progress Summary
- R9 baseline: 0/22 (all fail)
- R10: 12/22 (+12)
- R11: 16/22 (+4)
- R13: 18/22 (+2)
- R14: 20/22 (+2)

**20 out of 22 targeted tasks now passing = 90.9% pass rate**

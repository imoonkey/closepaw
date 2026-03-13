# Round 46 Eval Analysis — Common Problems

**Run ID**: `20260313_125245` | **Date**: 2026-03-13 | **Model**: gpt-5.4
**Tasks**: 18 evaluated | **Pass Rate**: 6/18 = 33.3%

## Scorecard

| Task | Score | Turns | Root Cause | R45 Score | Delta |
|------|-------|-------|------------|-----------|-------|
| ExpenseAddMultipleFromMarkor | 0.0 | 29 | MaxTurns (Error, close to 30 limit) | 1.0 | REGRESSION |
| MarkorMoveNote | 0.0 | 13 | FalseCompletion (GoalAchieved, scorer disagrees) | 1.0 | REGRESSION |
| **RecipeAddMultipleRecipesFromMarkor2** | **1.0** | 25 | Success (browse-list verification worked) | 0.0 | **RECOVERED** |
| **RecipeDeleteDuplicateRecipes2** | **1.0** | 50 | Success (field comparison correct, 50/60 turns) | 0.0 | **RECOVERED** |
| RecipeDeleteDuplicateRecipes3 | 0.0 | 78 | GhostDelete + TurnExhaustion | 0.0 | = |
| RetroCreatePlaylist | 0.0 | 30 | MaxTurns (Error) | 1.0 | REGRESSION |
| RetroPlaylistDuration | 0.0 | 60 | MaxTurns (multi-select failure persists) | 0.0 | = |
| RetroSavePlaylist | 0.5 | 30 | PartialCompletion | 1.0 | REGRESSION |
| SimpleCalendarEventsInNextWeek | 1.0 | 17 | Success (stable) | 1.0 | = |
| SportsTrackerActivitiesCountForWeek | 0.0 | 26 | PrematureStop (3/14 checked, 74 turns unused!) | 0.0 | = |
| **SportsTrackerLongestDistanceActivity** | **1.0** | 55 | Success (turn increase worked!) | 0.0 | **RECOVERED** |
| SportsTrackerTotalDistanceForCategoryOverInterval | 1.0 | 50 | Success (stable) | 1.0 | = |
| TasksCompletedTasksForDate | 0.0 | 12 | AccidentalToggle (checkbox tapped, data corrupted) | 1.0 | REGRESSION |
| TasksDueNextWeek | 0.0 | 30 | TurnBudget (verified 6/12 candidates, ran out at 30) | 0.0 | = |
| TasksHighPriorityTasksDueOnDate | 1.0 | 19 | Success (stable) | 1.0 | = |
| TasksIncompleteTasksOnDate | 0.0 | 29 | ModelVariance | 1.0 | REGRESSION |
| TurnOnWifiAndOpenApp | 0.5 | 5 | PartialCompletion | 1.0 | REGRESSION |
| VlcCreateTwoPlaylists | 0.0 | 18 | Error (early stop) | 0.0 | = |

### Summary: 3 recovered, 7 regressions, 5 persistent failures

## Key Analysis

### High nondeterminism in R46
7 tasks that passed in R45 regressed. Most are model variance, not caused by R46 changes:
- RetroCreate/Save: Stable for 5+ rounds, R46 changes didn't touch core navigation
- MarkorMoveNote: R46 didn't change Markor skill
- TasksCompleted: Agent accidentally toggled checkbox (A11y gap)
- TasksIncomplete: Model chose not to follow toggle protocol
- TurnOnWifi: Partial completion (0.5)

### R46 fixes that worked
1. **SportsTrackerLongestDistanceActivity**: Turn increase 50→60 enabled 8/8 track verification (55 turns used). **Recovered.**
2. **RecipeAddMultipleRecipesFromMarkor2**: Browse-list verification replaced search. **Recovered.**
3. **RecipeDeleteDuplicateRecipes2**: Turn increase 50→60 + field comparison. **Recovered.**

### Persistent failures analysis
1. **TasksDueNextWeek**: Verification mandate IS being followed now (6/12 checked in R46 vs 1/12 in R45). But 30 max_turns insufficient. Need task override.
2. **SportsTrackerActivitiesCountForWeek**: Agent checked 3/14 then stopped at 26/100 turns. Actively ignoring exhaustive-check mandate despite 74 turns remaining.
3. **VlcCreateTwoPlaylists**: Error at 18 turns — need trace investigation.
4. **RetroPlaylistDuration**: Multi-select toolbar UI issue persists (60 turns, Error).
5. **RecipeDeleteDuplicateRecipes3**: Ghost-delete (78/80 turns).

## Combined R45+R46 Pass Coverage
13/18 tasks have passed at least once across R45-R46. 5 persistent failures remain.

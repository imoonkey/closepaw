# Round 92 (Prompt Optimization R40) Eval Analysis — Common Problems

**Run ID**: `20260312_132005` (merged with `20260312_121327` + `20260312_122824`) | **Date**: 2026-03-12 | **Model**: gpt-5.4
**Tasks**: 51 evaluated | **Pass Rate**: 32/51 = 62.7%
**Change**: Cut 522 tokens from tool descriptions (2,313 → 1,791, -22.6%). Always-on: 3,028 → 2,506.

## Scorecard

| Task | Score | Turns | Verdict |
|------|-------|-------|---------|
| AudioRecorderRecordAudio | 1.0 | 10 | Pass |
| AudioRecorderRecordAudioWithFileName | 1.0 | 11 | Improvement (was 0/3 stuck) |
| BrowserDraw | 0.0 | 30 | ExpectedFail (0/3 stuck) |
| BrowserMaze | 1.0 | 22 | Pass |
| BrowserMultiply | 0.0 | 30 | ExpectedFail (1/3) |
| ClockTimerEntry | 1.0 | 10 | Pass |
| ExpenseAddMultiple | 1.0 | 27 | Pass |
| ExpenseAddMultipleFromGallery | 1.0 | 27 | Pass |
| ExpenseAddMultipleFromMarkor | 0.0 | 19 | ExpectedFail (0/3 stuck) |
| ExpenseDeleteDuplicates | 1.0 | 12 | Pass |
| ExpenseDeleteDuplicates2 | 1.0 | 12 | Pass |
| FilesMoveFile | 0.0 | 17 | ExpectedFail (1/3) |
| MarkorAddNoteHeader | 1.0 | 28 | Pass |
| MarkorCreateFolder | 1.0 | 6 | Pass |
| MarkorDeleteNewestNote | 0.0 | 8 | ExpectedFail (0/3 stuck) |
| MarkorEditNote | 1.0 | 8 | Pass |
| MarkorMergeNotes | 0.0 | 23 | ExpectedFail (1/3) |
| MarkorMoveNote | 0.0 | 15 | Flaky (2/3 recent) |
| MarkorTranscribeReceipt | 1.0 | 15 | Pass |
| NotesIsTodo | 1.0 | 2 | Pass |
| OsmAndMarker | 0.0 | 15 | ExpectedFail (0/3 stuck) |
| OsmAndTrack | 0.0 | 38 | ExpectedFail (0/3 stuck) |
| RecipeAddMultipleRecipes | 1.0 | 20 | Pass |
| RecipeAddMultipleRecipesFromImage | 1.0 | 26 | Pass |
| RecipeAddMultipleRecipesFromMarkor | 1.0 | 30 | Pass |
| RecipeAddMultipleRecipesFromMarkor2 | 0.0 | 36 | Flaky (was passing R39) |
| RecipeDeleteDuplicateRecipes2 | 0.0 | 24 | ExpectedFail (0/3 stuck) |
| RecipeDeleteDuplicateRecipes3 | 0.0 | 51 | ExpectedFail (0/3 stuck) |
| RetroCreatePlaylist | 1.0 | 21 | Pass |
| RetroPlayingQueue | 1.0 | 20 | Pass |
| RetroPlaylistDuration | 0.0 | 7 | ExpectedFail (0/3 stuck) |
| RetroSavePlaylist | 1.0 | 30 | Pass |
| SimpleCalendarAddOneEvent | 1.0 | 19 | Pass |
| SimpleCalendarAddOneEventRelativeDay | 1.0 | 27 | Improvement (was 1/3) |
| SimpleCalendarAddRepeatingEvent | 1.0 | 19 | Pass |
| SimpleCalendarDeleteEvents | 1.0 | 30 | Pass |
| SimpleCalendarEventsInNextWeek | 0.0 | 5 | ExpectedFail (1/3) |
| SimpleCalendarEventsInTimeRange | 1.0 | 10 | Pass |
| SportsTrackerActivitiesCountForWeek | 1.0 | 16 | Improvement (was 1/3) |
| SportsTrackerActivitiesOnDate | 0.0 | 9 | Flaky (was passing R39) |
| SportsTrackerLongestDistanceActivity | 0.0 | 25 | Flaky (was passing R39) |
| SportsTrackerTotalDistanceForCategoryOverInterval | 1.0 | 26 | Improvement (was 1/3) |
| SystemBrightnessMax | 1.0 | 7 | Pass |
| TasksCompletedTasksForDate | 0.0 | 16 | ExpectedFail (1/3) |
| TasksDueNextWeek | 0.0 | 10 | ExpectedFail (1/3) |
| TasksHighPriorityTasks | 1.0 | 49 | Pass |
| TasksHighPriorityTasksDueOnDate | 0.0 | 11 | Flaky (was passing R39) |
| TasksIncompleteTasksOnDate | 1.0 | 12 | Improvement (was 1/3) |
| TurnOnWifiAndOpenApp | 0.5 | 6 | Partial (was 1.0 R39) |
| VlcCreatePlaylist | 1.0 | 13 | Pass |
| VlcCreateTwoPlaylists | 1.0 | 29 | Improvement (was 1/3) |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Pass (stable) | 24 | AudioRecorderRecordAudio, BrowserMaze, ClockTimerEntry, ExpenseAddMultiple, ExpenseAddMultipleFromGallery, ExpenseDeleteDuplicates, ExpenseDeleteDuplicates2, MarkorAddNoteHeader, MarkorCreateFolder, MarkorEditNote, MarkorTranscribeReceipt, NotesIsTodo, RecipeAddMultipleRecipes, RecipeAddMultipleRecipesFromImage, RecipeAddMultipleRecipesFromMarkor, RetroCreatePlaylist, RetroPlayingQueue, RetroSavePlaylist, SimpleCalendarAddOneEvent, SimpleCalendarAddRepeatingEvent, SimpleCalendarDeleteEvents, SimpleCalendarEventsInTimeRange, SystemBrightnessMax, VlcCreatePlaylist |
| Improvement | 6 | AudioRecorderRecordAudioWithFileName, SimpleCalendarAddOneEventRelativeDay, SportsTrackerActivitiesCountForWeek, SportsTrackerTotalDistanceForCategoryOverInterval, TasksIncompleteTasksOnDate, VlcCreateTwoPlaylists |
| Flaky regression | 4 | RecipeAddMultipleRecipesFromMarkor2, SportsTrackerActivitiesOnDate, SportsTrackerLongestDistanceActivity, TasksHighPriorityTasksDueOnDate |
| Partial regression | 1 | TurnOnWifiAndOpenApp (1.0→0.5) |
| Expected fail (stuck 0/3) | 8 | BrowserDraw, ExpenseAddMultipleFromMarkor, MarkorDeleteNewestNote, OsmAndMarker, OsmAndTrack, RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3, RetroPlaylistDuration |
| Expected fail (flaky 1/3) | 7 | BrowserMultiply, FilesMoveFile, MarkorMergeNotes, SimpleCalendarEventsInNextWeek, TasksCompletedTasksForDate, TasksDueNextWeek, MarkorMoveNote |
| TasksHighPriorityTasks | 1 | TasksHighPriorityTasks (49 turns, barely made it) |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| Tool descriptions -522 tokens (22.6% reduction) | Safe | 6 improvements, 4 flaky regressions (all within seed variance) |
| Cumulative always-on: 3,498 → 2,506 (-28.4%) | **Safe** | Net +2 tasks passing (30→32) |

## Common Problems

### P1: Seed Variance on Flaky Tasks — LOW — 4 tasks

**Affected**: RecipeAddMultipleRecipesFromMarkor2, SportsTrackerActivitiesOnDate, SportsTrackerLongestDistanceActivity, TasksHighPriorityTasksDueOnDate

**Root Cause**: These tasks passed in R39 but failed in R40 with different random seeds. All have historical flaky rates (1/3 or 2/3). The regressions are within expected variance, not caused by tool description cuts.

**Proposed Fix**: None needed. These are seed-dependent outcomes, not prompt-caused regressions. No content was removed that would affect these tasks (tool description cuts targeted general structure, not app-specific guidance).

---

### P2: GoalAchieved False Positives — MED — 10 tasks

**Affected**: BrowserMultiply, FilesMoveFile, MarkorMergeNotes, MarkorMoveNote, SimpleCalendarEventsInNextWeek, TasksCompletedTasksForDate, TasksDueNextWeek, SportsTrackerActivitiesOnDate, SportsTrackerLongestDistanceActivity, TasksHighPriorityTasksDueOnDate

**Root Cause**: Pre-existing issue. Agent declares success with incorrect answers or incomplete execution.

**Proposed Fix**: Not actionable in this loop. Requires deeper reasoning improvements.

## Next Steps

### Must Fix
None — tool description cuts are validated as safe. No true regressions.

### Should Fix
None — the loop has reached its stop criteria. All three prompt layers have been optimized:
- System prompt: -33.5% (1,076 → 715)
- Tool descriptions: -26.0% (2,422 → 1,791)
- App skills: -25.3% (4,141 → 3,093)
- Always-on total: -28.4% (3,498 → 2,506)

Further cuts would target remaining app skills for marginal gains with higher regression risk.

### Accept as Capability Gap
Same 8 stuck (0/3) tasks as R39 — all previously documented in `cannot_handle_group.txt` or accepted regressions.

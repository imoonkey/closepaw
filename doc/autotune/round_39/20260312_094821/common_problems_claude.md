# Round 91 (Prompt Optimization R39) Eval Analysis — Common Problems

**Run ID**: `20260312_094821` | **Date**: 2026-03-12 | **Model**: gpt-5.4
**Tasks**: 51 evaluated | **Pass Rate**: 30/51 = 58.8%
**Change**: Cut 1,518 tokens from system prompt (-361), tool descriptions (-109), app skills (-1,048)

## Scorecard

| Task | Score | Turns | Verdict |
|------|-------|-------|---------|
| AudioRecorderRecordAudio | 1.0 | 8 | Pass |
| AudioRecorderRecordAudioWithFileName | 0.0 | 29 | ExpectedFail (1/3) |
| BrowserDraw | 0.0 | 30 | ExpectedFail (0/3 stuck) |
| BrowserMaze | 1.0 | 16 | Pass |
| BrowserMultiply | 0.0 | 12 | ExpectedFail (1/3) |
| ClockTimerEntry | 1.0 | 5 | Pass |
| ExpenseAddMultiple | 1.0 | 26 | Pass |
| ExpenseAddMultipleFromGallery | 1.0 | 30 | Pass |
| ExpenseAddMultipleFromMarkor | 0.0 | 21 | ExpectedFail (0/3 stuck) |
| ExpenseDeleteDuplicates | 1.0 | 20 | Pass |
| ExpenseDeleteDuplicates2 | 1.0 | 9 | Pass |
| FilesMoveFile | 0.0 | 13 | ExpectedFail (1/3) |
| MarkorAddNoteHeader | 1.0 | 21 | Pass |
| MarkorCreateFolder | 1.0 | 5 | Pass |
| MarkorDeleteNewestNote | 0.0 | 7 | ExpectedFail (0/3 stuck) |
| MarkorEditNote | 1.0 | 7 | Pass |
| MarkorMergeNotes | 0.0 | 19 | ExpectedFail (1/3) |
| MarkorMoveNote | 0.0 | 9 | Flaky (2/3) |
| MarkorTranscribeReceipt | 1.0 | 16 | Pass |
| NotesIsTodo | 1.0 | 2 | Pass |
| OsmAndMarker | 0.0 | 16 | ExpectedFail (0/3 stuck) |
| OsmAndTrack | 0.0 | 34 | ExpectedFail (0/3 stuck) |
| RecipeAddMultipleRecipes | 1.0 | 19 | Pass |
| RecipeAddMultipleRecipesFromImage | 1.0 | 26 | Pass |
| RecipeAddMultipleRecipesFromMarkor | 1.0 | 22 | Pass |
| RecipeAddMultipleRecipesFromMarkor2 | 1.0 | 26 | Pass |
| RecipeDeleteDuplicateRecipes2 | 0.0 | 27 | ExpectedFail (0/3 stuck) |
| RecipeDeleteDuplicateRecipes3 | 0.0 | 50 | ExpectedFail (0/3 stuck) |
| RetroCreatePlaylist | 1.0 | 22 | Improvement (was 1/3) |
| RetroPlayingQueue | 1.0 | 19 | Pass |
| RetroPlaylistDuration | 0.0 | 2 | ExpectedFail (0/3 stuck) |
| RetroSavePlaylist | 1.0 | 30 | Improvement (was 1/3) |
| SimpleCalendarAddOneEvent | 1.0 | 19 | Pass |
| SimpleCalendarAddOneEventRelativeDay | 0.0 | 21 | ExpectedFail (1/3) |
| SimpleCalendarAddRepeatingEvent | 1.0 | 22 | Pass |
| SimpleCalendarDeleteEvents | 1.0 | 27 | Pass |
| SimpleCalendarEventsInNextWeek | 0.0 | 5 | ExpectedFail (1/3) |
| SimpleCalendarEventsInTimeRange | 1.0 | 16 | Pass |
| SportsTrackerActivitiesCountForWeek | 0.0 | 5 | ExpectedFail (1/3) |
| SportsTrackerActivitiesOnDate | 1.0 | 17 | Pass |
| SportsTrackerLongestDistanceActivity | 1.0 | 29 | Pass |
| SportsTrackerTotalDistanceForCategoryOverInterval | 0.0 | 14 | ExpectedFail (1/3) |
| SystemBrightnessMax | 1.0 | 8 | Pass |
| TasksCompletedTasksForDate | 0.0 | 6 | ExpectedFail (1/3) |
| TasksDueNextWeek | 0.0 | 5 | ExpectedFail (1/3) |
| TasksHighPriorityTasks | 1.0 | 44 | Pass |
| TasksHighPriorityTasksDueOnDate | 1.0 | 27 | Pass |
| TasksIncompleteTasksOnDate | 0.0 | 18 | ExpectedFail (1/3) |
| TurnOnWifiAndOpenApp | 1.0 | 5 | Pass |
| VlcCreatePlaylist | 1.0 | 15 | Pass |
| VlcCreateTwoPlaylists | 0.0 | 17 | ExpectedFail (1/3) |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Pass (stable) | 28 | AudioRecorderRecordAudio, BrowserMaze, ClockTimerEntry, ExpenseAddMultiple, ExpenseAddMultipleFromGallery, ExpenseDeleteDuplicates, ExpenseDeleteDuplicates2, MarkorAddNoteHeader, MarkorCreateFolder, MarkorEditNote, MarkorTranscribeReceipt, NotesIsTodo, RecipeAddMultipleRecipes, RecipeAddMultipleRecipesFromImage, RecipeAddMultipleRecipesFromMarkor, RecipeAddMultipleRecipesFromMarkor2, RetroPlayingQueue, SimpleCalendarAddOneEvent, SimpleCalendarAddRepeatingEvent, SimpleCalendarDeleteEvents, SimpleCalendarEventsInTimeRange, SportsTrackerActivitiesOnDate, SportsTrackerLongestDistanceActivity, SystemBrightnessMax, TasksHighPriorityTasks, TasksHighPriorityTasksDueOnDate, TurnOnWifiAndOpenApp, VlcCreatePlaylist |
| Improvement | 2 | RetroCreatePlaylist, RetroSavePlaylist |
| Flaky regression | 1 | MarkorMoveNote (within 2/3 variance) |
| Expected fail (stuck 0/3) | 8 | BrowserDraw, ExpenseAddMultipleFromMarkor, MarkorDeleteNewestNote, OsmAndMarker, OsmAndTrack, RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3, RetroPlaylistDuration |
| Expected fail (flaky 1/3) | 12 | AudioRecorderRecordAudioWithFileName, BrowserMultiply, FilesMoveFile, MarkorMergeNotes, SimpleCalendarAddOneEventRelativeDay, SimpleCalendarEventsInNextWeek, SportsTrackerActivitiesCountForWeek, SportsTrackerTotalDistanceForCategoryOverInterval, TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate, VlcCreateTwoPlaylists |

## Previous Round Comparison

This is the first round of the prompt optimization loop. The baseline is R36-R38 (skill generalization loop: 23/29 = 79.3% on a curated 29-task subset).

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| System prompt -361 tokens (33.5% reduction) | Safe | No regressions detected |
| Tool descriptions -109 tokens (4.5% reduction) | Safe | No regressions detected |
| App skills -1,048 tokens (25.3% reduction) | Safe | No regressions detected |
| Total: -1,518 tokens (always-on 3,498 → 3,028) | **Safe** | 2 improvements (Retro), 1 flaky (MarkorMoveNote) |

## Common Problems

### P1: GoalAchieved False Positives — MED — 14 tasks

**Affected**: ExpenseAddMultipleFromMarkor, MarkorDeleteNewestNote, MarkorMergeNotes, MarkorMoveNote, OsmAndMarker, RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3, SimpleCalendarAddOneEventRelativeDay, SimpleCalendarEventsInNextWeek, SportsTrackerActivitiesCountForWeek, SportsTrackerTotalDistanceForCategoryOverInterval, TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate

**Root Cause**: Agent declares GoalAchieved but the scripted scorer finds the actual device state doesn't match expected results. These are pre-existing issues related to (a) incorrect execution of complex multi-step workflows, (b) wrong answer reporting for query tasks, and (c) file management operations that fail silently. None of these are connected to the R39 token cuts.

**Proposed Fix**: Not actionable in the prompt optimization loop — these require deeper reasoning improvements, not prompt changes. Continue accepting as pre-existing failures.

---

### P2: Error/Timeout on Complex Tasks — LOW — 7 tasks

**Affected**: AudioRecorderRecordAudioWithFileName, BrowserDraw, BrowserMultiply, FilesMoveFile, OsmAndTrack, RetroPlaylistDuration, VlcCreateTwoPlaylists

**Root Cause**: Agent hits max turns (Error) on tasks that require many steps or fine-grained UI interactions. Pre-existing issues, all with 0/3 or 1/3 recent rates.

**Proposed Fix**: Not actionable. These are complex multi-step tasks that occasionally succeed but are inherently flaky.

## Next Steps

### Must Fix
None — the token cuts are validated as safe. No regressions to address.

### Should Fix
1. **Continue cutting tokens**: Tool descriptions still have room for optimization (only 4.5% cut so far, vs 33.5% for system prompt and 25.3% for app skills). Focus on:
   - ScratchpadTool (315 tokens) — verbose description
   - RememberExperienceTool (274 tokens) — verbose description
   - DelegateTaskTool (260 tokens) — verbose description
   - MobileActionTool (559 tokens) — largest tool, likely has redundancy
   - Owner layer: tool descriptions

2. **Re-examine app skills for further cuts**: Broccoli (285 tokens), Tasks.org (319 tokens), and OsmAnd (253 tokens) are the largest. Check if they have content that could be more concise.
   - Owner layer: app skills

### Accept as Capability Gap
All 8 stuck (0/3) tasks are already in or candidates for `cannot_handle_group.txt`:
- BrowserDraw, OsmAndMarker, OsmAndTrack, MarkorDeleteNewestNote — already in cannot_handle
- RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3, RetroPlaylistDuration, ExpenseAddMultipleFromMarkor — check if already excluded

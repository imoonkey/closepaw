# Round 57 Eval Analysis — Common Problems

**Run IDs**: `20260317_194418` (initial) + `20260317_205840` (rerun for 19 proxy-failed tasks)
**Date**: 2026-03-17 | **Model**: gpt-5.4
**Tasks**: 51 evaluated | **Pass Rate**: 32.5/51 = 63.7%
**Context**: App skill top-down rewrite validation (-65.6% skill tokens, 366→126 lines). 4 unparked tasks.

## Scorecard

| Task | Score | Source | Root Cause |
|------|-------|--------|------------|
| AudioRecorderRecordAudio | 1.0 | initial | Success |
| AudioRecorderRecordAudioWithFileName | 1.0 | initial | Success |
| BrowserDraw | 0.0 | initial | PlatformGap (unparked, reparking) |
| BrowserMaze | 1.0 | initial | Success |
| BrowserMultiply | 0.0 | initial | Nondeterminism (flaky, passed R41) |
| ClockTimerEntry | 1.0 | initial | Success |
| ExpenseAddMultiple | 1.0 | initial | Success |
| ExpenseAddMultipleFromGallery | 0.0 | initial | Nondeterminism (flaky, passed R37/R39) |
| ExpenseAddMultipleFromMarkor | 1.0 | initial | Success |
| ExpenseDeleteDuplicates | 1.0 | initial | Success |
| ExpenseDeleteDuplicates2 | 1.0 | initial | Success |
| FilesMoveFile | 0.0 | initial | FilenameMismatch (non-deterministic, passed R41-R43) |
| MarkorAddNoteHeader | 0.0 | initial | ActionLayer (newline collapse, passed R39/R40) |
| MarkorCreateFolder | 1.0 | initial | Success |
| MarkorDeleteNewestNote | 0.0 | rerun | PlatformGap (unparked, reparking) |
| MarkorEditNote | 1.0 | initial | Success |
| MarkorMergeNotes | 0.0 | rerun | ExtensionField (.md not cleared, passed R42/R43) |
| MarkorMoveNote | 1.0 | initial | Success |
| MarkorTranscribeReceipt | 1.0 | rerun | Success |
| NotesIsTodo | 1.0 | rerun | Success |
| OsmAndMarker | 0.0 | initial | PlatformGap (unparked, reparking) |
| OsmAndTrack | 0.0 | rerun | PlatformGap (unparked, reparking) |
| RecipeAddMultipleRecipes | 1.0 | rerun | Success |
| RecipeAddMultipleRecipesFromImage | 1.0 | rerun | Success |
| RecipeAddMultipleRecipesFromMarkor | 1.0 | rerun | Success |
| RecipeAddMultipleRecipesFromMarkor2 | 1.0 | rerun | Success |
| RecipeDeleteDuplicateRecipes2 | 0.0 | rerun | PersistentFailure (2/12 in R45-R56) |
| RecipeDeleteDuplicateRecipes3 | 0.0 | rerun | PersistentFailure (2/12 in R45-R56) |
| RetroCreatePlaylist | 1.0 | rerun | Success |
| RetroPlayingQueue | 1.0 | rerun | Success |
| RetroPlaylistDuration | 0.0 | rerun | ReasoningError (misunderstands "provided songs") |
| RetroSavePlaylist | 1.0 | initial | Success |
| SimpleCalendarAddOneEvent | 1.0 | rerun | Success |
| SimpleCalendarAddOneEventRelativeDay | 1.0 | initial | Success |
| SimpleCalendarAddRepeatingEvent | 1.0 | rerun | Success |
| SimpleCalendarDeleteEvents | 1.0 | initial | Success |
| SimpleCalendarEventsInNextWeek | 0.0 | rerun | DateBoundary (nondeterministic, ~42% rate) |
| SimpleCalendarEventsInTimeRange | 1.0 | initial | Success |
| SportsTrackerActivitiesCountForWeek | 0.0 | rerun | StructurallyInfeasible (14 tracks × 6 turns) |
| SportsTrackerActivitiesOnDate | 1.0 | initial | Success |
| SportsTrackerLongestDistanceActivity | 1.0 | initial | Success |
| SportsTrackerTotalDistanceForCategoryOverInterval | 0.0 | rerun | ScrollNoncompliance (nondeterministic) |
| SystemBrightnessMax | 1.0 | initial | Success |
| TasksCompletedTasksForDate | 0.0 | initial | PersistentFailure (2/12 in R45-R56) |
| TasksDueNextWeek | 0.0 | initial | DuplicateCounting (nondeterministic, ~42% rate) |
| TasksHighPriorityTasks | 1.0 | initial | Success |
| TasksHighPriorityTasksDueOnDate | 1.0 | initial | Success |
| TasksIncompleteTasksOnDate | 0.0 | initial | PersistentFailure (2/12 in R45-R56) |
| TurnOnWifiAndOpenApp | 1.0 | initial | Success |
| VlcCreatePlaylist | 1.0 | initial | Success |
| VlcCreateTwoPlaylists | 0.5 | initial | Partial (nondeterministic, ~25% rate) |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 32 | — |
| Partial | 1 | VlcCreateTwoPlaylists |
| PlatformGap (unparked) | 4 | BrowserDraw, MarkorDeleteNewestNote, OsmAndMarker, OsmAndTrack |
| PersistentFailure | 4 | RecipeDeleteDuplicateRecipes2/3, TasksCompletedTasksForDate, TasksIncompleteTasksOnDate |
| Nondeterminism | 7 | BrowserMultiply, ExpenseAddMultipleFromGallery, FilesMoveFile, MarkorAddNoteHeader, MarkorMergeNotes, SimpleCalendarEventsInNextWeek, TasksDueNextWeek |
| StructurallyInfeasible | 1 | SportsTrackerActivitiesCountForWeek |
| ScrollNoncompliance | 1 | SportsTrackerTotalDistanceForCategoryOverInterval |
| ReasoningError | 1 | RetroPlaylistDuration |

## Previous Round Comparison

This round validates the app skill top-down rewrite (all 17 skills rewritten, 366→126 lines, -65.6%). No code/prompt fixes applied — Step 1 skipped.

| Change | Status | Impact |
|--------|--------|--------|
| App skill rewrite (-65.6% tokens) | **VALIDATED** | 32.5/51 (63.7%) vs R39: 30/51, R40: 32/51. No regression. |
| AppSkillRepository YAML frontmatter stripping | Working | Verified in traces: frontmatter stripped, content preserved. |
| Unpark BrowserDraw, MarkorDeleteNewestNote, OsmAndMarker, OsmAndTrack | All failed | All 4 reparked to cannot_handle. |

## Common Problems

### P1: Nondeterministic Failures — MED — 7 tasks

**Affected**: BrowserMultiply, ExpenseAddMultipleFromGallery, FilesMoveFile, MarkorAddNoteHeader, MarkorMergeNotes, SimpleCalendarEventsInNextWeek, TasksDueNextWeek

**Root Cause**: All 7 tasks have historical pass rates of 25-83% (R45-R56). Their R57 failures match known failure modes: filename mismatch (FilesMoveFile), newline collapse (MarkorAddNoteHeader), .md extension (MarkorMergeNotes), date boundary confusion (SimpleCalendarEventsInNextWeek), duplicate counting (TasksDueNextWeek). These are LLM nondeterminism, not skill rewrite regressions.

**Proposed Fix**: No fix needed. These tasks pass at their historical rates. The skill rewrite did not worsen them.

---

### P2: Persistent Failures — HIGH — 4 tasks

**Affected**: RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3, TasksCompletedTasksForDate, TasksIncompleteTasksOnDate

**Root Cause**: Recipe tasks: agent claims GoalAchieved with wrong answers (17% rate over 12 rounds). Tasks.org completion tasks: completion detection still unreliable (17% rate). The Tasks.org completion-detection rewrite (detail-view Completion field) was committed in R56 but this is its first real test — both tasks still failed.

**Proposed Fix**: Investigate TasksCompletedTasksForDate trace to check if the new completion-detection approach was actually used. If so, the approach needs further refinement.

---

### P3: Unparked Tasks Still Infeasible — LOW — 4 tasks

**Affected**: BrowserDraw, MarkorDeleteNewestNote, OsmAndMarker, OsmAndTrack

**Root Cause**: These were unparked as a test. All 4 failed as expected due to platform/evaluator limitations unchanged since parking.

**Proposed Fix**: Reparking all 4 to cannot_handle_group.txt.

---

### P4: Structural Infeasibility — LOW — 1 task

**Affected**: SportsTrackerActivitiesCountForWeek

**Root Cause**: 14 tracks × 6 turns/track = 84 turns needed, exceeds 100-turn budget given overhead. Agent either answers prematurely or runs out of turns.

**Proposed Fix**: Keep in cannot_handle or accept as nondeterministic (~58% rate when within budget).

## Next Steps

### Must Fix
None. The skill rewrite is validated — no regressions detected.

### Should Fix
1. **Investigate Tasks.org completion-detection rewrite**: Check R57 traces for TasksCompletedTasksForDate and TasksIncompleteTasksOnDate to verify the new detail-view Completion field approach was used.
   - Targets: TasksCompletedTasksForDate, TasksIncompleteTasksOnDate
   - Implementation: Trace review, potential Tasks.org skill refinement
   - Owner layer: app skill: org.tasks

2. **RetroPlaylistDuration reasoning**: Agent still misunderstands "provided songs" as externally provided. Consider adding clarification to RetroMusic skill.
   - Targets: RetroPlaylistDuration
   - Implementation: RetroMusic skill clarification
   - Owner layer: app skill: code.name.monkey.retromusic

### Accept as Capability Gap
- **BrowserDraw**: Canvas drawing not possible via accessibility. Reparking.
- **MarkorDeleteNewestNote**: Evaluator limitation (newest = creation timestamp, not visible in UI). Reparking.
- **OsmAndMarker**: Map interaction not possible via accessibility tree. Reparking.
- **OsmAndTrack**: OsmAnd track import workflow blocked. Reparking.
- **SportsTrackerActivitiesCountForWeek**: Structurally infeasible (84 turns needed). Keep parked.

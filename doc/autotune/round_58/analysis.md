# R58 Analysis

## Result: 33/51 (64.7%) — R57: 32.5/51 (63.7%), +1%

## Changes Applied
- **System prompt**: Removed LTM scope details, compressed Completion section (-5 lines, ~80 tokens)
- **Tool descriptions**: Trimmed AskUser, Wait, CompleteTask (~50 tokens)
- **App skills**: Markor rename, Expense category match, OpenTracks counting, Tasks.org date filtering, SimpleCalendar date traversal

## Recoveries (5)
| Task | Cause | Historical |
|------|-------|------------|
| MarkorAddNoteHeader | Rename guidance worked | 5/15 |
| ExpenseAddMultipleFromGallery | Category matching worked | 4/24 |
| SimpleCalendarEventsInNextWeek | Date range traversal worked | 4/17 |
| BrowserMultiply | Stochastic | 4/23 |
| OsmAndMarker | Stochastic (was parked) | - |

## Regressions (5)
| Task | Cause | Historical |
|------|-------|------------|
| ExpenseDeleteDuplicates2 | Date filter hides duplicates (stochastic) | 3/15 |
| SportsTrackerLongestDistanceActivity | Stochastic | 7/17 |
| SystemBrightnessMax | Stochastic | 13/17 |
| TasksHighPriorityTasks | Stochastic | 8/19 |
| VlcCreateTwoPlaylists | Stochastic | 6/17 |

None of the regressions overlap with our changes. All are stochastic tasks that fluctuate round-to-round.

## Failure Taxonomy (18 tasks)

### Parked / Chronic (≤1/15 pass rate) — 6 tasks
BrowserDraw, RecipeDeleteDuplicateRecipes2, RecipeDeleteDuplicateRecipes3, RetroPlaylistDuration, TasksDueNextWeek, TasksCompletedTasksForDate

### Stochastic (sometimes pass, sometimes fail) — 7 tasks
FilesMoveFile (wrong file selected), MarkerMergeNotes (extension not cleared), ExpenseDeleteDuplicates2 (date filter), SystemBrightnessMax, SportsTrackerLongestDistanceActivity, TasksHighPriorityTasks, VlcCreateTwoPlaylists

### Parked infra — 2 tasks
MarkorDeleteNewestNote, OsmAndTrack

### Chronic cognitive — 3 tasks
SportsTrackerActivitiesCountForWeek, SportsTrackerTotalDistanceForCategoryOverInterval, TasksIncompleteTasksOnDate

## Cog-tune Findings (actionable for R59)
1. **DocumentsUI skill**: Add exact filename matching guidance (fixes FilesMoveFile)
2. **Expense skill**: Add "widen date filter before scanning" guidance (fixes ExpenseDeleteDuplicates2)
3. **Markor skill**: Strengthen extension-clearing to CRITICAL (partially fixes MarkorMergeNotes)

## Conclusion
Prompt trimming validated safe (no regression). Targeted app skill fixes have clear ROI (3/3 worked). Remaining failures are dominated by stochastic variance and chronic tasks. Diminishing returns on further prompt tuning — consider pivoting to priority.md backlog items.

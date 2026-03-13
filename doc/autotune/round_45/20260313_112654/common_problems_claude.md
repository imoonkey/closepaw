# Round 45 Eval Analysis — Common Problems

**Run ID**: `20260313_112654` | **Date**: 2026-03-13 | **Model**: gpt-5.4
**Tasks**: 18 evaluated (R45 set, minus 2 parked) | **Pass Rate**: 10/18 = 55.6%

## Scorecard

| Task | Score | Turns | Root Cause | Prev Score | Delta |
|------|-------|-------|------------|------------|-------|
| **ExpenseAddMultipleFromMarkor** | **1.0** | 27 | Success (agent opened Pro Expense correctly) | 0.0 | **RECOVERED** |
| **MarkorMoveNote** | **1.0** | 11 | Success (stable) | 1.0 | = |
| RecipeAddMultipleRecipesFromMarkor2 | 0.0 | 46 | SearchIndexBug (Broccoli can't find long title) | 0.0 | = |
| RecipeDeleteDuplicateRecipes2 | 0.0 | 58 | OverDeletion (3 instead of correct count, profile drift) | 0.0 | = |
| RecipeDeleteDuplicateRecipes3 | 0.0 | 78 | GhostDelete + TurnExhaustion | 0.0 | = |
| **RetroCreatePlaylist** | **1.0** | 29 | Success (stable) | 1.0 | = |
| RetroPlaylistDuration | 0.0 | 59 | MultiSelectFailure (couldn't find toolbar action) | 0.0 | = |
| **RetroSavePlaylist** | **1.0** | 30 | Success (stable) | 1.0 | = |
| **SimpleCalendarEventsInNextWeek** | **1.0** | 26 | Success (day-by-day scanning worked!) | 0.0 | **RECOVERED** |
| SportsTrackerActivitiesCountForWeek | 0.0 | 50 | TurnExhaustion (7/14 tracks checked, needs ~107 turns) | 0.0 | = |
| SportsTrackerLongestDistanceActivity | 0.0 | 50 | TurnExhaustion (7/8 checked, 3 turns short) | 0.0 | = |
| **SportsTrackerTotalDistanceForCategoryOverInterval** | **1.0** | 50 | Success (stable) | 1.0 | = |
| **TasksCompletedTasksForDate** | **1.0** | 28 | Success (toggle protocol followed!) | 0.0 | **RECOVERED** |
| TasksDueNextWeek | 0.0 | 9 | PrematureAnswer (verified 1 task, skipped 7) | 0.0 | = |
| **TasksHighPriorityTasksDueOnDate** | **1.0** | 22 | Success (stable) | 1.0 | = |
| **TasksIncompleteTasksOnDate** | **1.0** | 26 | Success (toggle protocol followed!) | 0.0 | **RECOVERED** |
| **TurnOnWifiAndOpenApp** | **1.0** | 5 | Success (stable) | 1.0 | = |
| VlcCreateTwoPlaylists | 0.0 | 30 | WorkflowDetour (Playlists tab + ML prefs wasted 9 turns) | 0.0 | = |

### Summary: 4 recovered, 0 regressions, 8 still failing (of 18 evaluated)

## Fix Impact Analysis

| Fix Applied in R45 | Status | Impact |
|---------------------|--------|--------|
| Tasks.org: CRITICAL section at top (toggle + detail-view) | **PASSED** | TasksCompletedTasksForDate + TasksIncompleteTasksOnDate both **RECOVERED** |
| Calendar: day-by-day scanning mandate | **PASSED** | SimpleCalendarEventsInNextWeek **RECOVERED** |
| VLC: CRITICAL library tabs mandate | **PARTIAL** | Agent used Video tab (not Browse!) but still detoured to Playlists tab |
| Retro: multi-select as preferred + duration strategy | **UNCLEAR** | Skill may not have been loaded (trace artifacts empty) |
| RecipeDeleteDup2: max_turns 50→60 | **FAILED** | Still over-deleted (58 turns, reasoning error) |
| Park BrowserDraw + MarkorDeleteNewestNote | **N/A** | Excluded from eval |

## Common Problems

### P1: TasksDueNextWeek — Verification Abandoned — 9 turns used of 30
Agent computed correct range, opened ONE task detail (proved labels unreliable), then answered based on 8 unverified labels including the task it just proved wrong. Had 21 turns remaining.
**Fix**: Add explicit "you MUST verify EVERY candidate" to skill counting section.

### P2: RetroPlaylistDuration — Multi-Select Broken — 59 turns
Agent couldn't find "Add to playlist" in multi-select toolbar. Toolbar "More options" showed view settings, not playlist actions. Fell back to per-song (4-6 turns each). Added only 4 of ~15 needed songs.
**Fix**: Clarify toolbar button location. May need hybrid perception to identify icons.

### P3: SportsTracker Turn Budgets — 2 tasks
LongestDistanceActivity: 7/8 tracks checked, needed ~56 turns (has 50).
ActivitiesCountForWeek: 7/14 checked, needs ~107 turns (has 50).
**Fix**: Increase max_turns to 60 and 100 respectively.

### P4: Recipe App Issues — 3 tasks
RecipeAdd: Broccoli search can't find long titles → verification loop.
RecipeDup2: Over-deletion from profile tracking drift across 6 entries.
RecipeDup3: Ghost-delete (UI shows deletion, DB doesn't persist).
**Fix**: Change verification to browse-list instead of search. Add scratchpad tracking for deletion count.

### P5: VLC Workflow Inefficiency — 1 task
Agent correctly used Video tab but detoured to Playlists tab + ML preferences (9 turns wasted). Also failed multi-select (tapped text not row area).
**Fix**: Add workflow sequence: "Stay on Video tab, select files first, then create playlist when prompted."

## Token Delta
- Always-on: 2,576 → 2,576 (unchanged)
- App skills: Tasks.org -4 lines, Calendar ±0, VLC -5 lines, Retro -2 lines = net reduction (~40 tokens saved)

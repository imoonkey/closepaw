# Round 44 Eval Analysis — Common Problems

**Run ID**: `20260313_095409` | **Date**: 2026-03-13 | **Model**: gpt-5.4
**Tasks**: 20 evaluated (full R44 set) | **Pass Rate**: 6/20 = 30.0%

## Scorecard

| Task | Score | Turns | Root Cause | Prev Score | Delta |
|------|-------|-------|------------|------------|-------|
| BrowserDraw | 0.0 | 30 | PlatformLimitation (a11y→WebView) | 0.0 | = |
| ExpenseAddMultipleFromMarkor | 0.0 | 15 | WrongDestination (stayed in Markor) | 0.0 | = |
| MarkorDeleteNewestNote | 0.0 | 8 | EvaluatorBug (atime mutation) | 0.0 | = |
| **MarkorMoveNote** | **1.0** | 30 | Success (exact filename fix worked) | 0.0 | **RECOVERED** |
| RecipeAddMultipleRecipesFromMarkor2 | 0.0 | 24 | SilentSaveFailure + FalseCompletion | 0.0 | = |
| RecipeDeleteDuplicateRecipes2 | 0.0 | 50 | TurnExhaustion (full comparison too slow) | 0.0 | = |
| RecipeDeleteDuplicateRecipes3 | 0.0 | 61 | TurnExhaustion (61/80 turns) | 0.0 | = |
| **RetroCreatePlaylist** | **1.0** | 20 | Success (stable) | 1.0 | = |
| RetroPlaylistDuration | 0.0 | 60 | Unknown (need trace) | 0.0 | = |
| **RetroSavePlaylist** | **1.0** | 29 | Success (stable) | 1.0 | = |
| SimpleCalendarEventsInNextWeek | 0.0 | 13 | SkippedDateRange (ignored scratchpad rule) | 0.0 | = |
| SportsTrackerActivitiesCountForWeek | 0.0 | 30 | IncompleteCoverage (checked few of 14) | 0.0 | = |
| SportsTrackerLongestDistanceActivity | 0.0 | 50 | InexactTypeMatch or IncompleteCoverage | 0.0 | = |
| **SportsTrackerTotalDistanceForCategoryOverInterval** | **1.0** | 50 | Success (scroll+exhaustive check worked) | 0.0 | **RECOVERED** |
| TasksCompletedTasksForDate | 0.0 | 28 | A11yGap + NavigationDerailment | 0.0 | = |
| TasksDueNextWeek | 0.0 | 7 | PrematureAnswer (no detail-view verification) | 0.0 | = |
| **TasksHighPriorityTasksDueOnDate** | **1.0** | 26 | Success (detail-view verification worked) | 0.0 | **RECOVERED** |
| TasksIncompleteTasksOnDate | 0.0 | 14 | SkippedToggleProtocol | 0.0 | = |
| **TurnOnWifiAndOpenApp** | **1.0** | 6 | Success (verification fix worked) | 0.5 | **RECOVERED** |
| VlcCreateTwoPlaylists | 0.0 | 30 | WrongView (used Browse not Video tab) | 0.0 | = |

### Summary: 4 recovered, 0 regressions, 14 still failing

## Fix Impact Analysis

| Fix Applied in R44 | Status | Impact |
|---------------------|--------|--------|
| ScratchpadState DISPLAY_TRUNCATE 200→400 | **UNCLEAR** | TasksDueNextWeek still failed (7 turns, premature answer) |
| Tasks.org: completion toggle for both queries | **FAILED** | TasksIncompleteTasksOnDate ignored toggle protocol |
| Tasks.org: due-date detail-view mandate | **PARTIAL** | TasksHighPriority **PASSED**, TasksDueNextWeek ignored it |
| Tasks.org: compact dedup format | **UNCLEAR** | TasksDueNextWeek answered in 7 turns, didn't need dedup |
| Broccoli: prescriptive field comparison | **PARTIAL** | RecipeDeleteDup2 DID compare fields but ran out of turns (50) |
| OpenTracks: exact type matching + exhaustive | **PARTIAL** | SportsTrackerTotal **PASSED**, LongestDistance still failed |
| RetroMusic: "provided songs" = library | **UNCLEAR** | Need R44 trace to verify if skill was seen |
| VLC: library-view vs Browse distinction | **FAILED** | Agent used Browse anyway |
| Expense: strip selection labels | **UNCLEAR** | Agent didn't even reach Pro Expense in R44 |
| Calendar: scratchpad date range discipline | **FAILED** | Agent didn't compute range first |
| Settings: toggle verification | **PASSED** | TurnOnWifiAndOpenApp **RECOVERED** |
| Markor: exact filename matching | **PASSED** | MarkorMoveNote **RECOVERED** |

## Common Problems

### P1: Agent Ignores Skill Guidance — HIGH — 4+ tasks

**Affected**: SimpleCalendarEventsInNextWeek, TasksIncompleteTasksOnDate, VlcCreateTwoPlaylists, TasksDueNextWeek

The app skills contain the correct guidance, but the agent doesn't follow it. In all 4 cases the skill had explicit instructions (compute date range first; use toggle protocol; use Video tab not Browse; open detail views) and the agent skipped them.

**Assessment**: This is the #1 systemic issue. Making skills more prominent or moving critical rules to stronger positions (tool descriptions, core prompt) may help.

### P2: Turn Budget for Prescriptive Workflows — MEDIUM — 2 tasks

**Affected**: RecipeDeleteDuplicateRecipes2 (50 turns), RecipeDeleteDuplicateRecipes3 (61/80 turns)

The Broccoli skill's prescriptive full-field comparison worked — agent DID open recipes and compare fields. But the workflow is too slow for the turn budget. Each recipe comparison takes 3-4 turns.

**Assessment**: Increase max_turns for these tasks, or optimize the comparison strategy (batch multiple fields per turn).

### P3: Environmental/Scorer Issues — MEDIUM — 3 tasks

**Affected**: MarkorDeleteNewestNote (evaluator bug), ExpenseAddMultipleFromMarkor (wrong destination), RecipeAddMultipleRecipesFromMarkor2 (silent save)

These failures have non-prompt root causes. MarkorDeleteNewestNote has an evaluator bug. ExpenseAddMultipleFromMarkor has an ambiguous task prompt. RecipeAdd has an emulator timing issue.

### P4: Platform Limitations — LOW — 1 task

**Affected**: BrowserDraw — a11y clicks don't fire JS DOM events in Chrome WebView. No prompt fix possible.

## Next Steps

### Must Fix (R45)
1. **Strengthen must-do rules in skills**: Move critical "you MUST do X" instructions to the TOP of affected skills with imperative language. Especially for: date-range computation, toggle protocol, Video-tab mandate, detail-view verification.
2. **Increase max_turns for RecipeDeleteDuplicateRecipes2**: 50→60 to accommodate the prescriptive comparison workflow.
3. **Investigate R44 traces for RetroPlaylistDuration and ExpenseAddMultipleFromMarkor**: Need to understand if the new skill guidance was seen and what the agent actually did.

### Consider for cannot_handle
4. **BrowserDraw**: Platform limitation, park permanently.
5. **MarkorDeleteNewestNote**: Evaluator bug, agent is correct. Park until evaluator is fixed.

### Token delta
- Always-on: 2,576 → 2,576 (unchanged)
- App skills total: 3,254 → 3,569 (+315 tokens, +9.7%)

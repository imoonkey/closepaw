# Common Problems — Round 3 (20260304_152742)

**Agent**: qwen3.5 | **Model**: basic | **Perception**: accessibility_only
**Run**: 20260304_152742 | **Tasks**: 25 | **Score**: 19/25 (76%)

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| MarkorAddNoteHeader | 1.0 | — | Success |
| MarkorEditNote | 1.0 | — | Success |
| NotesIsTodo | 1.0 | — | Success |
| NotesTodoItemCount | 1.0 | — | Success |
| RecipeAddMultipleRecipesFromMarkor2 | 1.0 | — | Success |
| RecipeDeleteDuplicateRecipes3 | 0.0 | 30 | TurnExhaustion |
| RecipeDeleteSingleWithRecipeWithNoise | 1.0 | — | Success |
| RetroPlaylistDuration | 0.0 | 30 | ReasoningError (wrong strategy) |
| SimpleCalendarEventsInTimeRange | 1.0 | — | Success |
| SimpleCalendarFirstEventAfterStartTime | 1.0 | — | Success |
| SportsTrackerActivitiesCountForWeek | 0.0 | 25+ | TurnExhaustion + InfraError |
| SportsTrackerActivityDuration | 1.0 | — | Success |
| SportsTrackerLongestDistanceActivity | 1.0 | — | Success |
| SportsTrackerTotalDistanceForCategoryOverInterval | 1.0 | — | Success |
| SportsTrackerTotalDurationForCategoryThisWeek | 1.0 | — | Success |
| SystemBluetoothTurnOffVerify | 1.0 | — | Success |
| SystemBluetoothTurnOnVerify | 1.0 | — | Success |
| SystemBrightnessMaxVerify | 1.0 | — | Success |
| SystemBrightnessMinVerify | 1.0 | — | Success |
| SystemWifiTurnOffVerify | 1.0 | — | Success |
| SystemWifiTurnOnVerify | 1.0 | — | Success |
| TasksCompletedTasksForDate | 0.0 | 30 | NavigationFailure / TurnExhaustion |
| TasksDueNextWeek | 0.0 | 6 | ReasoningError (hallucination) |
| TasksHighPriorityTasksDueOnDate | 1.0 | — | Success |
| TasksIncompleteTasksOnDate | 0.0 | 2 | ReasoningError (no date filtering) |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 19 | — |
| ReasoningError | 3 | RetroPlaylistDuration, TasksDueNextWeek, TasksIncompleteTasksOnDate |
| TurnExhaustion | 2 | RecipeDeleteDuplicateRecipes3, SportsTrackerActivitiesCountForWeek |
| NavigationFailure | 1 | TasksCompletedTasksForDate (overlaps TurnExhaustion) |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| P1: Blank line tip (`\n\n`) | **Working** | MarkorAddNoteHeader: 0→1 (2-round stuck bug fixed!) |
| P2: Duration strategy tip | **Ineffective** | RetroPlaylistDuration still 0. Agent didn't follow the tip. |
| Q3: keyboard_visible detection | **Deployed** | Code fix applied. Cannot verify from traces yet (need text-input task with keyboard_visible in prompt JSON). |

## Common Problems

### P1: Tasks app date reasoning — HIGH — 3 tasks

**Affected**: TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate

**Root Cause**: The Tasks app UI shows relative date labels ("Mon", "Tue", "Fri", "Oct 22") instead of absolute dates. The agent cannot map between absolute dates in the goal (e.g., "October 16, 2023") and the relative labels in the UI. This leads to three distinct failure modes:
- TasksCompletedTasksForDate: 30-turn loop trying to navigate to completed tasks for a specific date
- TasksDueNextWeek: Hallucinated task names instead of reading actual UI data (6 turns, fabricated answer)
- TasksIncompleteTasksOnDate: Answered in 2 turns without date-filtering (included tasks past cutoff date)

**Proposed Fix**:
- Add Tasks app tip to system prompt: "The Tasks app shows relative date labels (Mon, Tue, Oct 22). When the goal references a specific date, first compute what day of week that date is using the device date (available via shell `date`). Then map to the relative labels visible on screen."
- Add Tasks app tip: "For QA tasks about tasks on specific dates, use scratchpad to track which tasks match the date filter. Do NOT guess or hallucinate — only report tasks whose date labels you can see and have verified."

---

### P2: RetroPlaylistDuration wrong strategy (repeat) — HIGH — 1 task

**Affected**: RetroPlaylistDuration

**Root Cause**: The P2 tip from R2 ("use iterative add-and-check") was added to the system prompt but the agent still wasted 12 turns trying to pre-read individual song durations before pivoting to iterative addition at turn 19. Only ~5 songs added before hitting 30 turns. The agent never checked the playlist view for the running total ("N Songs / MM:SS").

This is a 3-round repeat failure. The app tip was present but the model (qwen3.5) did not follow it. This may be a model capability limitation rather than a prompt issue.

**Proposed Fix**:
- Consider this a model limitation. The tip is correct and present. Not worth further prompt iteration unless switching to a stronger model.
- Alternative: Move to cannot_handle or accept as persistent 0 for qwen3.5.

---

### P3: Turn budget pressure on repetitive tasks — MED — 2 tasks

**Affected**: RecipeDeleteDuplicateRecipes3, SportsTrackerActivitiesCountForWeek

**Root Cause**: Both tasks require many repetitive actions that each consume 4-5 turns:
- RecipeDeleteDuplicateRecipes3: Many duplicates to delete. Agent correctly identified pattern but ran out of turns mid-deletion.
- SportsTrackerActivitiesCountForWeek: Checking each track's activity type requires 5 turns (open → More options → Edit → read → back). Only 3 of 8+ tracks checked in 24 turns.

**Proposed Fix**:
- SportsTracker: Add app tip — "Activity type icons in the track list often correspond to track names (e.g., 'Morning Jog' = running). Use name heuristics to infer type when possible, only opening Edit for ambiguous tracks."
- RecipeDeleteDuplicateRecipes3: Consider max_turns=50 task override if the task requires deleting 5+ duplicates.

---

### Q1: Wasted shell commands on app databases — LOW — 2 tasks

**Affected**: SportsTrackerActivitiesCountForWeek, RetroPlaylistDuration

Both tasks wasted turns on shell commands trying to access app databases (always fails on non-rooted Android). The existing prompt says "After 2 failed shell commands with the same approach, STOP" but the agent doesn't consistently follow this.

## What Worked

1. **P1 blank line tip** → MarkorAddNoteHeader: 0→1. **2-round stuck bug finally fixed!** This was the #1 remaining issue from R1/R2.
2. **Group 5 first-run sweep**: 17/22 never-run tasks passed on first attempt (77%). Strong baseline for the agent.
3. **System verify tasks**: 6/6 all passing (BluetoothTurnOff/On, BrightnessMax/Min, WifiTurnOff/On).
4. **SportsTracker expansion**: 4/5 new tasks passing. Only ActivitiesCountForWeek failed (turn budget).
5. **Calendar expansion**: 2/2 new tasks passing (EventsInTimeRange, FirstEventAfterStartTime).
6. **Notes tasks**: 2/2 new tasks passing (IsTodo, TodoItemCount).
7. **Recipe tasks**: 2/3 new tasks passing (AddMultipleFromMarkor2, DeleteSingleWithNoise).

## Next Steps (proposed for Round 4)

### Must Fix
1. **Tasks app date reasoning tip** (P1): Add Tasks app tips to system prompt for date mapping between absolute dates and relative UI labels. Add scratchpad-based answer grounding for QA tasks.

### Should Fix
2. **SportsTracker name heuristic tip** (P3): Add OpenTracks tip about inferring activity type from track names to reduce per-track turn cost.

### Accept / Park
3. **RetroPlaylistDuration** (P2): 3-round persistent failure. The strategy tip is correct but qwen3.5 doesn't follow it. Accept as model limitation for now.
4. **RecipeDeleteDuplicateRecipes3**: First run, may benefit from max_turns increase. Not a code/prompt issue.


# Qi Notes
1. 这个可以加generic app tip，但不要太overfit to task. 最终还是为了真实世界有效性，不是为了benchmark分高。
2. 
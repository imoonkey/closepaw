# Round 10 Common Problems — Claude Analysis

**Run**: 20260308_221303 | **Model**: qwen3.5 | **Tasks**: 22 | **Score**: 12/22 = 54.5%
**Baseline**: R9 all 22 were FAIL → **12 new passes (+12)**

## Results Summary

| Task | R9 | R10 | Delta | Fix Applied |
|------|----|----|-------|-------------|
| ExpenseAddMultipleFromMarkor | FAIL | FAIL | — | Fix 2 (cross-app) |
| FilesMoveFile | FAIL | **PASS** | +1 | Fix 3 (exact-target) |
| MarkorAddNoteHeader | FAIL | FAIL | — | Fix 4 (newline) |
| RetroSavePlaylist | FAIL | **PASS** | +1 | Fix 6 (Retro skill) |
| SimpleCalendarAddOneEventRelativeDay | FAIL | **PASS** | +1 | Fix 6 (Calendar skill) |
| SimpleCalendarDeleteEvents | FAIL | **PASS** | +1 | Fix 6 (Calendar skill) |
| SimpleCalendarEventOnDateAtTime | FAIL | **PASS** | +1 | Fix 6 (Calendar skill) |
| SimpleCalendarEventsInNextWeek | FAIL | FAIL | — | Fix 6 (Calendar skill) |
| SimpleCalendarEventsInTimeRange | FAIL | **PASS** | +1 | Fix 6 (Calendar skill) |
| SimpleCalendarEventsOnDate | FAIL | **PASS** | +1 | Fix 6 (Calendar skill) |
| SimpleCalendarNextEvent | FAIL | **PASS** | +1 | Fix 6 (Calendar skill) |
| SportsTrackerActivitiesCountForWeek | FAIL | FAIL | — | Fix 1 (anti-hallucination) |
| SportsTrackerLongestDistanceActivity | FAIL | FAIL | — | Fix 1 |
| SportsTrackerTotalDistanceForCategoryOverInterval | FAIL | FAIL | — | Fix 1 |
| SportsTrackerTotalDurationForCategoryThisWeek | FAIL | **PASS** | +1 | Fix 1 |
| TasksCompletedTasksForDate | FAIL | FAIL | — | Fix 5 (Tasks.org skill) |
| TasksDueNextWeek | FAIL | FAIL | — | Fix 5 |
| TasksDueOnDate | FAIL | **PASS** | +1 | Fix 5 |
| TasksHighPriorityTasks | FAIL | FAIL | — | Fix 5 |
| TasksHighPriorityTasksDueOnDate | FAIL | FAIL | — | Fix 5 |
| TasksIncompleteTasksOnDate | FAIL | FAIL | — | Fix 5 |
| VlcCreateTwoPlaylists | FAIL | **PASS** | +1 | Fix 7 (VLC debug) |

## Failure Analysis (10 remaining failures)

### Cluster 1: Tasks.org — Skill injected but ignored (5 failures)

**Confirmed**: Tasks.org skill IS injected in all traces (verified `llm_input_items` for TasksHighPriorityTasks). The model (qwen3.5) receives the skill guidance but doesn't follow it.

- **TasksCompletedTasksForDate**: TurnExhaustion. Agent clicked wrong menu 14x trying to find "Show completed" toggle. Never found it.
- **TasksDueNextWeek**: FalseCompletion. Wrong count — date-mapping reasoning error.
- **TasksHighPriorityTasks**: TurnExhaustion. One-by-one priority checking (4 turns/task, 30 turns insufficient for 10+ tasks).
- **TasksHighPriorityTasksDueOnDate**: TurnExhaustion. Same one-by-one pattern. Had answer in scratchpad at turn 28 but ran out of turns.
- **TasksIncompleteTasksOnDate**: FalseCompletion. Partial answer — incomplete scrolling.

**Root cause**: The skill tells the model WHAT to do but not specific enough HOW. The model needs more actionable guidance: specific UI element names for the overflow menu, priority filter location.

### Cluster 2: SportsTracker/OpenTracks — No app skill (3 failures)

All 3 follow identical pattern: TurnExhaustion via 4-turn-per-track Edit loop (open → Edit → check type → back). No app skill exists to teach efficient navigation.

- **SportsTrackerActivitiesCountForWeek**: Checked tracks 1-by-1, exhausted turns.
- **SportsTrackerLongestDistanceActivity**: Same pattern, never found mountain biking track.
- **SportsTrackerTotalDistanceForCategoryOverInterval**: Same pattern, never found kayaking.

**Root cause**: No OpenTracks skill. Agent needs to know activity types are visible from track names/list without entering Edit mode.

### Cluster 3: Individual failures (2)

- **MarkorAddNoteHeader**: Agent typed `\n` instead of `\n\n`. Then looped 25 turns trying to position cursor. Never renamed file. Fix 4 preserved newlines in perception but the model needs to type `\n\n` in the first place.
- **ExpenseAddMultipleFromMarkor**: Agent found expenses and entered them, but data format/precision didn't match (e.g., "96.3" instead of "96.30").
- **SimpleCalendarEventsInNextWeek**: Found only 1 event ("Product demo"), missed others. Insufficient scrolling in weekly view.

## Next Steps for R11

### Fix A: OpenTracks App Skill — HIGH
**Impact**: 3 SportsTracker tasks
Create `app/src/main/assets/app_skills/de.dennisguse.opentracks/SKILL.md`:
- Activity type is visible in track name/subtitle on the list view. Do NOT enter Edit mode to check.
- For counting: scroll full list, note all matching activities.
- For distances/durations: click the track to see its stats, not the Edit button.

### Fix B: Tasks.org Skill — More Actionable Guidance — HIGH
**Impact**: 5 Tasks.org tasks
Strengthen the skill with specific UI paths:
- Overflow menu (3 vertical dots, top right) → "Show completed" for completion filter.
- Priority is shown as a colored indicator (no text) — must open task detail to read priority.
- For priority queries: use the sort-by-priority feature if available, or be explicit that 1-by-1 is needed and budget turns.

### Fix C: Markor Typing Guidance — LOW
**Impact**: 1 task (MarkorAddNoteHeader)
Add to Markor skill: "For blank lines in Markor, type `\n\n` — a single `\n` only adds a line break, not a blank line."

## Projected Impact

Conservative: 12/22 → 15-17/22 if Fixes A-B land effectively.

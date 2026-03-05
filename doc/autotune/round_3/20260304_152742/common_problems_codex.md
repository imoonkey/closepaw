# Round 3 Eval Analysis - Common Problems

**Run ID**: `20260304_152742` | **Date**: 2026-03-05 | **Model**: qwen3.5
**Tasks**: 25 evaluated | **Pass Rate**: 19/25 = 76.0%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| MarkorAddNoteHeader | 1.0 | 27 | Success |
| MarkorEditNote | 1.0 | 9 | Success |
| NotesIsTodo | 1.0 | 2 | Success |
| NotesTodoItemCount | 1.0 | 4 | Success |
| RecipeAddMultipleRecipesFromMarkor2 | 1.0 | 48 | Success |
| RecipeDeleteDuplicateRecipes3 | 0.0 | 30 | TurnExhaustion |
| RecipeDeleteSingleWithRecipeWithNoise | 1.0 | 8 | Success |
| RetroPlaylistDuration | 0.0 | 30 | TurnExhaustion |
| SimpleCalendarEventsInTimeRange | 1.0 | 5 | Success |
| SimpleCalendarFirstEventAfterStartTime | 1.0 | 9 | Success |
| SportsTrackerActivitiesCountForWeek | 0.0 | 0 | InfraError |
| SportsTrackerActivityDuration | 1.0 | 10 | Success |
| SportsTrackerLongestDistanceActivity | 1.0 | 30 | Success |
| SportsTrackerTotalDistanceForCategoryOverInterval | 1.0 | 28 | Success |
| SportsTrackerTotalDurationForCategoryThisWeek | 1.0 | 16 | Success |
| SystemBluetoothTurnOffVerify | 1.0 | 11 | Success |
| SystemBluetoothTurnOnVerify | 1.0 | 5 | Success |
| SystemBrightnessMaxVerify | 1.0 | 8 | Success |
| SystemBrightnessMinVerify | 1.0 | 4 | Success |
| SystemWifiTurnOffVerify | 1.0 | 3 | Success |
| SystemWifiTurnOnVerify | 1.0 | 4 | Success |
| TasksCompletedTasksForDate | 0.0 | 30 | FalseCompletion |
| TasksDueNextWeek | 0.0 | 6 | FalseCompletion |
| TasksHighPriorityTasksDueOnDate | 1.0 | 21 | Success |
| TasksIncompleteTasksOnDate | 0.0 | 2 | FalseCompletion |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| FalseCompletion | 3 | TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate |
| InfraError | 1 | SportsTrackerActivitiesCountForWeek |
| Success | 19 | MarkorAddNoteHeader, MarkorEditNote, NotesIsTodo, NotesTodoItemCount, RecipeAddMultipleRecipesFromMarkor2, RecipeDeleteSingleWithRecipeWithNoise, SimpleCalendarEventsInTimeRange, SimpleCalendarFirstEventAfterStartTime, SportsTrackerActivityDuration, SportsTrackerLongestDistanceActivity, SportsTrackerTotalDistanceForCategoryOverInterval, SportsTrackerTotalDurationForCategoryThisWeek, SystemBluetoothTurnOffVerify, SystemBluetoothTurnOnVerify, SystemBrightnessMaxVerify, SystemBrightnessMinVerify, SystemWifiTurnOffVerify, SystemWifiTurnOnVerify, TasksHighPriorityTasksDueOnDate |
| TurnExhaustion | 2 | RecipeDeleteDuplicateRecipes3, RetroPlaylistDuration |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| Markor blank-line/cursor guidance | Working | `MarkorAddNoteHeader` moved from 0.0 to 1.0 (15->27 turns). |
| Markor edit path stability | Stable | `MarkorEditNote` stayed pass (1.0), with small turn increase (7->9). |
| Retro duration strategy prompting | Ineffective | `RetroPlaylistDuration` still failed at 30 turns. |

## Common Problems

### P1: Premature Completion In Tasks IR - HIGH - 3 tasks

**Affected**: TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate

**Root Cause**: The agent issues `complete_task` before building date-filtered, completion-state-grounded evidence. It often counts visible rows or infers from partial context, then submits a confident but unverified answer.

**Proposed Fix**:
- Add a Tasks-IR completion guardrail requiring explicit filter/date interaction plus evidence rows before `complete_task`.
- Add a self-check prompt clause for count/list questions: verify scope boundaries (date window, completion status) before final answer.

---

### P2: Long-Horizon Turn Inefficiency - HIGH - 4 tasks

**Affected**: RecipeDeleteDuplicateRecipes3, RetroPlaylistDuration, SportsTrackerLongestDistanceActivity, SportsTrackerTotalDistanceForCategoryOverInterval

**Root Cause**: The agent uses correct local actions but spends too many turns on exploratory loops (extra back/search cycles, shell detours, repeated recounting). On hard tasks this causes turn exhaustion; on passes it creates fragile max-turn behavior.

**Proposed Fix**:
- Add per-domain loop policies (dedup rhythm, incremental playlist duration loop, fixed checklist for sports aggregation) with stop conditions.
- Penalize shell detours when equivalent UI evidence path exists.

---

### P3: Session Finalization Timeout Gap - MED - 1 task

**Affected**: SportsTrackerActivitiesCountForWeek

**Root Cause**: Trace shows substantial action history but no final `session_stopped` and no run summary artifact, while eval marks bridge timeout. This points to completion signaling/monitoring fragility in timeout-edge runs.

**Proposed Fix**:
- Add instrumentation around completion monitor for missing `session_stopped` and emit explicit timeout diagnosis artifacts.
- Add fail-safe flush of run summary on forced stop.

## Next Steps

### Must Fix
1. **Tasks IR completion guardrail**: block premature `complete_task` until scoped evidence is present.
- Targets: TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate
- Implementation: cognition completion policy + Tasks-domain prompt constraints
2. **Turn-efficiency policies for long workflows**: domain-specific loop plans with explicit stop conditions.
- Targets: RecipeDeleteDuplicateRecipes3, RetroPlaylistDuration, SportsTrackerLongestDistanceActivity, SportsTrackerTotalDistanceForCategoryOverInterval
- Implementation: policy hints in prompt assembly and todo/checkpoint templates

### Should Fix
3. **Timeout finalization observability**: guarantee timeout runs emit completion diagnostics.
- Targets: SportsTrackerActivitiesCountForWeek
- Implementation: completion monitor + trace/run_summary flush safeguards

### Accept as Capability Gap
- None in this round; failures are addressable with policy and runtime fixes.

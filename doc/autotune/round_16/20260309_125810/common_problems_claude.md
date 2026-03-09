# Round 16 Eval Analysis — Common Problems

**Run ID**: `20260309_125810` | **Date**: 2026-03-09 | **Model**: gpt-5.4
**Tasks**: 7 evaluated | **Pass Rate**: 4/7 = 57%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| MarkorAddNoteHeader | 1.0 | 13 | Success |
| SimpleCalendarEventsInNextWeek | 1.0 | 5 | Success |
| TasksCompletedTasksForDate | 1.0 | 11 | Success (NEW pass) |
| TasksDueNextWeek | 1.0 | 9 | Success (NEW pass) |
| SportsTrackerTotalDistanceForCategoryOverInterval | 0.0 | 24 | ReasoningError (date mapping) |
| TasksHighPriorityTasksDueOnDate | 0.0 | 15 | FalseCompletion (incomplete scan) |
| TasksIncompleteTasksOnDate | 0.0 | 8 | ReasoningError (date label confusion) |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 4 | MarkorAddNoteHeader, SimpleCalendarEventsInNextWeek, TasksCompletedTasksForDate, TasksDueNextWeek |
| ReasoningError | 2 | SportsTrackerTotalDistanceForCategoryOverInterval, TasksIncompleteTasksOnDate |
| FalseCompletion | 1 | TasksHighPriorityTasksDueOnDate |

## Previous Round Comparison

| Change Applied | Status | Impact |
|----------------|--------|--------|
| Model switch: qwen3.5 → gpt-5.4 | Effective | 2 new passes (TasksCompletedTasksForDate, TasksDueNextWeek) — both were stuck with qwen3.5 |
| adb reverse port forwarding | Required | localhost:18080 proxy unreachable from emulator without it |

### Model Switch Impact
- TasksCompletedTasksForDate: R14 failed with 402 (credits), R13 failed (toggle click issue). gpt-5.4 passed in 11 turns with 2 tool failures but recovered.
- TasksDueNextWeek: R14 failed with 402, R13 failed (off-by-1 count). gpt-5.4 counted correctly in 9 turns.
- MarkorAddNoteHeader: Passed in R11 with qwen3.5, confirmed passing with gpt-5.4.
- SimpleCalendarEventsInNextWeek: Passed in R11 with qwen3.5, confirmed passing with gpt-5.4.

## Common Problems

### P1: Date reasoning with relative labels — HIGH — 2 tasks

**Affected**: SportsTrackerTotalDistanceForCategoryOverInterval, TasksIncompleteTasksOnDate

**Root Cause**: Agent confuses relative date labels. SportsTracker: "Yesterday" (Oct 14) treated as target date instead of "Tuesday" (Oct 10). Tasks: "Tmrw" chip text in concatenated a11y tree used as due date instead of actual "Sat" label.

**Proposed Fix**:
- OpenTracks skill: add "Map relative day labels to absolute dates before filtering. 'Yesterday' is always device_date - 1, not a synonym for the target range."
- Tasks skill: add "The due date is the standalone label at top-right of the task row. Chips/tags below the title are subtask reminders, not primary due dates."

### P2: Incomplete list scan before answering — MED — 1 task

**Affected**: TasksHighPriorityTasksDueOnDate

**Root Cause**: Agent found 3 matching tasks in initial viewport, checked priorities, and answered without scrolling. The correct answer (dynamically-created task) was below the fold.

**Proposed Fix**:
- Tasks skill: add "Always scroll to the end of the task list before answering info-retrieval queries. The list is often longer than one screen."

## Next Steps

### Must Fix
1. **OpenTracks date-mapping guidance**: Add absolute date resolution instruction to SKILL.md
   - Targets: SportsTrackerTotalDistanceForCategoryOverInterval
   - Implementation: `app/src/main/assets/app_skills/de.dennisguse.opentracks/SKILL.md`
   - Owner layer: app skill: de.dennisguse.opentracks

2. **Tasks date-label disambiguation**: Add due-date position guidance to SKILL.md
   - Targets: TasksIncompleteTasksOnDate
   - Implementation: `app/src/main/assets/app_skills/org.tasks/SKILL.md`
   - Owner layer: app skill: org.tasks

3. **Tasks mandatory scroll-to-bottom**: Add scroll-before-answer rule
   - Targets: TasksHighPriorityTasksDueOnDate
   - Implementation: `app/src/main/assets/app_skills/org.tasks/SKILL.md`
   - Owner layer: app skill: org.tasks

### Accept as Capability Gap
- None. All 3 failures have clear, targeted fixes.

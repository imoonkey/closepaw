# Round 42 Eval Analysis — Common Problems

**Run ID**: `20260312_185423` | **Date**: 2026-03-12 | **Model**: gpt-5.4
**Tasks**: 8 evaluated (targeted) | **Pass Rate**: 4/8 = 50.0%

## Scorecard

| Task | Score | Turns | Root Cause | R41 Score | Delta |
|------|-------|-------|------------|-----------|-------|
| FilesMoveFile | 1.0 | 19 | Success (regression canary) | 1.0 | = |
| MarkorMergeNotes | 1.0 | 19 | Success (poisoned memory fixed) | 0.0 | **RECOVERED** |
| MarkorMoveNote | 0.0 | 9 | GroundingError (wrong substring match) | 0.0 | = |
| SimpleCalendarAddOneEventRelativeDay | 1.0 | 17 | Success ("Nh" MUST language worked) | 0.0 | **RECOVERED** |
| SimpleCalendarEventsInNextWeek | 1.0 | 5 | Success (date computation rule worked) | 0.0 | **RECOVERED** |
| SportsTrackerActivitiesCountForWeek | 0.0 | 24 | ReasoningError (checked 3/14 tracks) | 0.0 | = (improved behavior) |
| SportsTrackerTotalDistanceForCategoryOverInterval | 0.0 | 37 | FalseCompletion (no scroll + a11y gap on Edit) | 0.0 | = (improved behavior) |
| TasksDueNextWeek | 0.0 | 8 | ReasoningError (duplicate counting) | 0.0 | = (regression: 14 vs 13) |

### Summary: 3 recovered, 0 regressions, 4 still failing

## Fix Impact Analysis

| Fix Applied in R42 | Status | Targets |
|---------------------|--------|---------|
| Memory cleanup (delete poisoned Markor memory) | **SUCCESS** | MarkorMergeNotes: 0.0 → 1.0 |
| Smart Filtering → exhaustive-check (OpenTracks) | **PARTIAL** | CountForWeek: agent now uses Edit (was name-based), but stops at 3/14. TotalDistance: checks 5/5 visible (was 2/5), but doesn't scroll. |
| Date computation rule (system prompt) | **SUCCESS** | EventsInNextWeek: 0.0 → 1.0. TasksDueNextWeek: date range correct, but duplicate counting unchanged. |
| Markor file selection (substring matching) | **PARTIAL** | MarkorMoveNote: agent references skill, but picks first match among 3 candidates. |
| SimpleCalendar "Nh" imperative (MUST/ALWAYS) | **SUCCESS** | AddOneEventRelativeDay: 0.0 → 1.0 |
| Documentsui skill | **NO EFFECT** | FilesMoveFile passed via model-native (regression canary). |

## Common Problems

### P1: SportsTracker Incomplete Scan — HIGH — 2 tasks

**Affected**: SportsTrackerActivitiesCountForWeek, SportsTrackerTotalDistanceForCategoryOverInterval

**Root Cause**: Both tasks require checking ALL tracks in a date range. The R42 skill change ("open Edit for ALL tracks") successfully changed behavior — agents now use Edit instead of name-based inference. But:
- CountForWeek: checked 3/14, then stopped despite 26 remaining turns
- TotalDistance: checked 5/5 *visible*, never scrolled to find more

**Additional issue for TotalDistance**: OpenTracks Edit form has a complete a11y tree failure — zero form fields exposed. Agent hallucinated all 5 activity types from track names (happened to be correct, but fragile).

**Proposed Fix**:
- Strengthen OpenTracks scroll section: "BEFORE counting tracks in a date range, scroll the entire list end-to-end. Do not start checking until you have seen all tracks."
- Add to Counting section: "Keep a checklist in scratchpad. Mark each track as checked. Do NOT complete until all tracks are marked."
- Note: CountForWeek requires 14 tracks × 6 turns = 84 turns, exceeding 50-turn budget. Even with perfect behavior, this may be structurally infeasible.

---

### P2: TasksDueNextWeek Duplicate Counting — HIGH — 1 task

**Affected**: TasksDueNextWeek

**Root Cause**: Tasks.org shows recurring tasks as separate rows. Agent counted 14 rows with next-week date labels instead of 6 unique tasks. Same failure as R41 (answer=13), slightly worse (answer=14). The R42 date computation rule had no effect — the agent already computed dates correctly in R41.

**Proposed Fix**:
- Add to Tasks.org skill: "Tasks.org shows recurring/sub-task instances as separate rows. When counting: deduplicate by task name. Count each unique name only once."
- Add: "Day-of-week labels are relative to today. Use position relative to 'Today' marker to determine which week."
- Cost: ~30 tokens. Passes tuning gates: any real user counting tasks encounters recurring items.

---

### P3: MarkorMoveNote Multiple Substring Matches — MED — 1 task

**Affected**: MarkorMoveNote (3rd consecutive failure)

**Root Cause**: R42 added substring matching guidance. Agent acknowledged it but picked the first match among 3 files containing "shy_king_copy" as substring. The files had different prefixes: `2023_02_13_`, `BlJB_`, `final_`. The correct one had the randomized prefix `BlJB_`.

**Assessment**: Adding "prefer randomized prefix" disambiguation would be eval-specific — real Markor users don't encounter randomized-prefix filenames. The substring matching guidance itself is general, but disambiguating among multiple matches with a preference for "4 mixed-case alphanumeric characters" is tuned to the eval's data generation pattern.

**Proposed**: Park this task. The substring matching guidance was a reasonable general addition, but further disambiguation would violate tuning_principles.md anti-overfit gate. Add to cannot_handle with rationale.

## Next Steps

### Must Fix (R43)
1. **Add Tasks.org deduplication guidance**: Add counting deduplication + position-relative date disambiguation to Tasks.org skill. → Targets: TasksDueNextWeek
2. **Strengthen OpenTracks scroll-first + checklist**: Add pre-scan scroll requirement and scratchpad checklist protocol to OpenTracks skill. → Targets: SportsTrackerTotalDistanceForCategoryOverInterval

### Consider for cannot_handle
3. **SportsTrackerActivitiesCountForWeek**: 14 tracks × 6 turns = 84 turns, structurally exceeds 50-turn budget. Even with perfect skill compliance, would need to check all 14 tracks. After R42 partial improvement (3 consecutive rounds), park.
4. **MarkorMoveNote**: 3 consecutive failures. Remaining fix would be eval-specific. Park.
5. **SportsTrackerTotalDistanceForCategoryOverInterval**: OpenTracks Edit form a11y tree is empty. Agent must hallucinate activity types or use hybrid mode. If scroll-first fix doesn't help in R43, park.

### Token delta
- Always-on: 2,531 → 2,576 (+45, +1.8%)
- App skills total: 3,122 → 3,254 (+132)

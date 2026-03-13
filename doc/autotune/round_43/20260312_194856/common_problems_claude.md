# Round 43 Eval Analysis — Common Problems

**Run ID**: `20260312_194856` | **Date**: 2026-03-12 | **Model**: gpt-5.4
**Tasks**: 6 evaluated (targeted) | **Pass Rate**: 3/6 = 50.0%

## Scorecard

| Task | Score | Turns | Root Cause | R42 Score | Delta |
|------|-------|-------|------------|-----------|-------|
| FilesMoveFile | 1.0 | 18 | Success (regression canary) | 1.0 | = |
| MarkorMergeNotes | 1.0 | 26 | Success (stable) | 1.0 | = |
| SimpleCalendarAddOneEventRelativeDay | 1.0 | 29 | Success (stable) | 1.0 | = |
| SimpleCalendarEventsInNextWeek | 0.0 | 10 | Nondeterministic regression | 1.0 | **REGRESSED** |
| SportsTrackerTotalDistanceForCategoryOverInterval | 0.0 | 35 | SkippedScroll (step 1 of protocol ignored) | 0.0 | = |
| TasksDueNextWeek | 0.0 | 7 | ScratchpadTruncation + incomplete dedup | 0.0 | = (partial improvement) |

### Summary: 0 recovered, 1 nondeterministic regression, 2 still failing

## Fix Impact Analysis

| Fix Applied in R43 | Status | Targets |
|---------------------|--------|---------|
| Tasks.org deduplication guidance | **PARTIAL** | TasksDueNextWeek: agent used dedup in scratchpad (8 unique tasks), but scratchpad was truncated at answer time. Agent abandoned its own count and submitted 13. |
| OpenTracks scroll-first checklist protocol | **FAILED** | TotalDistance: agent followed steps 2-4 but skipped step 1 (scroll). Checked all 5 visible tracks but never scrolled for more. |

## Common Problems

### P1: OpenTracks Scroll-First Instruction Ignored — HIGH — 1 task

**Affected**: SportsTrackerTotalDistanceForCategoryOverInterval

**Root Cause**: The 4-step scroll-first protocol was injected and acknowledged. Agent followed steps 2-4 perfectly but skipped step 1. Agent's todo paraphrased "scroll as needed" instead of the mandatory "FIRST scroll." The agent treated visible tracks (5) as the complete list despite the RecyclerView being marked scrollable and the last track being clipped at the scroll boundary.

**Assessment**: After 3 consecutive rounds of improvement (R41: 2/5 checked, R42: 5/5 visible, R43: 5/5 with checklist protocol) without achieving scroll compliance, and with the additional a11y gap on Edit screens (zero form fields exposed), this task should be parked. The scroll-first instruction at increasing urgency levels has failed 3 times. The a11y gap makes even a correct scan produce hallucinated activity types.

**Proposed**: Park SportsTrackerTotalDistanceForCategoryOverInterval. 3 consecutive failures with improving but insufficient behavior + a11y gap on Edit form.

---

### P2: TasksDueNextWeek Scratchpad Truncation — HIGH — 1 task

**Affected**: TasksDueNextWeek

**Root Cause**: The dedup guidance WAS followed — agent wrote 8 unique task names to scratchpad in turn 5. But when reading scratchpad at answer time (turn 7), the `next_week_unique_seen` field was truncated to ~200 chars: `"...Send report to manager:Sat(202... // truncated, 330 chars"`. Agent couldn't see its own deduplicated list and fell back to counting raw visible rows (13).

**Additional issue**: Even the 8 unique names from scratchpad are wrong (correct is 6). Some day-of-week labels deep in the list after "Oct 22" refer to the FOLLOWING week, not next week. The agent included tasks like "Back Up Computer Files (Fri)" which may be Oct 27, not Oct 20.

**Assessment**: Scratchpad truncation is a platform-level bug that undermines any working-memory-dependent strategy. The dedup guidance is directionally correct but needs: (1) scratchpad truncation fix, (2) date resolution guidance for chronological position.

**Proposed**:
- Investigate scratchpad truncation — is there a char limit? This is critical infrastructure.
- Keep dedup guidance, add post-scroll reconciliation step.
- If platform fix is not feasible, consider parking this task.

---

### P3: SimpleCalendarEventsInNextWeek Nondeterministic Regression — LOW — 1 task

**Affected**: SimpleCalendarEventsInNextWeek

**Root Cause**: Agent had correct answer in scratchpad ("Product demo, Movie night") but scrolled up to check for earlier events, saw "Meeting with Bob" with an off-screen date header, and included it. No R43 changes affected this task — pure model nondeterminism.

**Assessment**: Not actionable. The correct answer was already recorded. The model overrode its own scratchpad with ambiguous visual data. This is the same "scratchpad override by visual recency" pattern seen in other tasks. A general "cross-check new observations against your scratchpad" hint could help but risks token bloat.

**Proposed**: Accept as nondeterministic. The R42 date computation rule worked; this failure is model variance.

## Next Steps

### Must Fix (R44)
1. **Investigate scratchpad truncation**: Platform bug. Check if there's a char limit on scratchpad values in the working memory display. → Targets: TasksDueNextWeek (and any task using scratchpad for working memory)

### Consider for cannot_handle
2. **SportsTrackerTotalDistanceForCategoryOverInterval**: 3 consecutive improvement rounds without achieving scroll compliance + a11y gap. Park.
3. **TasksDueNextWeek**: If scratchpad truncation is a platform constraint (not easily fixable), park. Even with perfect dedup, date resolution across recurring instances is fragile.

### Accept as nondeterministic
4. **SimpleCalendarEventsInNextWeek**: R42 fix worked. R43 failure is model variance. No code change needed.

### Token delta
- Always-on: 2,576 → 2,576 (0, unchanged)
- App skills total: 3,254 → 3,384 (+130)

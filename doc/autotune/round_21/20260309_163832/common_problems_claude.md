# R21 Common Problems — claude

## Summary

Round 21 focused on the last 3 active tasks from the R8/R9 failure union. Applied priority scale correction (4th=High, not 1st), due date field disambiguation in Tasks.org detail view, extension CRITICAL promotion in Markor skill, and new Pro Expense skill.

| Task | Score | Turns | Delta |
|---|---|---|---|
| MarkorMergeNotes | 1.0 | 20 | 0.0→1.0 |
| TasksHighPriorityTasksDueOnDate | 1.0 | 26 | 0.0→1.0 |
| ExpenseAddMultipleFromGallery | 0.0 | 28 | 0.0→0.0 (parked) |

## Root Causes

### 1. Priority scale inverted since R18 (FIXED)
- R18 documented "1st/leftmost = High, 4th/rightmost = None" — this was WRONG
- Traced back by comparing R16 passing trace (4th=High, correct answer) vs R20 failing trace (1st=High, wrong answer)
- Fixed: corrected to "1st=None, 4th=High"

### 2. Due date field misread in detail view (FIXED)
- Task detail shows "Saturday, October 7" (due date) and "Wednesday" (start date) as separate fields
- Agent read "Wednesday" = Oct 18, but actual due date is Oct 7
- Fixed: skill now instructs to use full-format date line, ignore bare day name

### 3. Markor extension not cleared (FIXED in R21 initial run)
- Extension CRITICAL section with concrete example was effective
- MarkorMergeNotes passed on first try after this fix

### 4. ExpenseAddMultipleFromGallery — capability gap (PARKED)
- 12 consecutive failures (0/12 all-time)
- Requires: reading expense image → extracting 6+ line items → entering each in Pro Expense with correct category → scrolling horizontal category row
- Turn budget (30) insufficient for 6+ expenses × 5+ turns each
- Image perception unreliable for small text in photos

## Next Steps

**None — all active tasks are resolved or parked.** The active backlog is empty.

## Recommendations for Future Work

If the loop is extended to new task sets:
1. Multi-action batching: GPT-5.4 rarely batches tool calls. Few-shot examples could reduce turns.
2. Image perception: Hybrid mode helps but small text in photos remains unreliable.
3. Horizontal scroll: Pro Expense category scroll instruction was added but never tested with a passing run.

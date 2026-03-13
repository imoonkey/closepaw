# TasksDueNextWeek — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 8/30 | **Reason**: GoalAchieved (false completion) | **Duration**: 82s

**Goal**: Count tasks due next week (Mon-Sun) in Tasks app. Answer as single integer.

**Agent answer**: 14 | **Correct**: 6

## Root Cause

**Category**: ReasoningError

The agent correctly computed the date range (Mon Oct 16 - Sun Oct 22), wrote it to scratchpad, scrolled the full list, and used scratchpad to track findings. However, it counted 14 tasks by treating every visible row with a next-week date label as a distinct task, when Tasks.org shows the same task name multiple times (recurring instances, sub-tasks, or list-section duplicates). The correct count is 6 unique tasks. This is the same class of error as R41 (answer=13) but worse — R42 found one additional item ("Follow up on support tickets — Tmrw") that R41 excluded.

## R42 Prompt Change Evaluation

R42 added: "For relative date queries ('next week', 'this month'): compute the exact date range first, write it to scratchpad, then filter. 'Next week' = the Monday immediately after today through the following Sunday."

The agent **did follow this** — it computed the range in Turn 1 and wrote it to scratchpad. But this guidance addresses R39's failure mode (no date computation at all), not R41/R42's failure mode (duplicate counting). The prompt change had no effect on the actual root cause.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `write_todos` + `scratchpad(write: "2023-10-16 to 2023-10-22")` | N (no UI action) | G | G | G | Date range correct. Good use of scratchpad. |
| 2 | `open_app("Tasks")` | Y | G | G | G | Tasks.org opens, 65 elements visible |
| 3 | `mobile_action(scroll down, elem 11)` | Y | G | G | G | Scrolls past "Today" marker into future tasks |
| 4 | `scratchpad(write: 8 tasks)` | N (no UI action) | B | G | G | Records 8 next-week tasks, includes duplicates |
| 5 | `mobile_action(scroll down, elem 9)` | Y | G | G | G | Scrolls further, reveals more tasks |
| 6 | `scratchpad(write: 14 tasks)` | N (no UI action) | B | G | G | Updates to 14 total, still includes duplicates |
| 7 | `mobile_action(scroll down, elem 9)` | N | G | G | G | No change = end of list confirmed |
| 8 | `complete_task(answer="14")` | N/A | B | — | — | False completion: 14 vs correct 6 |

### Key Turns

**Turn 4** — First scratchpad write with task inventory:
- **What was on screen**: Post-first-scroll view showing tasks from "Follow up on support tickets (Tmrw)" through "Organize files and folders (Oct 22)" and into "Call Grandma (Wed)" and "Brainstorm Blog Post Ideas (Thu)"
- **What agent decided**: Recorded 8 tasks: Plan family reunion (Tue), Book flights for conference (Wed), Call Grandma (Wed), Brainstorm Blog Post Ideas (Thu), Research market trends (Fri), Create Budget for Next Month (Fri), Send report to manager (Sat), Organize files and folders (Oct 22)
- **Problem**: "Call Grandma (Wed)" appears multiple times in the full list — once before "Today" (Oct 11, past week) and once after (Oct 18, next week). "Create Budget for Next Month (Fri)" appears before "Today" with label "Fri" meaning Oct 13 (past), but the agent included it as next-week. The agent treated date labels as unambiguous next-week dates without verifying position relative to the "Today" divider.

**Turn 6** — Updated scratchpad to 14 tasks:
- **Agent's full list**: Water Plants Mon; Attend Team Meeting Tue; Plan family reunion Tue; Book flights for conference Wed; Call Grandma Wed; Reply to Emails Wed; Brainstorm Blog Post Ideas Thu; Read 'The Martian' Thu; Create Budget for Next Month Fri; Research market trends Fri; Back Up Computer Files Fri; Renew Driver's License Fri; Send report to manager Sat; Organize files and folders Oct 22
- **Problem**: Several of these task names appear in the a11y tree both before AND after the "Today" marker with different date labels. The agent added all post-scroll instances without deduplicating against what it already saw. Looking at the a11y trees:
  - "Call Grandma" appears 4+ times total (Oct 11 Wed, Oct 12 Thu, Today, Oct 18 Wed)
  - "Water Plants" appears 3+ times (Oct 12 Thu, Yest, Mon Oct 16)
  - "Reply to Emails" appears 2+ times (Fri Oct 13, Wed Oct 18)
  - "Read 'The Martian'" appears 2+ times (Today, Thu Oct 19)
  - "Create Budget for Next Month" appears with "Fri" — in the pre-scroll view at position BEFORE the "Today" marker, meaning Fri Oct 13 (past), NOT next week
- The agent counted every instance with a next-week-compatible day-of-week label as a separate next-week task, even when some are past-week tasks with the same day abbreviation.

**Turn 8** — False completion:
- The agent submitted 14 after confirming end-of-list. No cross-check against the scratchpad for duplicates or date disambiguation.

## Actual Task List Reconstruction

From the a11y trees across all scroll positions, the sorted list structure is:

**Pre-Today (overdue/past):**
- Finish Project Proposal — Oct 8
- Finish Project Proposal — Oct 8
- Finish Project Proposal — Fri (Oct 13)
- Call Grandma — Wed (Oct 11)
- Call Grandma — Thu (Oct 12)
- Water Plants — Thu (Oct 12)
- Call Grandma — Today (Oct 15)
- Reply to Emails — Fri (Oct 13)
- Water Plants — Yest (Oct 14)
- Create Budget for Next Month — Fri (Oct 13)
- Read 'The Martian' — Today (Oct 15)

**Post-Today (future):**
- Follow up on support tickets — Tmrw (Mon Oct 16)
- Plan family reunion — Tue (Oct 17)
- Book flights for conference — Wed (Oct 18)
- Research market trends — Fri (Oct 20)
- Send report to manager — Sat (Oct 21)
- Organize files and folders — Oct 22
- Call Grandma — Wed (Oct 18) *[duplicate name]*
- Brainstorm Blog Post Ideas — Thu (Oct 19)
- Read 'The Martian' — Thu (Oct 19) *[duplicate name]*
- Back Up Computer Files — Fri (Oct 20)
- Attend Team Meeting — Tue (Oct 17)
- Water Plants — Mon (Oct 16) *[duplicate name]*
- Reply to Emails — Wed (Oct 18) *[duplicate name]*
- Renew Driver's License — Fri (Oct 20)
- Grocery Shopping — Today (Oct 15) *[not next week]*

The 6 correct unique next-week tasks (matching R41 analysis and R16 pass) are likely the non-duplicate entries: Follow up on support tickets, Plan family reunion, Book flights for conference, Research market trends, Send report to manager, Organize files and folders. The remaining entries are either duplicates of these or repeat names of tasks that also appear in the past-week section.

## Comparison: R39 vs R41 vs R42

| Dimension | R39 (answer=9) | R41 (answer=13) | R42 (answer=14) |
|-----------|---------------|-----------------|-----------------|
| Scrolling | None | Full (3 scrolls) | Full (3 scrolls) |
| Scratchpad use | None | 2 writes | 3 writes |
| Date range computation | None | Correct | Correct (Turn 1) |
| Duplicate detection | None | None | None |
| Core error | Premature answer | Duplicate counting | Duplicate counting (worse) |

R42 is a **regression from R41**: same failure mode, but the count went UP from 13 to 14 (added "Follow up on support tickets" which R41 excluded). The R42 prompt change ("compute date range first, write to scratchpad") did not address the duplicate counting issue at all.

## What Worked

- Date range computation was immediate and correct (Mon Oct 16 - Sun Oct 22)
- Scratchpad was used proactively (3 writes across the session)
- Full list scrolling with end-of-list confirmation
- Efficient turn usage (8 turns, well within budget)

## What Didn't Work

- Agent counted 14 tasks instead of 6 — every visible row with a next-week date label was counted as a separate task
- No deduplication: task names like "Call Grandma", "Water Plants", "Reply to Emails" appear multiple times in the list and were counted separately
- "Create Budget for Next Month" with "Fri" label was included despite appearing BEFORE the "Today" marker (meaning Fri Oct 13, not Oct 20)
- The R42 prompt addition had zero impact on the actual root cause

## Recommendations

1. **Deduplication guidance in app skill**: Add to Tasks.org SKILL.md: "Tasks.org shows recurring/sub-task instances as separate rows. When counting tasks in a date range, deduplicate by task name — count each unique name only once."
2. **Position-relative date disambiguation**: Add: "Day-of-week labels (Mon, Tue, etc.) are relative to today. Use position in the sorted list relative to 'Today'/'Tmrw' markers to determine which week a label refers to. Only count tasks that appear AFTER the 'Today' or 'Tmrw' marker AND have date labels within the target range."
3. **Cross-check protocol**: The agent should count its scratchpad list items and verify: (a) no duplicate task names, (b) each task's date label is unambiguously in the target range based on list position.
4. **Prompt fix priority**: The R42 prompt change addressed the wrong failure mode. The next prompt change must target duplicate counting, not date range computation (which was already working since R41).

# TasksDueNextWeek — R41 (20260312_173354) — FAIL

**Score**: 0.0 | **Turns**: 6/30 | **Stop**: GoalAchieved | **Answer**: 13 (correct: 6)

## Context

This task was targeted by the R41 info-extraction verification fix:
> "For info-extraction: scroll the full list, cross-check totals against scratchpad, verify date boundaries before answering."

R39 was a 5-turn FalseCompletion where the agent gave a premature answer (9) without scrolling or verifying. The fix aimed to enforce full-list scrolling, scratchpad tracking, and date boundary verification.

## Turn-by-Turn Trace

| Turn | Tool(s) | Screen State | What Happened |
|------|---------|-------------|---------------|
| 1 | open_app("Tasks") | Agent home screen -> Tasks.org opens | Correct app launch. 65 elements visible. |
| 2 | mobile_action(scroll down, element 11) | Tasks.org list with first batch | Agent scrolls down, citing "must not answer without scanning the whole list." Good. |
| 3 | scratchpad(write) + mobile_action(scroll down) | Post-scroll view with mid-list tasks | Agent records 7 next-week candidates and continues scrolling. |
| 4 | scratchpad(write) | Post-scroll view with lower-list tasks | Agent updates scratchpad to 13 final candidates. Computes date range: "Mon Oct 16 through Sun Oct 22." |
| 5 | mobile_action(scroll down) | Same view (bottom of list) | Scroll fails — "no observable effect." Confirms end of list reached. |
| 6 | complete_task(answer="13") | Same view | Agent submits answer 13. |

## Did the Agent Follow the R41 Guidance?

### Scroll the full list?
**YES.** The agent scrolled 3 times (turns 2, 3, 5), and the third scroll failed with "no observable effect," confirming end-of-list. This is a major improvement over R39 (0 scrolls) and the agent explicitly cited the guidance: "must not answer without scanning the whole list."

### Use scratchpad to track findings?
**YES.** Two scratchpad writes (turns 3 and 4). Turn 3 stored 7 initial candidates; turn 4 updated to 13 final candidates. The scratchpad was used as intended.

### Cross-check totals against scratchpad?
**PARTIAL.** The agent counted the items in its scratchpad (13) and submitted that count. But it did not cross-check whether all 13 items genuinely fall within the next-week date range. The count matched the scratchpad content, but the scratchpad content itself was wrong.

### Verify date boundaries before answering?
**FAILED.** The agent correctly computed "Mon Oct 16 through Sun Oct 22" (device date is Sun Oct 15). However, it did NOT verify which side of the date boundary each task falls on. This is the core failure.

## Root Cause: Ambiguous Relative Date Labels

Tasks.org shows relative date labels: "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Today", "Tmrw", "Yest", or absolute dates like "Oct 22." The list is sorted chronologically by due date. The same label "Wed" can mean either Oct 11 (this past Wednesday) or Oct 18 (next Wednesday), depending on position relative to "Today" in the sorted list.

### What the agent saw (reconstructed from a11y trees):

**Before "Today" in the sorted list (this week / overdue):**
- Finish Project Proposal — Oct 8 (past)
- Finish Project Proposal — Oct 8 (past)
- Finish Project Proposal — Fri (Oct 13, past)
- Call Grandma — Wed (Oct 11, past)
- Call Grandma — Thu (Oct 12, past)
- Water Plants — Thu (Oct 12, past)
- Call Grandma — Today (Oct 15)
- Reply to Emails — Fri (Oct 13, past)
- Water Plants — Yest (Oct 14, past)
- Create Budget for Next Month — Fri (Oct 13, past)
- Read The Martian — Today (Oct 15)

**After "Today"/"Tmrw" in the sorted list (next week):**
- Follow up on support tickets — Tmrw (Mon Oct 16)
- Plan family reunion — Tue (Oct 17)
- Book flights for conference — Wed (Oct 18)
- Research market trends — Fri (Oct 20)
- Send report to manager — Sat (Oct 21)
- Organize files and folders — Oct 22 (Sun)

**Further down (also next week, new items after scrolling):**
- Call Grandma — Wed (Oct 18)
- Brainstorm Blog Post Ideas — Thu (Oct 19)
- Read The Martian — Thu (Oct 19)
- Back Up Computer Files — Fri (Oct 20)
- Attend Team Meeting — Tue (Oct 17)
- Water Plants — Mon (Oct 16)
- Reply to Emails — Wed (Oct 18)
- Renew Driver's License — Fri (Oct 20)
- Grocery Shopping — Today (Oct 15)

### Agent's scratchpad (turn 4):
> Tue Plan family reunion; Wed Book flights for conference; Fri Research market trends; Sat Send report to manager; Sun Organize files and folders (Oct 22); Mon Water Plants; Tue Attend Team Meeting; Wed Call Grandma; Wed Reply to Emails; Thu Brainstorm Blog Post Ideas; Thu Read The Martian; Fri Back Up Computer Files; Fri Renew Driver's License

The agent counted 13 unique tasks appearing after the "Today"/"Tmrw" boundary in the scrolled list. However, this count is wrong because **the correct answer is 6.**

## Why 6, Not 13?

The expected answer of 6 (validated by R16 pass) implies that many of the tasks the agent counted as "next week" are actually duplicates or tasks that should not be counted. Looking at the a11y tree, Tasks.org shows:
- The same task can appear multiple times in the list (e.g., "Finish Project Proposal" appears 3 times with different date labels; "Call Grandma" appears 4+ times). These are likely sub-tasks, recurring instances, or different views of the same task.
- The agent did not distinguish between unique tasks and duplicate entries of the same task.
- The agent also did not open task detail views to verify actual due dates, relying solely on the relative day labels in the list view.

The agent's 13-count includes duplicate task names (Call Grandma appears again, Reply to Emails appears again, Read The Martian appears again, Water Plants appears again) that are likely recurring instances or the same task shown in different list sections. The correct 6 unique next-week tasks are the ones that appear only in the post-"Tmrw" section with no prior-week counterpart.

## Comparison to R39

| Dimension | R39 (5 turns, answer=9) | R41 (6 turns, answer=13) |
|-----------|------------------------|-------------------------|
| Scrolling | None | Full (3 scrolls, end-of-list confirmed) |
| Scratchpad | Not used | Used (2 writes) |
| Date boundary computation | None | Correctly computed Oct 16-22 |
| Date boundary verification | None | Not done per-task |
| Duplicate detection | None | None |
| Core failure | Premature answer, no exploration | Good exploration, wrong counting logic |

R41 shows clear behavioral improvement over R39: the agent followed the scrolling and scratchpad guidance. However, the failure mode shifted from "didn't try" to "counted incorrectly due to duplicate entries and no per-task date verification."

## Why the Fix Was Insufficient

The info-extraction verification fix ("scroll the full list, cross-check totals against scratchpad, verify date boundaries before answering") addressed the R39 problem of not scrolling at all. The agent now scrolls and uses the scratchpad. But the fix did not address:

1. **Duplicate detection**: Tasks.org shows the same task name multiple times (recurring tasks, sub-tasks). The agent needs to deduplicate by task identity, not just count visible rows.
2. **Date label disambiguation**: Relative labels like "Wed" are ambiguous without positional context. The agent should either (a) use list sort position relative to "Today"/"Tmrw" markers, or (b) open each candidate in detail view to read the absolute due date.
3. **The app skill says** "Same-name day labels may refer to this week or next week. Check section headers to disambiguate." — the agent did not follow this guidance.

## Recommendations

1. **App skill enhancement**: Add explicit guidance: "Tasks may appear multiple times in the list (recurring, sub-tasks). Count unique task names only. When a task name repeats with different date labels, open detail view to verify the actual due date."
2. **Counting protocol**: For Tasks.org counting tasks, the agent should: (a) scroll full list, (b) record task names + date labels, (c) deduplicate by name, (d) for ambiguous dates, use position relative to "Today" marker or open detail view, (e) verify final count against deduped list.
3. **Prompt reinforcement**: The current "verify date boundaries" is too vague. Change to: "For each candidate task, confirm its date label resolves to the target range by checking its position relative to Today/Tmrw markers in the sorted list."

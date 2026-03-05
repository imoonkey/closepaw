# TasksDueNextWeek — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 6/30 | **Reason**: FalseCompletion | **Duration**: 80s

**Goal**: How many tasks do I have due next week in Tasks app? Assume the week starts from Monday. Express your answer as a single integer.

## Root Cause

**Category**: ReasoningError

The agent answered "8" but the answer was scored as incorrect. The agent's reasoning about which tasks fall within "next week" was flawed. It listed 8 tasks due Mon-Fri+Oct 22, but miscounted or misidentified which tasks actually had due dates in the next-week window (Mon Oct 16 through Sun Oct 22). The relative date labels (Mon, Tue, Wed, etc.) shown in the UI required the agent to reason about the current date (Oct 15, a Sunday) and compute the next Monday-Sunday range. The agent also only scrolled down 3 times and may have missed tasks, and included tasks whose relative labels may not correspond to the assumed next-week range.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | open_app("Tasks") | Yes | OK | OK | OK | Correctly opens Tasks app |
| 2 | scroll down | Yes | OK | OK | OK | Seeing more tasks — view shows tasks with relative dates |
| 3 | scroll down | Yes | OK | OK | OK | Continued scrolling, now sees Oct 22 tasks |
| 4 | scroll down | Yes | OK | OK | OK | Scrolled further to check for more tasks |
| 5 | click hamburger menu | Yes | Weak | OK | OK | Opened nav drawer unnecessarily |
| 6 | complete_task("8") | N/A | Fail | N/A | N/A | Wrong count — off by at least 1 |

### Key Turns

**Turn 2** — After scrolling, the agent could see tasks with relative date labels: Yest (yesterday), Today, Tmrw (tomorrow), Tue, Wed, Fri, Sat, Oct 22. The task list showed: Water Plants (Yest), Create Budget (Fri), Read The Martian (Today), Follow up on support tickets (Tmrw), Plan family reunion (Tue), Book flights (Wed), Research market trends (Fri), Send report to manager (Sat), Organize files and folders (Oct 22).

**Turn 6** — The agent listed 8 tasks as due next week: Water Plants (Mon), Attend Team Meeting (Tue), Reply to Emails (Wed), Brainstorm Blog Post Ideas (Thu), Read The Martian (Thu), Back Up Computer Files (Fri), Renew Driver's License (Fri), Organize files and folders (Oct 22). However, several of these task names do not match what was actually visible on screen. The agent hallucinated task names like "Attend Team Meeting", "Reply to Emails", and "Brainstorm Blog Post Ideas" which were not in the visible UI. This indicates the agent confabulated data rather than reading from the actual screen.

## What Worked

- Efficient navigation: opened app and scrolled to see all tasks in just 4 turns
- Correctly identified that "next week" means Mon-Sun starting Oct 16
- Quick completion in 6 turns (though answer was wrong)

## What Didn't Work

- **Hallucinated task names**: The agent listed tasks ("Attend Team Meeting", "Reply to Emails", "Brainstorm Blog Post Ideas") that were not visible on any screen capture. It fabricated task data instead of reading from the a11y tree
- **Insufficient scroll coverage**: Only scrolled 3 times — may not have seen all tasks in the list
- **No verification pass**: Did not scroll back through the list to double-check the count before answering
- **Date mapping errors**: The relative labels (Yest, Today, Tmrw, Tue, etc.) needed to be carefully mapped to absolute dates, but the agent did not explicitly reason about this mapping

## Recommendations

- **Ground answers in observed data only**: The agent must only report task names that appear verbatim in the a11y tree. Never fabricate or guess task names
- **Add explicit date computation**: Before counting, the agent should use scratchpad to write out: "Today = Oct 15 (Sun). Next week = Mon Oct 16 through Sun Oct 22. Relative labels: Mon=Oct 16, Tue=Oct 17, ..." then match tasks to this range
- **Scroll comprehensively**: Scroll until no new content appears (duplicate elements detected) before making a count
- **Verification pass**: After forming an answer, scroll through the entire list once more to verify the count
- **Use scratchpad to accumulate**: Write each qualifying task to scratchpad as it is found, with its date label, to prevent hallucination

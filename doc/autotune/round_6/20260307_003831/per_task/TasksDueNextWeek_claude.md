# TasksDueNextWeek — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 2/30 | **Reason**: GoalAchieved | **Duration**: 14s

**Goal**: Count tasks due next week (Monday-Sunday) in Tasks app. Answer as a single integer.

## Root Cause

**Category**: ReasoningError

The agent opened the Tasks app successfully and saw the "My Tasks" list with tasks and relative due dates (Today, Yest, Wed, Thu, Fri, Oct 8). It answered "4" after a single glance at the screen without (a) scrolling to see all tasks, (b) determining what "today" actually is to compute "next week," or (c) carefully deduplicating the a11y tree's repeated elements. The LLM treated the visible list as complete and guessed a count from ambiguous relative dates.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("Tasks")` | Y | G | G | G | Successfully opened org.tasks |
| 2 | `complete_task(answer="4")` | N/A | B | N/A | N/A | Premature completion, wrong count |

### Key Turns

**Turn 2** — The agent saw the Tasks list with ~10 task rows visible (many are duplicated in the a11y tree due to RelativeLayout + child TextView pairs). The visible tasks with their relative dates were:

- "Finish Project Proposal" x3 entries: "Oct 8", "Oct 8", "Fri"
- "Call Grandma" x3 entries: "Wed", "Thu", "Today"
- "Water Plants" x2 entries: "Thu", "Yest"
- "Reply to Emails" x1: "Fri"
- "Create Budget for Next Month" x1: "Fri" (partially visible, cut off at bottom)

Three critical reasoning failures:
1. **No date anchoring**: The agent had no way to know what calendar date "Today" maps to (the system clock shows 15:34 but no calendar date). Without knowing today's date, "next week" is undefined. The agent should have looked at a calendar or checked date details on a task to anchor the timeline.
2. **No scroll**: The RecyclerView was scrollable, meaning more tasks could exist below the fold. The agent never scrolled to see the full list.
3. **Ambiguous deduplication**: The a11y tree contains duplicate entries for each task (parent RelativeLayout text vs child TextView text). The agent likely miscounted unique tasks vs UI elements, or made incorrect assumptions about which relative dates fall in "next week."

## What Worked

- Correctly identified and opened the Tasks app (org.tasks) on the first try
- The open_app tool worked cleanly, transitioning from the Android Agent home screen to the Tasks list

## What Didn't Work

- Agent completed in only 2 turns on a task that requires careful date reasoning and potentially scrolling
- No attempt to determine today's absolute date to define "next week" boundaries
- No scrolling to ensure all tasks were visible
- The a11y tree's duplicate entries (RelativeLayout containing task name + description vs separate child TextViews) created a confusing counting surface that the LLM did not carefully parse
- The LLM (gpt-5.4) was overconfident, jumping to an answer without verifying completeness

## Recommendations

- **System prompt guidance**: Add instruction that for counting/listing tasks, the agent must scroll to the end of any scrollable list before answering
- **Date anchoring heuristic**: When relative dates (Today, Wed, Fri) are visible but an absolute reference is needed, the agent should open a task detail or calendar to determine the current date before computing "next week"
- **Anti-premature-completion**: For questions requiring enumeration ("how many"), the agent should be prompted to verify completeness (scroll to end, confirm no more items) before calling complete_task
- **A11y tree dedup awareness**: The perception layer could deduplicate parent containers whose text is just a concatenation of child texts, reducing confusion for the LLM

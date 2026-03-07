# TasksDueNextWeek — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 7/30 | **Reason**: GoalAchieved (wrong answer) | **Duration**: 101s

**Goal**: Count tasks due next week (Mon-Sun) in the Tasks app. Answer as a single integer.

## Root Cause

**Category**: FalseCompletion

The agent executed a reasonable strategy -- opened Tasks app, recorded tasks via scratchpad, scrolled to see more, and resolved relative date labels against the known date (Oct 15, Sunday). It answered "20" but the eval scorer disagreed. The agent's task list in the turn 4 scratchpad contains 20 entries, all with dates mapping into Oct 16-22. Two possible failure modes: (1) the agent miscounted by including duplicates seen across overlapping scroll views, or (2) the agent hallucinated tasks not actually present in the app. The a11y tree evidence supports most of the 20 items as genuinely visible, but some items may have been double-counted across scroll boundaries where overlapping tasks appeared in consecutive views.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app(Tasks)` | Y | G | G | G | Opened org.tasks successfully |
| 2 | `scratchpad(write)` + `scroll(down)` | Y | G | G | G | Recorded 10 visible tasks, identified 6 as next week. Scrolled to see more. |
| 3 | `scroll(down)` | Y | G | G | G | New tasks visible: Brainstorm Blog Post Ideas through Grocery Shopping |
| 4 | `scratchpad(write)` | N | B | G | G | Wrote 20 tasks as due next week. No screen action -- pure memory turn. |
| 5 | `scroll(down)` | N | G | G | B | Scroll succeeded per tool but same content as turn 3 (end of list likely near) |
| 6 | `scroll(down)` | N | G | G | B | Scroll failed: "no observable effect" -- confirmed end of list |
| 7 | `complete_task(20)` | N | B | G | G | Submitted answer based on scratchpad count |

### Key Turns

**Turn 2** -- Good use of scratchpad to record findings with date resolution. The agent correctly identified "today" = Oct 15 and mapped relative dates (Wed, Thu, Fri) to absolute dates. Initial count of 6 next-week tasks from the first viewport was correct. The agent also correctly excluded Oct 8 items and "Today"/"Yest" items.

**Turn 4** -- The agent consolidated all observed tasks into a single list of 20. This is the critical turn. Comparing the scratchpad list against what was actually visible in a11y trees across all 3 scroll positions, the items are broadly consistent with what was on screen. However, the final count of 20 may include items that were double-counted across overlapping scroll views (e.g., Call Grandma Wed appears in both initial view and after-scroll-1 view, and the agent recorded 3 separate "Call Grandma" entries for next week -- Wed Oct 18, Thu Oct 19, and another Wed Oct 18). This could be legitimate recurring tasks or scroll-boundary duplication.

**Turn 7** -- The agent recounted tasks from the current screen (8 visible) and combined with earlier observations to confirm 20. The reasoning references "additional instances of recurring tasks" without precise verification.

## What Worked

- Correct date reasoning: correctly identified next week as Oct 16-22 from the Sunday Oct 15 anchor
- Systematic scrolling: scrolled 4 times to see the full task list, confirmed end-of-list via scroll failure
- Scratchpad usage: recorded findings persistently across screens, avoiding memory loss
- Correct exclusion of "Today" (Oct 15), "Yest" (Oct 14), and "Oct 8" tasks
- Significant improvement from R6: 7 turns with scrolling vs 2 turns without scrolling

## What Didn't Work

- **Overcounting across scroll boundaries**: The agent likely double-counted tasks visible in overlapping scroll positions. For example, "Create Budget for Next Month" and "Water Plants (Yest)" appear in both the initial view and after-scroll-1 view, so adjacent tasks could be counted twice.
- **No deduplication strategy**: The agent accumulated tasks across viewports without a systematic way to detect overlap between consecutive scroll views.
- **No app skill injected**: The system prompt contained no org.tasks-specific guidance (date label semantics, sort order, list structure). The "new org.tasks app skill" mentioned in the round context was apparently not present in this run's prompt.
- **Hallucination risk in scratchpad**: The turn 4 scratchpad jump from 6 (turn 2) to 20 (turn 4) is a large leap. Some entries may have been inferred or hallucinated rather than directly observed -- particularly the ones with precise date mappings that may not have been visible in the a11y tree simultaneously.

## Recommendations

- **Deduplication protocol for scroll-and-count tasks**: Add prompt guidance to use anchor items (first/last visible item) to detect scroll overlap and avoid double-counting.
- **Inject org.tasks app skill**: The date label semantics in Tasks (relative dates like "Fri", "Wed", "Tmrw", "Today", "Yest", absolute dates like "Oct 22") should be documented in an app skill so the agent can map them reliably.
- **Incremental scratchpad updates**: Instead of rewriting the entire task list after multiple scrolls, append only newly discovered tasks each scroll, using the last seen item as a boundary marker.
- **Verify count against scratchpad before completion**: The agent should re-read its scratchpad and verify uniqueness of entries before submitting a count.

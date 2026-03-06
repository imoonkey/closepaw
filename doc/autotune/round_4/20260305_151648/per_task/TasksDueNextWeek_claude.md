# TasksDueNextWeek -- Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 6/30 | **Reason**: GoalAchieved | **Duration**: 87s

**Goal**: Count tasks due next week (Mon-Sun) in the Tasks app; answer as a single integer.

## Root Cause

**Category**: FalseCompletion

The agent opened the Tasks app, scanned the visible list, and quickly concluded there were 14 tasks due next week. The scratchpad shows it counted tasks with date labels like "Mon Oct 16", "Tue Oct 17", etc. However, the answer was scored 0.0, meaning the count was wrong. The agent's scratchpad shows inconsistency: Turn 4 counted 14 tasks, Turn 5 recounted to 13, but the final answer was 14. The agent included "Read 'The Martian' (Thu Oct 19)" in the Turn 4 scratchpad but dropped it in Turn 5, yet still answered 14. The core problem: the agent relied on a single shallow scan of the task list without scrolling through ALL tasks, potentially missing some or double-counting duplicates (the Tasks app shows the same task under multiple lists/views).

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("Tasks")` | Y | G | G | G | -- |
| 2 | `mobile_action(click)` | Y | G | G | G | Navigation in task list |
| 3 | `mobile_action(click)` | Y | G | G | G | Further navigation |
| 4 | `scratchpad(write)` | N | B | -- | G | Counted 14 tasks for Oct 16-22 |
| 5 | `scratchpad(write)` | N | B | -- | G | Recounted to 13 but inconsistent |
| 6 | `complete_task("14")` | -- | B | -- | G | Submitted 14 despite scratchpad showing 13 |

### Key Turns

**Turn 4-5** -- The scratchpad shows conflicting counts. Turn 4 lists 14 tasks including "Read 'The Martian' (Thu Oct 19)". Turn 5 re-organizes by day and gets 13, adding "Create Budget for Next Month" (Fri) but dropping "Read 'The Martian'". The agent then submits 14, ignoring its own recount of 13.

**Turn 6** -- Agent submitted 14 with a thought that lists 14 tasks, but the scratchpad's final entry said 13. The QA guardrail (scratchpad evidence check) was technically used but the agent contradicted its own scratchpad.

## What Worked

- Used scratchpad to track tasks (R4 guardrail partially working)
- Fast execution (6 turns) -- efficient navigation
- Date-label tip helped: agent correctly mapped "next week" to Oct 16-22 using device date (Oct 15, Sunday)

## What Didn't Work

- Shallow scan: agent did not scroll through the entire task list; the Tasks app may show duplicates across views or have tasks below the fold
- Self-contradictory scratchpad: counted 14, recounted 13, submitted 14
- No verification pass: after writing to scratchpad, agent should have re-scanned the UI to verify against what was actually visible
- The QA guardrail was used (scratchpad) but the agent ignored its own discrepancy

## Recommendations

- The scratchpad guardrail needs teeth: if the scratchpad count differs from the submitted answer, the agent should reconcile before submitting. Consider a prompt-level rule: "If your scratchpad total differs from your answer, re-verify before calling complete_task."
- For counting tasks, the agent should scroll the ENTIRE list and record findings incrementally, not rely on a single viewport.
- Task deduplication awareness: the Tasks app can show the same task in multiple views/groups. The agent should note unique task titles and deduplicate.

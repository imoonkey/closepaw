# TasksIncompleteTasksOnDate — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 3/30 | **Reason**: GoalAchieved (FalseCompletion) | **Duration**: 32s

**Goal**: What incomplete tasks do I have still have to do by October 19 2023 in Tasks app? Answer with the titles only. If there are multiples titles, format your answer in a comma separated list.

## Root Cause

**Category**: ReasoningError

The R4 date-label tip and scratchpad guardrail partially worked: the agent now uses the scratchpad to compute date mappings (Today=Oct 15, Thu=Oct 19, Fri=Oct 20, etc.) and correctly filters out tasks after Oct 19 (Water Plants/Fri, Back Up Computer Files/Sat, Water Plants/Oct 22). However, the agent still scored 0.0 because (a) it never scrolled the task list and likely missed qualifying tasks below the fold, and (b) it may have mishandled the ambiguous "Back Up Computer Files" entry which shows both "Sat" (due date) and a "Tmrw" (Oct 16) chip that could indicate it belongs in the answer. The failure mode is still FalseCompletion — the agent confidently answered on turn 3 with an incomplete/incorrect list.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("Tasks")` | Y | G | G | G | Correctly opens Tasks app |
| 2 | `scratchpad(write)` | N | B | G | G | Date mapping computed, but no scroll before answering |
| 3 | `complete_task(success)` | N | B | — | G | Submitted answer without scrolling or verification |

### Key Turns

**Turn 2** — The agent used the scratchpad to compute date mappings: "Today is Oct 15 (Sunday), so Thu=Oct 19, Fri=Oct 20, Sat=Oct 21, Tue=Oct 17, Today=Oct 15, Tmrw=Oct 16." It then filtered to tasks on/before Oct 19: "Attend training session (Thu), Call client for follow-up (Thu), Research market trends (Thu), Change Air Filter (Today), Renew Driver's License (Tue), Clean Out Fridge (Tue)." This is a genuine improvement over R3 (which dumped all 8 tasks with zero filtering). However, two problems remain:

1. **No scroll**: The RecyclerView (index 10) is scrollable and the last visible task (Clean Out Fridge) is cut off at the bottom edge (bounds extend to y=2337, the screen boundary). There are almost certainly more tasks below the fold that could be due by Oct 19.

2. **Ambiguous "Back Up Computer Files"**: This task shows "Sat" as the date label (index 28) but also has a distinct "Tmrw" chip (index 30, a checkable View). The "Sat" label may be the due date while "Tmrw" could be a start date or reminder. If the scorer considers this task as due on "Tmrw" (Oct 16), excluding it would be wrong.

**Turn 3** — The agent immediately called `complete_task` with the filtered list from the scratchpad. No scrolling, no verification, no clicking into individual tasks to confirm exact due dates.

## R3 vs R4 Comparison

| Dimension | R3 (Round 3) | R4 (Round 4) |
|-----------|-------------|-------------|
| Turns used | 2 | 3 |
| Date filtering | None — dumped all visible tasks | Yes — computed date map, excluded Fri/Sat/Oct 22 tasks |
| Scratchpad use | Listed all tasks, no reasoning | Wrote date computation, filtered list |
| Scrolling | No | No |
| Answer | 8 tasks (no filtering) | 6 tasks (date-filtered but incomplete list) |
| Score | 0.0 | 0.0 |

The date-label tip clearly helped: the agent went from zero date awareness to correct relative-date computation. The scratchpad guardrail also worked — the agent used it for evidence. But the score didn't improve because the agent still doesn't scroll before answering list queries.

## What Worked

- Date-label tip landed: agent correctly mapped relative labels (Thu, Fri, Sat, Tue, Today, Tmrw) to absolute October dates
- Scratchpad used for structured reasoning before answering (R4 guardrail effective)
- Correct exclusion of Water Plants (Fri=Oct 20), Water Plants (Oct 22) — post-deadline tasks filtered out

## What Didn't Work

- **No scrolling before answering**: The RecyclerView is scrollable and the last task is clipped at the screen edge. The agent must scroll to see the full task list before answering any enumeration query. This was the same failure in R3 and remains the primary blocker.
- **Premature completion on turn 3**: The agent should have at minimum scrolled down, ideally scrolled back up, before calling complete_task.
- **"Back Up Computer Files" ambiguity not resolved**: The task has both "Sat" and "Tmrw" indicators. The agent assumed "Sat" was the due date and excluded it, but the "Tmrw" chip may indicate an earlier relevant date. Clicking into the task would have revealed the actual due date.

## Recommendations

- **Mandatory scroll-before-answer rule**: For any "list all X" or "what tasks" query, the agent MUST scroll the full list at least once before answering. This is the single highest-impact fix for this task family. Add a system prompt rule: "Before answering any enumeration question, scroll to ensure you have seen all items."
- **Click ambiguous tasks**: When a task shows multiple date indicators (e.g., "Sat" + "Tmrw"), click into it to confirm the actual due date rather than guessing.
- **Increase minimum turn budget for info-retrieval**: The agent completed in 3 turns for a task that requires at minimum: open app (1) + scroll down (1-2) + scroll up to verify (1) + answer (1) = 4-5 turns. A "minimum exploration" guardrail could prevent premature completion.

# TasksIncompleteTasksOnDate — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 18/30 | **Reason**: GoalAchieved | **Duration**: 145s

**Goal**: List incomplete tasks on a specific date in Tasks.org.

## Root Cause

**Category**: FalseCompletion / PerceptionGap

Agent spent 18 turns — more effort than other Tasks.org failures — but still got the wrong answer. The root cause is likely the a11y tree missing the `checked` property for Tasks.org checkboxes. Without being able to distinguish completed from incomplete tasks, the agent cannot reliably filter by completion status. The agent may have included completed tasks in its answer or missed incomplete ones.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1-3 | Launch Tasks.org, navigate to task list | Y | G | G | G | App opened |
| 4-8 | Scroll through tasks, read due dates | Y | G | G | G | Task scanning |
| 9-14 | Attempt to identify completion status | Y | B | B | PerceptionGap | Can't read checkbox state |
| 15-17 | Compile answer from available info | — | B | B | B | Wrong filter |
| 18 | complete_task with answer | — | B | B | FalseCompletion | Wrong answer |

### Key Turns

**Turn 9** — Agent attempts to determine which tasks are incomplete. Checkboxes render as ImageView with `checked=N/A` in the a11y tree. Agent cannot distinguish completed from incomplete tasks and must guess or use heuristics.

**Turn 18** — GoalAchieved declared. Without reliable completion status, the answer is inevitably wrong.

## What Worked

- Tasks.org launched and navigated correctly
- Due date filtering attempted
- More effort (18 turns) than other Tasks.org failures

## What Didn't Work

- Cannot read checkbox/completion state from a11y tree
- No "Show completed" toggle strategy attempted (or toggle didn't register)
- Answer included wrong set of tasks

## Recommendations

- Known issue: A11y tree missing `checked` property for Tasks.org
- Diff-based strategy: toggle "Show completed" ON vs OFF, compare lists to identify completed tasks
- However, "Show completed" toggle itself may not register via a11y click
- Fundamental perception gap — no prompt change will fix without a11y tree improvement

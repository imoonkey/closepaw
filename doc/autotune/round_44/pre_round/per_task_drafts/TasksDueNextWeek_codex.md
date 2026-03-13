# TasksDueNextWeek — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 7/30 | **Reason**: GoalAchieved | **Duration**: 68s

**Goal**: Count how many tasks are due next week in Tasks app, where next week means Monday October 16, 2023 through Sunday October 22, 2023, and answer with a single integer.

## Root Cause

**Category**: FalseCompletion

`20260312_194856` is a regression from the last pass `20260309_125810`. The success run used a simple, bounded strategy visible in the local trace: resolve `Oct 16-22`, scan until the list stops changing, then count the six future tasks visible after the `Today/Tmrw` boundary. The recent failures (`20260312_185423` answer `14`, `20260312_194856` answer `13`) instead reason over recurring/duplicate rows with ambiguous weekday labels, and `20260312_194856` also loses its partial dedup state because the scratchpad value is truncated before the final answer.

## Turn-by-Turn Analysis

Local traces for `20260312_185423` and `20260312_194856` are not present under `eval/results/.../artifacts`, so the failure sequence below is reconstructed from `per_task.jsonl` plus the round-42/43 task analyses, and compared against the local success trace for `20260309_125810`.

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1-2 | Open Tasks, compute `Oct 16-22`, start working notes | Y / N | G | G | G | Same good setup as the pass: the date window is correct. |
| 3-5 | Scroll and build a dedup list in scratchpad | Y | B | G | G | `20260312_194856` improves over `20260312_185423` by attempting dedup, but still records 8 unique names, already above the correct 6. |
| 6 | Final scroll near end of list | Y | B | G | G | Additional rows after the `Oct 22` boundary are not reconciled back into a correct bounded count. |
| 7 | `complete_task(answer=\"13\")` | N/A | B | — | — | Scratchpad is truncated, so the agent falls back to raw visible-row counting and false-completes. |

## What Worked

- `20260309_125810` shows the task is solvable with the current UI: after two downward scrolls, the local trace bottoms out on exactly six next-week tasks and returns `6`.
- The post-R39 behavior is better than the premature-answer failure in `20260312_094821`: both `20260312_185423` and `20260312_194856` compute the right date range and scroll instead of answering from the first viewport.
- `20260312_194856` partially adopts the intended dedup behavior, which is an improvement over `20260312_185423` even though it still fails.

## What Didn't Work

- The regression versus `20260309_125810` is that the agent no longer keeps the count bounded to the visible `Today/Tmrw -> Oct 22` slice that yielded the correct six tasks.
- `20260312_185423` counts duplicate/recurring task rows as separate tasks, producing `14`.
- `20260312_194856` still over-collects candidates, then loses its own working memory when the scratchpad field is truncated and submits `13`.
- The failure is no longer navigation; it is counting logic plus fragile working memory.

## Recommendations

- Re-anchor the Tasks counting skill to the passing invariant from `20260309_125810`: count only tasks after `Today/Tmrw` and stop at the `Oct 22` boundary; do not keep accumulating rows after that boundary.
- Keep dedup-by-name guidance, but pair it with the boundary rule above so repeated weekday labels deeper in the list are not assumed to belong to the target week.
- Avoid long serialized scratchpad values for this task until truncation is fixed; store a compact count plus short task-name set, or treat scratchpad truncation as a platform bug and park the task if that bug cannot be fixed.

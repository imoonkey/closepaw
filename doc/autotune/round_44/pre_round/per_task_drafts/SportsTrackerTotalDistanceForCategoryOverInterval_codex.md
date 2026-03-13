# SportsTrackerTotalDistanceForCategoryOverInterval — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 35/50 | **Reason**: GoalAchieved (false completion) | **Duration**: 313s

**Goal**: In OpenTracks, find the total distance for kayaking activities from October 9, 2023 to October 10, 2023, and return a single integer number of meters.

## Root Cause

**Category**: FalseCompletion

Local `eval/results/*/trace` directories are absent in this workspace for `20260312_194856`, `20260312_185423`, and `20260312_132005`, so this write-up is grounded in `per_task.jsonl`, `doc/autotune/meta/scoreboard.json`, `doc/autotune/meta/per_task/SportsTrackerTotalDistanceForCategoryOverInterval.md`, and the checked-in R42/R43 per-task analyses that were written from those traces. The post-R40 regression is loss of exhaustive list coverage: `20260312_132005` passed with answer `6500` in 26 turns, while `20260312_185423` and `20260312_194856` both answered `0` after treating the initial viewport as the full Oct 9-10 set.

`20260312_185423` fixed the older R41 Smart Filtering shortcut by checking all 5 visible Oct 9-10 rows, but it still never scrolled and had to infer sport types from an empty Edit-screen a11y tree. `20260312_194856` then added a stronger 4-step scroll-first checklist, yet the agent still skipped step 1, inspected the same visible-only set, and false-completed again. `20260312_094821` already showed the same coverage family of error with partial answer `2832`; R40 recovered, and R42-R43 regressed back to incomplete scan behavior.

Earlier notes describe the underlying total as about `6502m`, but the scorer accepted `6500` in `20260312_132005`. The important regression is coverage, not the exact rounding boundary.

## Recent Run Comparison

| Run | Score | Turns | Answer | Note |
|-----|-------|-------|--------|------|
| `20260312_132005` | 1.0 | 26 | `6500` | Accepted success; implies the hidden kayaking rows were found |
| `20260312_185423` | 0.0 | 37 | `0` | Checked all 5 visible rows, never scrolled |
| `20260312_194856` | 0.0 | 35 | `0` | Same visible-only scan; ignored new scroll-first step |

## Turn-by-Turn Analysis

Note: `20260312_194856` is reconstructed from the checked-in R43 analysis because the local trace directory is missing.

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1-4 | Launch OpenTracks, dismiss onboarding, receive 4-step checklist | Y | G | G | G | Protocol explicitly required scrolling before inspection |
| 5 | Write the visible Oct 9-10 tracks into scratchpad | N | B | G | G | Agent softened "scroll first" into "scroll as needed" and froze the visible 5-row set |
| 6-34 | Open each visible track, inspect details/Edit, mark it checked, back out | Y | B | G | G | Thorough on the visible set, but still no list scroll; R42 shows Edit screens also had empty a11y trees |
| 35 | `complete_task(answer="0")` | N | B | G | G | False completion with incomplete list coverage |

### Key Turns

**Turn 5 (`20260312_194856`)**

This is the decisive miss. The agent acknowledged only the tracks visible on screen, wrote them down as the working set, and never performed the mandated pre-scan scroll. That is why the new R43 protocol did not change the outcome from R42.

**Turns 10, 16, 22, 29, 36 (`20260312_185423`)**

The checked-in R42 analysis shows these were the points where the agent wrote sport types into scratchpad after opening Edit. The Edit screen's accessibility tree exposed only system status-bar nodes, so the model inferred activity types from track names instead of grounded UI evidence. That did not directly cause the `0` answer, but it made the whole workflow fragile.

**Success comparison (`20260312_132005`)**

Even without the missing trace, the run metadata is clear: `20260312_132005` passed with `6500` in only 26 turns. The recent failures consumed more turns while learning less, which strongly suggests that the success run covered the hidden kayaking entries instead of exhaustively validating only the first five visible rows.

## What Worked

- `20260312_185423` and `20260312_194856` no longer had the R41 Smart Filtering problem; they did inspect every visible Oct 9-10 track.
- Date reasoning was stable in the recent runs; the agent looked at the correct Monday/Tuesday window.
- The accepted pass on `20260312_132005` proves the task is solvable when coverage is complete.

## What Didn't Work

- The core failure in both recent losses was no scrolling before conclusion, despite increasingly explicit instructions.
- `20260312_194856` is a direct regression in instruction-following: the newly added "scroll first" step was ignored entirely.
- `20260312_185423` revealed a second structural weakness: OpenTracks Edit screens expose no usable form fields through accessibility, so type verification was effectively hallucinated.
- More turns did not help. The failure runs used 35-37 turns versus 26 in the success run because they spent effort on the wrong subset of rows.

## Recommendations

- Treat this as parked for prompt-only tuning. R41, R42, and R43 all escalated wording around scrolling, and none produced scroll compliance.
- If revisited, enforce scroll-before-inspection in policy/tooling rather than app-skill prose. The failure is repeated non-compliance, not missing instruction text.
- Fix or bypass the OpenTracks Edit-form perception gap with screenshot input or better instrumentation; otherwise sport-type verification remains ungrounded even when scrolling improves.

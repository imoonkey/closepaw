# SportsTrackerLongestDistanceActivity — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 16/50 | **Reason**: GoalAchieved | **Duration**: 125s

**Goal**: Find the longest distance for an OpenTracks activity whose exact activity type is `mountain biking` in the current Monday-start week, then answer in meters rounded to the nearest integer.

## Root Cause

**Category**: ReasoningError

In the newest failure `20260312_173354`, the agent navigated correctly and read exact activity labels, but it regressed after opening `Scenic Cycling`: it saw the authoritative type `biking`, then treated that as mountain-biking-equivalent and marked candidate verification complete. The last success `20260312_094821` saw the same weekly list, explicitly rejected `Scenic Cycling` as only `biking`, later opened the still-visible `Skill work` row (idx `43` on the same list screen), verified exact type `mountain biking`, and answered `1207`. The other recent failure `20260312_122824` has no local trace; the fallback task note says it answered `0` after a perception miss, so `20260312_173354` is a different regression.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1-3 | `open_app` + dismiss onboarding | Y | G | G | G | Reached the OpenTracks track list cleanly. |
| 4 | `write_todos` | — | G | G | G | Initial plan was correct: verify candidate activity types, then compute the max. |
| 5-10 | Open `Wave Exploration`, enter `Edit`, record `surfing`, back out | Y | G | G | G | Correctly ruled out today's track. |
| 11 | `system_button(back)` | N* | G | G | G | Immediate post-action observation still looked like the stats screen, but turn 12 confirmed the list was reached. |
| 12-14 | Open `Scenic Cycling`, record `11.60 mi`, enter `Edit` | Y | G | G | G | Reasonable first long-distance biking candidate. |
| 15 | `scratchpad` + `write_todos` | — | B | G | G | After reading exact type `biking`, it marked “open each biking-related track” complete even though unresolved week entries were still visible on the prior list, including `Skill work`. |
| 16 | `complete_task("18668")` | — | B | B | G | Finalized from the `Scenic Cycling` edit screen, effectively equating `biking` with `mountain biking`. |

### Key Turns

**Turn 15** — search was declared complete too early:
- **What was on screen**: The `Edit` screen for `Scenic Cycling` with exact activity type `biking`.
- **What agent decided**: It stored `{"scenic_cycling_tuesday_activity":"biking"}` and then updated todos as if “Open each biking-related track from this week and verify authoritative Activity type in Edit screen” were complete.
- **What action was taken**: `scratchpad(write scenic_cycling_tuesday_activity=biking)` followed by `write_todos(...)`.
- **What actually happened**: No new evidence was gathered; on the immediately preceding weekly list screen, `Road Bike Ride`, `Road Bike Session`, and `Skill work` were all still visible and in-range.
- **What should have happened**: Reject `biking` as a non-exact match for `mountain biking`, return to the list, and inspect unresolved candidates.
- **Perception check**: This was not a visibility problem. Both `20260312_173354` and `20260312_094821` show `Track | Skill work | Tuesday | 45:00 (0.75 mi)` on the same list screen.

**Turn 16** — wrong answer was emitted from the wrong candidate:
- **What was on screen**: Still the `Scenic Cycling` edit screen with exact type `biking`.
- **What agent decided**: Report `18668` meters as the winner.
- **What action was taken**: `complete_task(answer="18668")`.
- **What actually happened**: The scorer rejected the answer. In the last success `20260312_094821`, the agent continued from the same candidate state, later opened `Skill work`, saw exact type `mountain biking`, stored `{"longest_mtb_mi":"0.75"}`, and returned `1207`.
- **What should have happened**: Continue candidate verification until an exact `mountain biking` match was found or all candidates were ruled out.
- **Perception check**: The success trace shows the label `mountain biking` is available in the `Edit` screen for `Skill work`, so this run failed in decision policy, not UI visibility.

## What Worked

- Navigation and tool execution were stable in both `20260312_173354` and `20260312_094821`; there were no tool failures.
- The agent correctly used the `Edit` screen as the authoritative source for activity type.
- `Wave Exploration` was correctly ruled out as `surfing` before the regression happened.

## What Didn't Work

- `20260312_173354` collapsed exact-type matching: `biking` was treated as close enough to `mountain biking`.
- The agent marked verification complete after checking only two tracks, despite unresolved in-range candidates still being visible.
- The run ended with confident false completion instead of preserving uncertainty or continuing the search.
- `20260312_122824` suggests the task is also vulnerable to perception misses when the exact label is not recognized, so the task remains flaky for two different reasons.

## Recommendations

- Require exact activity-type matching for category extremum queries: `mountain biking` must not accept `biking` or `road biking`.
- Do not mark candidate verification complete while any visible in-range biking-related entries remain unchecked; keep an explicit unresolved-candidate set in scratchpad/todos.
- If no exact match has been verified yet, block `complete_task` and keep searching or report uncertainty instead of defaulting to a near match (`20260312_173354`) or `0` (`20260312_122824`).

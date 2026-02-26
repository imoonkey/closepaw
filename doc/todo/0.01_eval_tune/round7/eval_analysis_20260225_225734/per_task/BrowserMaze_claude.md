# BrowserMaze - Cog-Tune Analysis

## Run Metadata

| Field | Value |
|---|---|
| Run ID | `aw_20260225_225734_BrowserMaze_3_0` |
| Model | qwen3.5 (qwen/qwen3.5-plus-02-15) |
| Goal | Open the file task.html in Downloads in the file manager; when prompted open it with Chrome. Then navigate the X to the bottom-right cell, by using the direction buttons. |
| Completion | (null — trace capture failed) |
| Task Status | **failure** |
| Turns (actual) | 18 (logcat) / 0 (runner-reported) |
| Duration | 189s |
| Scripted Score | 0.0 |

## Root Cause

**Category: Execution + Observation**

The agent executed 18 turns and claimed success, but the maze was not actually solved. Multiple issues:

1. **Chrome first-run interference**: Turns 4-8 were spent dealing with Chrome's "Accept & continue" and "No thanks" sync prompts, consuming ~5 turns.
2. **Maze navigation partially correct but unverified**: Agent moved Down, Right, Down, Down, Right, Down, Right — but may not have reached the actual target cell. The agent claimed "Success!" but the scripted scorer found the puzzle was not completed.
3. **False completion claim**: Agent called complete_task with "Success!" without proper verification that the maze was actually solved.

## Turn-by-Turn Analysis (from logcat)

| Turn | Tool | Action | Target | Result |
|------|------|--------|--------|--------|
| 1 | open_app | open | Files | Opened Files app |
| 2 | mobile_action | click | task.html (idx 17) | Selected file |
| 3 | mobile_action | click | Just once (idx 6) | Chose Chrome |
| 4 | mobile_action | click | Accept (idx 7) | Chrome first-run accept |
| 5 | mobile_action | click | No thanks (idx 8) | Chrome sync decline |
| 6 | wait | 2000ms | — | Wait for Chrome |
| 7 | mobile_action | click | (180,900) coordinates | Try clicking No thanks |
| 8 | mobile_action | click | No thanks (idx 8) | Another click attempt |
| 9 | mobile_action | click | task.html (idx 17) | Re-open file |
| 10 | mobile_action | click | Just once (idx 6) | Chrome again |
| 11 | mobile_action | click | Down (idx 13) | Maze: move down |
| 12 | mobile_action | click | Right (idx 15) | Maze: move right |
| 13 | mobile_action | click | Down (idx 13) | Maze: move down |
| 14 | mobile_action | click | Down (idx 13) | Maze: move down |
| 15 | mobile_action | click | Right (idx 15) | Maze: move right |
| 16 | mobile_action | click | Down (idx 13) | Maze: move down |
| 17 | mobile_action | click | Right (idx 15) | Maze: move right |
| 18 | complete_task | success (FP) | — | Claimed puzzle shows "Success!" |

## Key Observations

1. **Chrome first-run overhead**: 5 turns (28% of total) wasted on Chrome setup prompts. This is a cross-task issue for all Browser* tasks.
2. **Maze navigation was blind**: The agent moved D-R-D-D-R-D-R but couldn't verify its actual position since the maze grid is rendered in HTML and accessibility tree may not reflect X position accurately.
3. **False positive completion**: Agent claimed "Success!" but maze wasn't solved — the "Success!" text may not have actually appeared on screen, or the X wasn't in the correct cell.
4. **Trace capture failed**: Runner reported 0 turns despite 18 actual executions.

## Recommendation

1. **Chrome pre-configuration**: Pre-accept Chrome ToS during eval environment setup to avoid wasting 5 turns per browser task.
2. **Post-action verification**: Before calling complete_task, agent should verify the screen actually shows a success state. Add system prompt guidance: "For puzzle/game tasks, verify the success message is visible on screen before completing."
3. **Maze perception**: This task may benefit from hybrid perception mode to see the actual maze grid state.
4. **Infra**: Fix trace capture mechanism.

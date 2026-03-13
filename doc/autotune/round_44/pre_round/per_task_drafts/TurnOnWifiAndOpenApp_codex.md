# TurnOnWifiAndOpenApp — Cog-Tune Analysis

**Score**: 0.5 | **Turns**: 6/30 | **Reason**: GoalAchieved | **Duration**: 55s

**Goal**: Turn on Wi-Fi, then open the Settings app.

## Root Cause

**Category**: FalseCompletion

R40 `20260312_122824` regressed from the clean R39 pass `20260312_094821` even though the task, seed, and model were unchanged. The local R40 trace is missing, so the hard evidence is limited to `per_task.jsonl` plus fallback task notes: the agent claimed success in 6 turns, but the scorer awarded only 0.5, which means Settings was open but Wi-Fi was still off at scoring time. In the R39 trace, the agent only completed after reaching `Settings > Network & internet > Internet`, tapping the Wi-Fi row, and observing the Internet page again with the Wi-Fi switch checked; the regression is loss of that post-toggle verification, likely a non-deterministic flip at the Wi-Fi toggle boundary rather than a navigation regression.

## Turn-by-Turn Analysis

R40 local trace is not present in this repo; rows below use R40 metadata and the exact R39 trace as the comparison baseline.

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("Settings")` | Y in R39; likely Y in R40 | G | G | G | R39 opened Settings directly from Android Agent. |
| 2-3 | Open `Network & internet`, then `Internet` | Y in R39; likely Y in R40 | G | G | G | R39 used the Settings path, not a Quick Settings shortcut. |
| 4 | Tap Wi-Fi row / toggle | Y in R39 | G | G | G in R39, unknown in R40 | In R39 this changed the Internet page from Wi-Fi off to `Switch=true` / `Searching for networks…`. |
| 5 | Re-read Internet page before completing | Y in R39 | G | G | G in R39, missing or inconclusive in R40 | R39's next capture still showed Settings open and Wi-Fi on; R40 still only scored 0.5. |
| 6 | `complete_task` | — | B | — | — | R40 returned `GoalAchieved` even though the scorer still saw Wi-Fi off. |

### Key Turns

**R39 turn 4 -> 5** — the successful verification pattern:
- **What was on screen**: On `Internet`, the Wi-Fi switch was off before the tap.
- **What agent decided**: Tap the Wi-Fi row inside Settings instead of leaving the app.
- **What action was taken**: `mobile_action(click, element_index=16)`.
- **What actually happened**: The post-action capture changed to `Switch=true` and `Searching for networks…`; the next screen capture still showed the Internet page with Wi-Fi enabled, and the screenshot showed the `AndroidWifi` row while Settings remained open.
- **What should have happened**: Exactly this. It satisfied both subgoals with a verifiable final state.
- **Perception check**: The status bar still said `No internet`, so status-bar text is not a reliable verifier for this task; the Internet page state is.

**R40 turn 6** — the regression boundary:
- **What was on screen**: Local trace missing, so the exact UI is unavailable.
- **What agent decided**: `complete_task` with "Wi-Fi is on and the Settings app is open."
- **What action was taken**: Final completion after 6 turns / 7 tool calls.
- **What actually happened**: The scorer awarded only 0.5, so the final device state did not match the claim; the most plausible mismatch is Wi-Fi still off while Settings was open.
- **What should have happened**: The agent should have matched R39's pattern and only completed after an explicit post-toggle read showing the Wi-Fi switch on.
- **Perception check**: Because the R40 trace is absent, this cannot be separated cleanly into tap failure vs misread state. The only certain issue is false completion.

## What Worked

- R39 `20260312_094821` shows a stable, short path: Settings -> Network & internet -> Internet -> Wi-Fi row -> verify -> complete.
- The task does not require extra navigation once Settings is open; 5 turns were enough in the successful run.
- Both recent runs indicate the agent understood the high-level task order.

## What Didn't Work

- R40 `20260312_122824` regressed from `1.0 / 5 turns` to `0.5 / 6 turns` despite the same task seed and model.
- The latest run ended in `GoalAchieved` without the same verification signal R39 had before completion.
- `tool_result = Success: Clicked...` is not enough for Wi-Fi toggles; the reliable evidence in R39 came from the next captured screen state.

## Recommendations

- Treat `Settings > Internet` as the canonical verification surface for this task. Complete only after the Wi-Fi switch reads on (`checked=true` / visibly blue) and Settings is still open.
- Do not use status-bar `No internet` as a failure signal by itself; R39 passed while that text was still present.
- If the tap reports success but the next capture does not show Wi-Fi on, retry or refresh the Internet page instead of completing.

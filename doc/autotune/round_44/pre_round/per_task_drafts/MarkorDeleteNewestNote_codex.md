# MarkorDeleteNewestNote — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 8/30 | **Reason**: GoalAchieved | **Duration**: 72s

**Goal**: Delete the newest note in Markor.

## Root Cause

**Category**: PerceptionGap

`20260312_173354` fails for the same concrete reason visible in the local round_39 trace `20260312_094821`: after `Sort by -> Date`, the screenshot shows `final_friendly_king.md 10/15/2023, 15:34` and `fine_jelly_final.md 10/15/2023, 15:17`, but the sanitized a11y tree swaps the timestamp/file pairing and reports `fine_jelly_final.md 15:34` and `final_friendly_king.md 15:17`. The agent trusts the tree, long-presses `fine_jelly_final.md`, deletes the wrong file, and then falsely completes.

This is a regression from the last success `20260227_222506`. That run used shell (`ls -lt` plus `stat`) to identify `final_friendly_king.md` as newest, and the visible UI agreed. Recent runs follow the current Markor skill's UI-only guidance, never use shell, and are therefore exposed to the recurring screenshot-vs-a11y mismatch. `20260312_132005` also ended `0.0 / 8 turns / GoalAchieved` in `per_task.jsonl` and `doc/autotune/meta/scoreboard.json`, but its trace is not present locally, so the concrete regression call is grounded on `20260312_173354`, corroborated by `20260312_094821`, and compared against `20260227_222506`.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app(Markor)` | Y | G | G | G | Opens Markor file list. |
| 2 | `click(element_index=9)` | Y | G | G | G | Opens Sort menu. |
| 3 | `click(Date)` | Y | G | G | G | Sort works, but post-sort screenshot and a11y tree disagree on which file has `15:34`. |
| 4 | `long_press(element_index=16)` | Y | G | B | G | Tree says `fine_jelly_final.md 15:34`; screenshot shows `final_friendly_king.md 15:34`. Wrong file selected. |
| 5 | `click(Delete)` | Y | B | G | G | Delete flow is correct, but for the wrongly selected file. |
| 6 | `click(OK)` | Y | B | G | G | `fine_jelly_final.md` is deleted; `final_friendly_king.md` remains. |
| 7 | `remember_experience(...)` | N | B | B | G | Stores a false "successful deletion" memory. |
| 8 | `complete_task(success)` | N | B | B | G | False completion after wrong deletion. |

### Key Turns

**Turn 3** — observation becomes corrupted:
- **What was on screen**: The screenshot shows `final_friendly_king.md` with `10/15/2023, 15:34` and `fine_jelly_final.md` with `10/15/2023, 15:17`.
- **What agent decided**: Use the sorted timestamps to pick the newest file.
- **What action was taken**: `mobile_action(click, text="Date")`.
- **What actually happened**: The sort action succeeded, but the sanitized a11y tree exposed the opposite pairing: `fine_jelly_final.md 15:34`, `final_friendly_king.md 15:17`.
- **What should have happened**: The model should have had access to the screenshot-consistent pairing before choosing a deletion target.
- **Perception check**: This is the decisive mismatch in both `20260312_094821` and `20260312_173354`.

**Turn 4** — wrong file selected:
- **What was on screen**: `final_friendly_king.md` is visibly the newer file in the screenshot.
- **What agent decided**: "Newest note visible is `fine_jelly_final.md` dated `15:34`."
- **What action was taken**: `mobile_action(long_press, element_index=16)`.
- **What actually happened**: The app selected `fine_jelly_final.md`, matching the corrupted a11y tree rather than the screenshot.
- **What should have happened**: Long-press `final_friendly_king.md`.
- **Perception check**: The agent is not reasoning badly here; it is grounding against bad a11y data.

**Turn 6** — wrong deletion becomes visible:
- **What was on screen**: After confirming delete, only `final_friendly_king.md` remains in the list.
- **What agent decided**: The deletion removed the newest note.
- **What action was taken**: `mobile_action(click, text="OK")`.
- **What actually happened**: The wrong file was removed. This directly contradicts the success trace `20260227_222506`, where `final_friendly_king.md` was the file deleted and `fine_jelly_final.md` remained.
- **What should have happened**: `final_friendly_king.md` should have disappeared.
- **Perception check**: The surviving-file timestamp later mutates to `15:34`, which likely confused earlier evaluator-bug analyses, but the pre-delete screenshot already proves the targeting error.

**Turn 8** — false completion:
- **What was on screen**: The file list still contains `final_friendly_king.md`.
- **What agent decided**: The task is complete.
- **What action was taken**: `complete_task(status="success")`.
- **What actually happened**: The agent ends the run despite deleting the wrong note.
- **What should have happened**: Either recover by deleting the remaining newer file or decline completion.
- **Perception check**: No screenshot was attached to the model (`screenshot_attached=false`), so it never got a visual chance to catch the a11y swap.

## What Worked

- The Markor navigation and delete dialog flow were stable in all traced runs.
- `Sort by -> Date` consistently exposed enough UI information in the screenshot to identify the correct target.
- `20260227_222506` shows the task is solvable in 8 turns when the agent uses shell metadata instead of trusting the post-sort a11y tree.

## What Didn't Work

- `20260312_094821` and `20260312_173354` both show the same screenshot-vs-a11y timestamp swap after date sorting.
- Recent runs rely on the UI tree only; they do not use the shell path that succeeded in `20260227_222506`.
- The current Markor skill says "Shell does NOT work for Markor files," which pushes the model away from the successful fallback.
- The agent still completes after a destructive action without validating that the correct filename disappeared.

## Recommendations

- Reclassify the recent failures as a recurring Markor perception/grounding regression, not a pure evaluator false negative.
- Revisit `app/src/main/assets/app_skills/net.gsantner.markor/SKILL.md`: replace the blanket no-shell rule with a conditional fallback such as "try `ls/stat`; if access is denied, fall back to UI."
- For the UI-only fallback, require a screenshot or hybrid-perception cross-check before deleting when filename/timestamp pairing determines the target.
- Block `complete_task` after destructive actions unless the remaining file state matches the pre-delete newest-file inference.

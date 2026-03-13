# MarkorMoveNote — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 9/30 | **Reason**: GoalAchieved | **Duration**: 82s

**Goal**: In Markor, move the note `shy_king_copy.md` from `StudyGuides` to `MeetingMinutes`.

## Root Cause

**Category**: GroundingError

`20260312_185423` regressed from the `20260307_071709` success because the March 12 Markor app skill added a new file-selection rule: "match the target name as a substring." The base prompt still said "match exact filenames," but the agent followed the looser app-skill rule: on turn 3 its action thought says "by substring as instructed," and it long-pressed `2023_02_13_shy_king_copy.md` instead of scrolling to the exact `shy_king_copy.md` that the successful run found after one scroll.

`20260312_173354` shows the same 9-turn pattern and the same wrong selection (`2023_02_13_shy_king_copy.md` at element 16), so this is a stable regression rather than a one-off misclick. The move flow itself still works; the regression is the new premature substring match plus a final false completion without exact-file verification.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("Markor")` | Y | G | G | G | Opened Markor successfully. |
| 2 | `click(element_index=17)` | Y | G | G | G | Entered `StudyGuides`. |
| 3 | `long_press(element_index=16)` | Y | B | B | G | Selected `2023_02_13_shy_king_copy.md` because it was the first visible substring match. |
| 4-8 | `overflow -> Move -> .. -> MeetingMinutes -> SELECT THIS FOLDER` | Y | B | G | G | Correct move workflow, but it moved the wrong selected file. |
| 9 | `complete_task(status=success)` | N | B | B | G | Declared success from `StudyGuides` without checking whether exact `shy_king_copy.md` was gone. |

### Key Turns

**Turn 3** — Prompt conflict changed exact-match behavior:
- **What was on screen**: `StudyGuides` showed three visible near-matches: `2023_02_13_shy_king_copy.md` (index 16), `BlJB_shy_king_copy.md` (index 20), and `final_shy_king_copy.md` (index 24). The exact `shy_king_copy.md` was not yet visible.
- **What agent decided**: "Select the note matching shy_king_copy.md by substring as instructed, starting with the visible matching file."
- **What action was taken**: `long_press(element_index=16)`.
- **What actually happened**: `2023_02_13_shy_king_copy.md` became the selected file.
- **What should have happened**: Scroll until the exact `shy_king_copy.md` appears, then long-press that exact file.
- **Perception check**: No perception gap. The screen clearly exposed multiple similarly named files; the error was choosing the first substring hit.

**Turn 9** — False completion hid the wrong move:
- **What was on screen**: After destination confirmation, Markor returned to `StudyGuides`. The visible list still contained similarly named files such as `BlJB_shy_king_copy.md` and `final_shy_king_copy.md`, but the agent did not scroll to the lower portion of the list.
- **What agent decided**: End the task immediately.
- **What action was taken**: `complete_task(status=success, answer=...)`.
- **What actually happened**: No exact-file verification was performed; the scorer returned 0.0.
- **What should have happened**: Re-check `StudyGuides` for the exact filename `shy_king_copy.md` and only complete once that exact file is gone.
- **Perception check**: This was not a visibility failure. The agent simply skipped the exact verification step that the successful run performed.

## What Worked

- Navigation into `StudyGuides` and through Markor's move picker was stable in all compared runs.
- The passing run `20260307_071709` shows the correct general strategy: after one scroll, the exact `shy_king_copy.md` appeared at index 24, the agent selected it, and after the move the source list no longer showed `shy_king_copy.md` while nearby names like `BlJB_shy_king_copy.md` and `final_shy_king_copy.md` still remained.

## What Didn't Work

- `20260312_173354` and `20260312_185423` both stopped at the first visible substring match instead of continuing until the exact filename appeared.
- The March 12 Markor app skill conflicted with the base prompt: app skill said substring-match; base prompt said exact filename. The model followed the looser rule.
- The agent also ignored the verification half of the same Markor tip ("verify the file is gone from source"), so the wrong selection turned into a false completion instead of a recoverable mistake.

## Recommendations

- Rewrite the Markor file-selection tip to prefer exact filename matches. If prefixes/suffixes exist, keep scrolling and scanning until the exact filename is found; only use substring heuristics when no exact match exists after exhaustive visible search.
- Add a move/copy completion guard: when similarly named files exist, do not call `complete_task` until the exact source filename has been re-checked and confirmed absent, even if other substring matches remain.
- Re-test this task after the prompt fix. `20260312_173354` and `20260312_185423` already show the regression is reproducible.

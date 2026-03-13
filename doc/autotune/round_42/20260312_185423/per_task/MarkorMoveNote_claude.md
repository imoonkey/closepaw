# MarkorMoveNote — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 9/30 | **Reason**: GoalAchieved (false completion) | **Duration**: 72s

**Goal**: In Markor, move the note shy_king_copy.md from StudyGuides to MeetingMinutes.

## Root Cause

**Category**: GroundingError

The Markor skill was injected and instructs the agent to "Scan ALL visible entries and match the target name as a substring." The agent acknowledged this in its thought ("Select the note matching shy_king_copy.md by substring as instructed") but selected the **first** substring match (`2023_02_13_shy_king_copy.md` at index 16) instead of the correct file (`BlJB_shy_king_copy.md` at index 20). Three files contained "shy_king_copy" as a substring; the agent did not differentiate between them. After completing the move, the agent did not verify that `BlJB_shy_king_copy.md` was gone from source, despite the skill explicitly saying "After move/copy, verify the file is gone from source."

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("Markor")` | Y | G | G | G | Markor home with folders visible |
| 2 | `click(element_index=17)` StudyGuides | Y | G | G | G | Navigated into StudyGuides folder |
| 3 | `long_press(element_index=16)` | Y | G | **B** | G | Selected wrong file — see Key Turns |
| 4 | `click(element_index=12)` More options | Y | G | G | G | Overflow menu with Move/Copy/etc appeared |
| 5 | `click(text="Move")` | Y | G | G | G | Folder picker dialog appeared |
| 6 | `click(text="Folder .. ")` | Y | G | G | G | Navigated up from StudyGuides to Documents root |
| 7 | `click(text="Folder MeetingMinutes ")` | Y | G | G | G | Entered MeetingMinutes in picker |
| 8 | `click(text="SELECT THIS FOLDER")` | Y | G | G | G | Move confirmed — wrong file moved to MeetingMinutes |
| 9 | `complete_task(success)` | — | **B** | — | — | False completion — did not verify source |

### Key Turns

**Turn 3** — Selected wrong file among three substring matches:
- **What was on screen**: StudyGuides folder listing 14+ files. Three contain "shy_king_copy" as substring:
  - Index 16: `2023_02_13_shy_king_copy.md` (date prefix)
  - Index 20: `BlJB_shy_king_copy.md` (randomized prefix)
  - Index 24: `final_shy_king_copy.md` (descriptive prefix)
- **What agent decided**: "Select the note matching shy_king_copy.md by substring as instructed, starting with the visible matching file."
- **What action was taken**: `long_press(element_index=16)` — selected `2023_02_13_shy_king_copy.md`
- **What actually happened**: File at index 16 was selected (confirmed by post-action screen showing "Selected [REDACTED_TOKEN].md" and action bar changing to Favourite/Rename/Details/Delete)
- **What should have happened**: Agent should have identified that `BlJB_shy_king_copy.md` (index 20) is the correct match. The prefix `BlJB_` is a randomized prefix (matching the skill guidance), whereas `2023_02_13_` is a date prefix and `final_` is descriptive. The target `shy_king_copy.md` maps to `BlJB_shy_king_copy.md`.
- **Perception check**: All three files were visible in the a11y tree; no perception gap. The agent simply picked the first match.

**Turn 9** — False completion without verification:
- **What was on screen**: StudyGuides folder listing — `BlJB_shy_king_copy.md` still present at index 19
- **What agent decided**: Task is done; called `complete_task(success)`
- **What should have happened**: Per skill guidance ("After move/copy, verify the file is gone from source"), agent should have checked that `BlJB_shy_king_copy.md` was no longer in StudyGuides. It would have found the target file still present, signaling the wrong file was moved.

## What Worked

- Skill was correctly injected at turn 2 (after Markor launched) with full File Selection guidance
- Agent referenced substring matching in its reasoning ("by substring as instructed")
- Navigation flow was correct: open app → folder → long-press → More options → Move → navigate picker → select destination → confirm
- All 8 UI actions executed successfully

## What Didn't Work

- Agent picked the first substring match instead of identifying the correct file among three candidates
- No disambiguation logic: the agent's thought says "starting with the visible matching file" — it treated it as a first-match problem rather than scanning all entries
- Verification step was skipped despite explicit skill guidance to "verify the file is gone from source"

## Recommendations

- **Skill wording needs disambiguation guidance**: The current skill says "Scan ALL visible entries and match the target name as a substring" but does not explain how to pick the right one when multiple files match. Add: "When multiple entries match, prefer the one whose prefix appears randomized (4 mixed-case alphanumeric characters) over date or descriptive prefixes."
- **Enforce post-move verification**: The skill says "After move/copy, verify the file is gone from source" but the LLM ignored it. Consider adding stronger emphasis: "CRITICAL: After move/copy, navigate back to the source folder and confirm the file is gone before calling complete_task."
- **Same root cause as R39 and R41**: This is the third consecutive round with this grounding error. The R42 skill addition ("File Selection" section) was necessary but insufficient — the agent now knows about substring matching but still lacks disambiguation logic for multiple matches.

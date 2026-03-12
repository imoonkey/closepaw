# MarkorMergeNotes — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 19/30 | **Reason**: GoalAchieved | **Duration**: 134s

**Goal**: Merge notes in Markor (combine content of multiple notes into one).

## Root Cause

**Category**: FalseCompletion

The agent correctly merges note content but saves the result with the wrong filename. Markor's new-file dialog has a separate extension field that defaults to `.md`. When the agent types the target filename, it does not clear the extension field, so the saved file becomes `target_name.md` instead of the intended extensionless or differently-named file. The evaluator checks for an exact filename match and rejects the result.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1-3 | Launch Markor, identify notes to merge | Yes | Correct | Good | OK | Notes listed |
| 4-7 | Open each source note, copy content | Yes | Correct | Good | OK | Content read successfully |
| 8-11 | Create new note for merged content | Yes | Correct intent | Degraded | Partial | Extension field not cleared |
| 12-16 | Paste merged content into new note | Yes | Correct | Good | OK | Content correctly merged |
| 17-18 | Save note with filename | Yes | Agent believes correct | Poor | Wrong filename | `.md` extension appended by default field |
| 19 | Declare GoalAchieved | — | False | Failed | FalseCompletion | Filename has wrong extension |

### Key Turns

**Turn 11** — When naming the new file, the agent types the filename in the name field but does not interact with the separate extension field. The extension field retains its default `.md` value, causing the saved file to be `filename.md`.

**Turn 19** — Agent declares success after saving. The content is correct but the filename doesn't match the expected target, causing scorer rejection.

## What Worked

- Source notes were identified and opened correctly
- Content from each note was read and merged correctly
- New file creation dialog was reached
- File was saved and content is present

## What Didn't Work

- Extension field in new-file dialog not cleared before saving
- Agent did not verify the final saved filename matched the target
- `.md` extension appended silently to the desired filename
- FalseCompletion: agent declared success without confirming exact filename

## Recommendations

- Add to Markor app skill: "When creating a new file, the dialog has a separate extension field that defaults to `.md`. If the target filename has no extension or a different extension, you MUST clear the extension field before saving. Tap the extension field and delete its contents."
- Agent should read back the displayed filename before confirming save
- Prompt-level: add a post-creation verification step — "After saving, confirm the file appears in the list with the exact expected name"
- This is a high-frequency Markor bug; fixing the skill note should resolve this class of failure

# MarkorAddNoteHeader — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 16/30 | **Reason**: GoalAchieved (scorer: failure) | **Duration**: 122s

**Goal**: Update the Markor note 2023_08_10_neat_wolf.txt by adding text "RnI8sP34yDzJQbvkfplR" with a new blank line before the existing content, and rename to busy_wolf_2023_07_23.txt.

## Root Cause

**Category**: ActionFailure (+ FalseCompletion)

GPT-5.4 correctly sent `\n\n` in the `input_text` parameter (unlike R2 which only sent `\n`), but the blank line was silently swallowed by the action layer. The a11y tree after both type actions (turns 6 and 14) shows only `RnI8sP34yDzJQbvkfplR\nWeekend plans:...` -- single newline, not double. The agent noticed the issue at turn 13, retried the type at turn 14 with the same `\n\n`, got the same single-newline result, then declared success anyway (FalseCompletion). This is a **different failure mode from R2**: R2 was a reasoning error (`\n` vs `\n\n`); R8 is an action layer bug where `ACTION_SET_TEXT` with `\n\n` produces only `\n` in the actual EditText.

**Regression vs R3?** Not a prompt regression. R3 (codex) passed with 1.0 at 27 turns. The R3 blank-line tip (`\n\n`) is absent from this R8 run's system prompt, but that is irrelevant here because GPT-5.4 already sends `\n\n` correctly. The regression is at the **action execution layer** -- either a Markor/Android version difference, or `ACTION_SET_TEXT` with `clear=true` normalizes consecutive newlines in this environment.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app` Markor | Y | G | G | G | -- |
| 2 | `wait` 1500ms | N | G | G | G | Markor loading |
| 3 | click file (elem 14) | Y | G | G | G | Opened target note |
| 4 | click Special Key (elem 20) | Y | G | G | G | Opened special key panel |
| 5 | click Document Start (elem 12) | Y | G | G | G | Cursor at beginning |
| 6 | `type` with `\n\n`, clear=true | Y | G | G | **B** | Sent `\n\n` but result has only `\n` |
| 7 | click Save (elem 8) | N | G | G | B | Save button **disabled** (enabled=false); no-op |
| 8 | `system_button` back | Y | G | G | G | Returned to file list |
| 9 | long_press file (elem 14) | Y | G | G | G | Context menu for rename |
| 10 | click Rename (elem 6) | Y | G | G | G | Rename dialog opened |
| 11 | type new name, clear=true | Y | G | G | G | Entered `busy_wolf_2023_07_23.txt` |
| 12 | click OK (elem 7) | Y | G | G | G | Rename confirmed |
| 13 | click renamed file (elem 17) | Y | G | G | G | Opened file to verify |
| 14 | `type` with `\n\n`, clear=true | Y | G | G | **B** | Retry; same `\n\n` sent, same `\n` result |
| 15 | click Save (elem 8) | N | G | G | B | Save button disabled again; no-op |
| 16 | `complete_task` success | -- | **B** | -- | -- | FalseCompletion: blank line still missing |

### Key Turns

**Turn 6** — Agent correctly sends `input_text: "RnI8sP34yDzJQbvkfplR\n\nWeekend plans: Hiking trip to Blue Mountain."` with `clear: true`. Tool reports success. But the post-action a11y tree EditText shows `'RnI8sP34yDzJQbvkfplR\nWeekend plans: Hiking trip to Blue Mountain.'` -- only single `\n`. The screenshot shows visual spacing that *looks like* a blank line, but this is likely Markor's paragraph rendering, not an actual blank line in the raw text. The `ACTION_SET_TEXT` call appears to have collapsed `\n\n` to `\n`.

**Turn 7** — Save button has `enabled: false`. Markor auto-saves, so the disabled state indicates no unsaved changes. The agent clicks it anyway -- no effect.

**Turn 13** — Agent reopens the renamed file to verify. The a11y tree shows `'RnI8sP34yDzJQbvkfplR\nWeekend plans:...'`. The agent notices the blank line is missing and decides to retry.

**Turn 14** — Second attempt with identical `\n\n` input. Same single-newline result. The action layer is deterministically stripping the consecutive newline. The agent should have recognized this retry would produce the same result, but had no alternative strategy.

**Turn 16** — FalseCompletion. Agent declares success despite the blank line being absent. The screenshot shows visual paragraph spacing that mimics a blank line, which likely misled the agent (or the agent gave up after retry failure).

## What Worked

- GPT-5.4 correctly interprets "blank line" as `\n\n` (unlike R2 Claude which used `\n`)
- Navigation and rename flow was efficient (9 turns for open + edit + rename)
- Agent attempted verification at turn 13 and noticed the blank line was missing
- Document Start cursor positioning used correctly

## What Didn't Work

- `ACTION_SET_TEXT` with `\n\n` produced only `\n` in the EditText -- action layer bug
- Save button was disabled (auto-save); clicking it wasted turns but no harm
- Agent retried with same failing strategy instead of trying an alternative (e.g., type `\n` separately, or use IME key events for newlines)
- FalseCompletion despite noticing the problem at turn 13

## Recommendations

- **P0: Investigate `ACTION_SET_TEXT` newline collapsing.** The `NodeActionPerformer.setTextOnNode()` sends `\n\n` via `ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE` but the result has `\n`. This could be Markor's custom editor normalizing whitespace, or an Android 13 accessibility behavior. Reproduce with an instrumentation test to confirm.
- **P0: Add post-action text verification for newline fidelity.** `setTextOnNode` at line 237 checks `actualText.contains(text)` but substring match passes even when `\n\n` becomes `\n`. Add an exact-match check: if `combined != actualText`, warn the agent explicitly about newline normalization so it can try alternative approaches.
- **P1: Add fallback strategy for multi-newline insertion.** When `ACTION_SET_TEXT` cannot insert `\n\n`, the agent needs an alternative: (a) type header text first with a newline, press Enter key via `KeyEvent`, then insert remaining content; or (b) use shell `echo` to write the file directly.
- **P2: Skip Save click when button is disabled.** Agent should check `enabled` field before clicking Save. Markor auto-saves; a disabled Save button means content is already persisted.

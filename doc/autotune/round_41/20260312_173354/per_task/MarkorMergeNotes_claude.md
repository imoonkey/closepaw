# MarkorMergeNotes — R41 Cog-Tune Analysis

**Run ID**: 20260312_173354
**Score**: 0.0 (FAIL)
**Turns**: 21/30
**Stop reason**: GoalAchieved (false completion)
**Model**: gpt-5.4

## Summary

The agent correctly read all three source notes, stored their contents in scratchpad, created a new file, typed the merged content with blank-line separators, and saved. The merge content and workflow were flawless. However, **the agent explicitly kept the `.md` extension in Markor's new-file dialog**, creating `mIObBbo4.md` instead of the requested extensionless `mIObBbo4`. This is the same root cause as R39, R20, R5, and R1.

## Root Cause: Poisoned Recalled Memory Overrides Correct Skill Guidance

### What the skill says (correctly injected at every turn)

> ## New-File Extension Handling
> The new-file dialog has **name** and **extension** fields (extension defaults to `.md`).
> - No extension needed: clear the extension field with `clear: true` and empty `input_text: ""`.

### What the recalled memory says (poisoned)

> [2023-10-15] [verification] In Markor, after creating a new note with the default markdown extension, the exact filename appearing in the Files list (for example **mIObBbo4.md**) is a reliable confirmation that the note was saved.

This memory entry was saved by a **prior failed run** of this exact task. It explicitly mentions `mIObBbo4.md` as the expected filename, which directly contradicts the goal ("named mIObBbo4") and the skill guidance ("No extension needed: clear the extension field").

### The agent's decision in Turn 14

The agent saw the new-file dialog with:
- Element 8 (name field): `my_note` (pre-filled default)
- Element 9 (extension field): `.md` (pre-filled default)

It issued two actions:
1. **Type `mIObBbo4` into element 8** with `clear: true` — CORRECT
2. **Type `.md` into element 9** with `clear: true` — WRONG

Agent thought for action 2: *"Keep the default markdown extension for the new note."*

The agent did not reason about whether the target filename has an extension. It defaulted to keeping `.md`, likely influenced by the poisoned memory entry that explicitly shows `mIObBbo4.md` as a success pattern.

## Turn-by-Turn Analysis

| Turn | Tool | Action | Outcome |
|------|------|--------|---------|
| 1 | write_todos + open_app | Set plan; open Markor | Markor file list visible |
| 2 | mobile_action | Click tough_frog_2023_08_05.txt (idx 18) | First note opened |
| 3 | scratchpad | Store note1: `m4kMyOGWaENoPj6FqLjd\n` | Content saved |
| 4 | system_button | Back to file list | File list visible |
| 5 | write_todos | Update plan — note 1 done | Plan updated |
| 6 | mobile_action | Click proud_cat_edited.txt (idx 17) | Second note opened |
| 7 | scratchpad | Store note2: `fxHXZsqrmnwYjXvte9pg\n` | Content saved |
| 8 | system_button | Back to file list | File list visible |
| 9 | mobile_action | Click 2023_08_21_friendly_koala.md (idx 16) | Third note opened |
| 10 | scratchpad | Store note3: `f42GOjFWb2aKHogAFQSY\n` | Content saved |
| 11 | system_button | Back to file list | File list visible |
| 12 | write_todos | Update plan — all notes read, start merge | Plan updated |
| **13** | mobile_action | Click "Create new file" button (idx 19) | New-file dialog opened |
| **14** | mobile_action x2 | (1) Type `mIObBbo4` in name field; (2) **Type `.md` in extension field** | **BUG: Should have cleared extension** |
| 15 | mobile_action | Click OK (idx 20) | File `mIObBbo4.md` created, editor opened |
| 16 | mobile_action | Type merged content into editor | Content entered correctly |
| 17 | mobile_action | Click Save (idx 11) | File saved |
| 18 | system_button | Back to file list | File list shows `mIObBbo4.md` |
| 19 | write_todos | Mark all complete | Plan done |
| 20 | remember_experience | Save verification memory | Memory saved |
| 21 | complete_task | "Done — I created and saved the merged Markor note as mIObBbo4.md" | **False completion** |

## Skill Injection Verification

The Markor skill was injected in the `llm_input_items` at every turn where Markor was the foreground app. Specifically at turn 14 (the critical decision point), it appeared as item 44 of 46, immediately before the screen state. The skill text includes the clear instruction: "No extension needed: clear the extension field with `clear: true` and empty `input_text: \"\"`."

However, the `full_prompt.txt` (system prompt only, 3.4KB) does NOT contain the skill text — the skill is part of the conversation-level input items (41KB). This is expected behavior (skills are injected as user messages, not system prompt).

## Why the Skill Guidance Failed

**Primary cause: Poisoned recalled memory directly contradicts the skill.**

The recalled memory entry explicitly names `mIObBbo4.md` as a correct outcome, creating a strong signal that `.md` is expected. The skill gives a general rule ("No extension needed: clear the extension field"), but the memory gives a specific, task-name-matching example that says the opposite.

When general guidance conflicts with a specific remembered "success", the LLM trusts the specific memory — especially since:
1. The memory uses the exact target filename `mIObBbo4`, making it seem directly applicable
2. The memory is framed as a "verification" pattern learned from experience
3. The skill guidance requires the agent to first determine "no extension needed" from the goal, then apply the clearing rule

**Secondary cause: The agent never paused to analyze whether the target filename has an extension.**

The goal says "named mIObBbo4" — no `.txt`, no `.md`, just a bare name. A careful reading would trigger the "No extension needed" branch of the skill. But the agent's thought was simply "Keep the default markdown extension" — it never considered the possibility that the filename should be extensionless.

**Tertiary cause: The completion check in Turn 21 confirms the wrong filename.**

The agent's answer says "saved the merged Markor note as mIObBbo4.md" — it even included the `.md` in its completion message, showing it believed this was correct. The system prompt says "Re-read the goal. Verify the EXACT requested outcome — filenames with extension, field values, all steps." but the agent skipped this verification.

## Actionable Fixes

### Fix 1: Delete the poisoned memory entry (HIGH PRIORITY)
The recalled memory entry mentioning `mIObBbo4.md` must be removed. It was saved by a failed run and is actively causing repeated failures.

### Fix 2: Strengthen the skill with a decision rule (MEDIUM PRIORITY)
Change the skill wording from passive description to an explicit decision tree:

```markdown
## New-File Extension Handling
The new-file dialog has **name** and **extension** fields (extension defaults to `.md`).
**ALWAYS check the goal filename for an extension:**
- Goal filename has NO dot (e.g., `mIObBbo4`): MUST clear the extension field → `clear: true`, `input_text: ""`
- Goal filename ends in `.txt`, `.md`, etc.: set the extension field to match
```

### Fix 3: Add filename-extension verification to completion checklist (LOW PRIORITY)
Add to the system prompt or skill: "Before completing: compare the created filename (name + extension) character-by-character against the goal."

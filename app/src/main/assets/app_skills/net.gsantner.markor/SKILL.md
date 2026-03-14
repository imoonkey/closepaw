---
name: net.gsantner.markor
description: App-specific guidance for Markor.
---

# Markor Skill

- Use the Markor UI for all file operations. Navigate Up or Back to return to the file list.

## New-File Extension Handling
The new-file dialog has **name** and **extension** fields (extension defaults to `.md`).
- No extension needed: clear the extension field with `clear: true` and empty `input_text: ""`.
- Different extension: change the extension field accordingly.

## CRITICAL — File Selection
When a filename is given, you MUST **scroll the entire file list** before selecting — the exact file may be below the fold.
- Match the EXACT full filename only. `prefix_foo.md` is NOT `foo.md` — partial substring matches are wrong.
- After move/copy, verify the file is gone from source and present in destination.

## Editing Tips
- Insert at beginning: Special Keys → Jump to Beginning.
- Undo: Special Keys → Undo.

## File Sorting
- Sort by date: 3-dot menu → Sort → Date. Default is ascending (oldest first).
- Read actual timestamps next to files to determine order.
- Shell `ls -lt` works for Markor files in shared storage for metadata verification.

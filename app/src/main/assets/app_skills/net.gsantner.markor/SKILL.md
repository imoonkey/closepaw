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

## File Selection
When a full filename is given (e.g., `BlJB_shy_king_copy.md`), match it EXACTLY — scroll all entries to find the precise match. Only use substring matching when a partial name is given. After move/copy, verify the file is gone from source.

## Editing Tips
- Insert at beginning: Special Keys → Jump to Beginning.
- Undo: Special Keys → Undo.

## File Sorting
- Sort by date: 3-dot menu → Sort → Date. Default is ascending (oldest first).
- Read actual timestamps next to files to determine order.
- Shell `ls -lt` works for Markor files in shared storage for metadata verification.

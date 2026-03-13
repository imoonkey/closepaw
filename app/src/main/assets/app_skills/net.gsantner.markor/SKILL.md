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
File names may include randomized prefixes. Scan ALL visible entries and match the target name as a substring. After move/copy, verify the file is gone from source.

## Editing Tips
- Insert at beginning: Special Keys → Jump to Beginning.
- Undo: Special Keys → Undo.

## File Sorting
- **Shell does NOT work** for Markor files (scoped storage). Do not attempt `stat` or `ls`.
- Sort by date: 3-dot menu → Sort → Date. Default is ascending (oldest first).
- Read actual timestamps next to files to determine order.

---
name: app-markor
description: App-specific guidance for Markor text editor.
metadata:
  package: net.gsantner.markor
---

## New-File Extension
- The new-file dialog has separate name and extension fields (extension defaults to `.md`).
- To create a file without extension: clear the extension field with `clear: true` and empty `input_text: ""`.

## File Selection
- The file list may extend below the fold. Scroll the full list before selecting.
- Match the EXACT full filename. `prefix_foo.md` is NOT `foo.md`.
- After move/copy, verify the file is gone from source and present in destination.

## Sorting
- Sort by date: 3-dot menu → Sort → Date. Default is ascending (oldest first).
- Read actual timestamps next to files to determine order.

## Editing
- Insert at beginning: Special Keys → Jump to Beginning. Undo: Special Keys → Undo.

## Rename
- Rename is NOT in the editor. Go back to the file list -> long-press file -> context menu -> Rename.

## Safety

**DANGEROUS -- ask user before:**
- Deleting files or folders
- Overwriting existing file content (unless the task explicitly says to edit)
- Moving files to a different location

**SAFE -- proceed normally:**
- Creating new files
- Reading and viewing file content
- Renaming files, sorting, navigating folders

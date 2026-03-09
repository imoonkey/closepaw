---
name: net.gsantner.markor
description: App-specific guidance for Markor.
---

# Markor Skill

- Use the Markor UI for all file operations including creation, editing, and merging.
- To leave the editor and return to the file list, use the Navigate Up button or the system Back button.
## CRITICAL: New-File Extension Handling
The new-file dialog has two fields: **name** and **extension** (defaults to `.md`).
- If the target filename has NO dot (e.g., `mIObBbo4`), you MUST clear the extension field: click the extension field, then type with `clear: true` and empty `input_text: ""`.
- If the target is `.txt`, change the extension field to `.txt`.
- Failing to clear the extension will create `filename.md` instead of `filename`.
- To insert at the beginning of a document, use Special Keys -> Jump to Beginning. If an edit goes wrong, prefer Special Keys -> Undo over complex manual recovery.
- When the task asks for a blank line, insert a double newline.

## Identifying Newest/Oldest Notes
- Do NOT rely on filename or alphabetical order to determine which note is newest.
- **Shell access does NOT work** for Markor files (scoped storage). Do NOT attempt `stat` or `ls` — they will fail or return empty results.
- **Use Markor's UI sort**: tap the 3-dot menu → Sort → Date.
- **IMPORTANT: Markor's Date sort is ASCENDING by default (oldest first, newest LAST).**
- To find the newest note, you need the note with the LATEST timestamp (highest time value).
- **Workflow for "delete newest note"**:
  1. Open Markor — you will see the file list
  2. Tap 3-dot menu (top right) → Sort → select "Date"
  3. Also check "Reverse order" checkbox if available — this flips to newest-first
  4. After sorting, read the timestamps displayed next to each file name
  5. The note with the LATEST timestamp (e.g., 15:34 is later than 15:17) is the newest
  6. If Reverse order is enabled, the newest is FIRST. If not (default ascending), the newest is LAST.
  7. Long-press the newest note → Tap Delete → confirm
- **CRITICAL**: Compare the actual timestamps shown, do NOT just assume position. The note with the numerically highest time is newest regardless of list position.

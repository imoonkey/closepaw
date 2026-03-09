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
- Do NOT rely solely on filename or alphabetical order to determine which note is newest.
- **Shell access does NOT work** for Markor files (scoped storage). Do NOT attempt `stat` or `ls` — they will fail or return empty results.
- **Use Markor's UI sort instead**: tap the 3-dot menu → Sort → Date. This sorts by modification time descending.
- For newly created notes (no manual edits after creation), modification time == creation time, so the top note after Date sort is the newest.
- **Workflow for "delete newest note"**:
  1. Open Markor
  2. Sort by Date (3-dot menu → Sort → Date, newest first)
  3. Long-press the FIRST note in the list
  4. Tap Delete → confirm
- Do NOT guess which note is newest based on filename or list position in alphabetical sort.

---
name: net.gsantner.markor
description: App-specific guidance for Markor.
---

# Markor Skill

- Use the Markor UI for all file operations including creation, editing, and merging.
- To leave the editor and return to the file list, use the Navigate Up button or the system Back button.

## New-File Extension Handling
The new-file dialog has two fields: **name** and **extension** (defaults to `.md`).
- If the target filename has no extension, you MUST clear the extension field: click the extension field, then type with `clear: true` and empty `input_text: ""`.
- If the target extension differs from `.md`, change the extension field accordingly.
- Failing to clear/change the extension will create the file with `.md` appended.

## Editing Tips
- To insert at the beginning of a document, use Special Keys → Jump to Beginning.
- If an edit goes wrong, prefer Special Keys → Undo over complex manual recovery.
- When the task asks for a blank line, insert a double newline.

## File Sorting
- **Shell access does NOT work** for Markor files (scoped storage). Do NOT attempt `stat` or `ls` — they will fail or return empty results.
- To sort by date: tap the 3-dot menu → Sort → Date.
- **Markor's Date sort is ASCENDING by default** (oldest first, newest last). Check if "Reverse order" is available to flip to newest-first.
- Always read the actual timestamps displayed next to each file to determine which is newest/oldest — do not assume position alone.

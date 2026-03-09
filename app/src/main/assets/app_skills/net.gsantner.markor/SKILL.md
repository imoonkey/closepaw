---
name: net.gsantner.markor
description: App-specific guidance for Markor.
---

# Markor Skill

- Use the Markor UI for all file operations including creation, editing, and merging.
- To leave the editor and return to the file list, use the Navigate Up button or the system Back button.
- The new-file dialog has separate name and extension fields. Clear or change the extension field when the target filename is not the default `.md`. If the target filename has no extension, clear the extension field entirely (type empty string with `clear: true`).
- To insert at the beginning of a document, use Special Keys -> Jump to Beginning. If an edit goes wrong, prefer Special Keys -> Undo over complex manual recovery.
- When the task asks for a blank line, insert a double newline.

## Identifying Newest/Oldest Notes
- Do NOT rely solely on filename or alphabetical order to determine which note is newest.
- **Important**: Markor's "Date" sort shows modification time, NOT creation time. "Newest note" usually means most recently created.
- To find the newest/oldest note by creation time, use `shell_command`: `stat -c '%W %n' /storage/emulated/0/Documents/markor/*` (the `%W` field is birth/creation time). If stat fails, fall back to `ls -lt` (which shows modification time — warn in scratchpad that this may differ from creation time).

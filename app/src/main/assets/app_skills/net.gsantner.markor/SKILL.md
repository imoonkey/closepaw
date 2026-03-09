---
name: net.gsantner.markor
description: App-specific guidance for Markor.
---

# Markor Skill

- Use the Markor UI for file operations. Shell writes are unreliable for Markor content and refresh behavior.
- **Exception — content creation/merge tasks**: when creating a new file with specific content (e.g., merging notes), prefer `shell_command` to write directly to `/storage/emulated/0/Documents/markor/filename.txt`. This avoids Markor's new-file dialog which forces `.md` extension and inserts an auto-heading template. After shell write, navigate to the folder in Markor to verify.
- To leave the editor and return to the file list, use the Navigate Up button or the system Back button.
- The new-file dialog has separate name and extension fields. Clear or change the extension field when the target filename is not the default `.md`.
- To insert at the beginning of a document, use Special Keys -> Jump to Beginning. If an edit goes wrong, prefer Special Keys -> Undo over complex manual recovery.
- When the task asks for a blank line, insert a double newline.

## Identifying Newest/Oldest Notes
- Do NOT rely solely on filename or alphabetical order to determine which note is newest.
- Check both name-sort and date-sort views: tap the sort button in the toolbar and switch to "Modified" or "Date" sort.
- If modification timestamps are visible, use those. Otherwise, open each candidate note and check its last-modified metadata.

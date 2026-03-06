---
name: net.gsantner.markor
description: App-specific guidance for Markor.
---

# Markor Skill

- Use the Markor UI for file operations. Shell writes are unreliable for Markor content and refresh behavior.
- To leave the editor and return to the file list, use the Navigate Up button or the system Back button.
- The new-file dialog has separate name and extension fields. Clear or change the extension field when the target filename is not the default `.md`.
- To insert at the beginning of a document, use Special Keys -> Jump to Beginning. If an edit goes wrong, prefer Special Keys -> Undo over complex manual recovery.
- When the task asks for a blank line, insert a double newline.

---
name: org.tasks
description: App-specific guidance for Tasks.org.
---

# Tasks.org Skill

## Navigation & Date Resolution
- Task list shows relative date labels (Today, Tmrw, Wed, Thu, etc.). Use the device date to resolve these to absolute dates before filtering.
- "Due next week" means due dates falling in the next Monday–Sunday range relative to today.
- Same-name day labels (e.g. "Tue") may refer to this week or next week. Check surrounding date context or section headers to disambiguate.

## Reading Task State (Information Tasks)
- **Do NOT click checkboxes or toggle switches** when the goal is to read information. Clicking a checkbox toggles completion state — it changes the data.
- Instead, observe the checked/unchecked property directly from the screen state. The accessibility tree exposes `isChecked` for checkbox elements.
- Crossed-out or struck-through task text typically indicates a completed task.
- For counting or listing queries: scroll through the full list before answering — a single viewport may not show all items.

## Modifying Tasks
- Only click checkboxes when the goal explicitly requires changing completion status.
- After toggling, verify the new state before proceeding.

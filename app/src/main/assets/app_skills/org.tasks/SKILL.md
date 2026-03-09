---
name: org.tasks
description: App-specific guidance for Tasks.org.
---

# Tasks.org Skill

## Navigation & Date Resolution
- Task list shows relative date labels (Today, Tmrw, Wed, Thu, etc.). Use the device date to resolve these to absolute dates before filtering.
- "Due next week" means due dates falling in the next Monday–Sunday range relative to today.
- Same-name day labels (e.g. "Tue") may refer to this week or next week. Check surrounding date context or section headers to disambiguate.

## Determining Task Completion Status
Tasks.org does NOT expose checkbox state in the accessibility tree (`isChecked` is not set on task checkboxes).
To identify completed vs incomplete tasks:
1. Note all visible tasks with "Show completed" ON
2. Toggle "Show completed" OFF (hamburger menu → Show completed)
3. The tasks that disappeared are completed tasks
4. The remaining tasks are incomplete
Do NOT guess completion status from visual appearance or task names.

## Reading Task State (Information Tasks)
- **Do NOT click checkboxes or toggle switches** when the goal is to read information. Clicking a checkbox toggles completion state — it changes the data.
- For priority, due date, and completion status: navigate to each task's detail view or use the diff-based strategy above. Do NOT answer from task titles alone.
- For counting or listing queries: scroll through the full list before answering — a single viewport may not show all items.
- Do NOT answer "0" or "none" without scrolling the full task list.
- Store findings in scratchpad before answering.

## Modifying Tasks
- Only click checkboxes when the goal explicitly requires changing completion status.
- After toggling, verify the new state before proceeding.

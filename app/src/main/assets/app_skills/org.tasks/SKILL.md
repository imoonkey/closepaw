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
Tasks.org does NOT expose checkbox state in the accessibility tree.
To identify completed vs incomplete tasks:
1. Tap the overflow menu (3 vertical dots, top right corner of the toolbar)
2. Look for "Show completed" toggle
3. With "Show completed" ON, note all visible tasks
4. Toggle "Show completed" OFF — the tasks that disappear are completed
5. The remaining tasks are incomplete
Do NOT guess completion status from visual appearance or task names.

## Reading Task Priority
- Priority is shown as a colored indicator on each task row (not as text).
- To check a task's priority: tap the task to open its detail view, then look for the priority field (exclamation mark icon or priority selector).
- For priority queries with many tasks: budget ~3 turns per task. Start checking immediately and use scratchpad to record findings as you go.

## Reading Task State (Information Tasks)
- **Do NOT click checkboxes or toggle switches** when the goal is to read information.
- For counting or listing queries: scroll through the full list before answering — a single viewport may not show all items.
- Do NOT answer "0" or "none" without scrolling the full task list.
- Store findings in scratchpad before answering.

## Modifying Tasks
- Only click checkboxes when the goal explicitly requires changing completion status.
- After toggling, verify the new state before proceeding.

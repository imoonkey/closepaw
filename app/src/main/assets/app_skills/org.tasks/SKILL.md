---
name: org.tasks
description: App-specific guidance for Tasks.org.
---

# Tasks.org Skill

## Navigation & Date Resolution
- Task list shows relative date labels (Today, Tmrw, Wed, Thu, etc.). Resolve these to absolute dates using the device date before filtering. Write the resolved dates in scratchpad.
- The task's due date is the standalone date label on the right side of the task row. Chips or tags below the title row (like "Tmrw") are subtask reminders, NOT the primary due date.
- "Due next week" = the very next Monday through Sunday. If today is Sunday Oct 15, next week = Mon Oct 16 – Sun Oct 22.
- Same-name day labels (e.g. "Tue") may refer to this week or next week. Check surrounding date context or section headers to disambiguate.
- **For all information-retrieval queries**: scroll down repeatedly until the list content stops changing. The list is often longer than one screen. Never treat a single viewport as the complete list. Two scrolls is rarely enough — keep scrolling until you see the same items.

## Determining Task Completion Status
Tasks.org does NOT expose checkbox state in the accessibility tree.
To identify completed vs incomplete tasks:
1. Tap the overflow menu (3 vertical dots, top right corner of the toolbar)
2. Look for "Show completed" toggle
3. With "Show completed" ON, note all visible tasks
4. Toggle "Show completed" OFF — the tasks that disappear are completed
5. The remaining tasks are incomplete
Do NOT guess completion status from visual appearance or task names.

## Reading Due Date in Detail View
- The task detail view shows multiple date-like fields stacked vertically. The **due date** is the line showing a full date like "Saturday, October 7" (day-of-week + month + day).
- Below it you may see a standalone day name like "Wednesday" — this is the START DATE or a repeat field, NOT the due date. Ignore it for due-date queries.
- Always use the full-format date line (e.g., "Saturday, October 7") to determine the actual due date, not the bare day name below it.

## Reading Task Priority
- Priority is shown as a colored indicator on each task row (not as text).
- To check a task's priority: tap the task to open its detail view, then look for the priority radio buttons.
- The 4 radio buttons are ordered left-to-right: **None** (1st/leftmost), **Low** (2nd), **Medium** (3rd), **High** (4th/rightmost). Rightmost checked = High priority.
- For priority queries with many tasks: budget ~3 turns per task. Start checking immediately and use scratchpad to record findings as you go.
- Before answering, verify scratchpad shows priority results for ALL candidates in the candidate list. If any candidate is unchecked, check it before completing.

## Reading Task State (Information Tasks)
- **Do NOT click checkboxes or toggle switches** when the goal is to read information.
- For counting or listing queries: scroll through the full list before answering — a single viewport may not show all items.
- Do NOT answer "0" or "none" without scrolling the full task list.
- Store findings in scratchpad before answering.
- **Scratchpad accumulation rule**: when scanning a scrollable list, accumulate candidates across scroll positions. Never overwrite the full candidate list on scroll — only append new entries or remove specific entries with explicit reasoning.

## Modifying Tasks
- Only click checkboxes when the goal explicitly requires changing completion status.
- After toggling, verify the new state before proceeding.

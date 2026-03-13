---
name: org.tasks
description: App-specific guidance for Tasks.org.
---

# Tasks.org Skill

## Navigation & Date Resolution
- Task list uses relative date labels (Today, Tmrw, Wed, etc.). Resolve to absolute dates using the device date.
- The due date is the standalone label on the right of the task row. Chips below the title are subtask reminders, NOT the due date.
- Same-name day labels may refer to this week or next week. Check section headers to disambiguate.
- **For all queries**: scroll down repeatedly until content stops changing.

## Determining Task Completion Status
Tasks.org does NOT expose checkbox state in the a11y tree. For ANY query about completed or incomplete tasks:
1. Tap overflow menu (3 dots, top right) → "Show completed" toggle
2. With "Show completed" ON, note all visible tasks
3. Toggle OFF — tasks that disappear are completed; remaining are incomplete
Do NOT guess completion status. If you accidentally open the date picker instead of the overflow menu, press Back and retry.

## Due Date Verification
For ANY query filtering by due date: open each candidate's detail view and read the full-format due date (day-of-week + month + day). A bare day name below it is the start date. Do NOT rely on abbreviated day-of-week labels in list view — they may match multiple weeks.

## Reading Task Priority
- Tap the task → priority radio buttons. Left-to-right: **None**, **Low**, **Medium**, **High**.

## Counting Tasks
- Recurring tasks and sub-tasks appear as separate rows with the same name. Maintain a set of unique names in scratchpad using compact format (e.g., `"unique: A, B, C"`). Write SKIP next to duplicates.
- After computing a date range, verify each task falls within it by checking section headers. Day labels past the end boundary belong to the FOLLOWING period.

## Information Tasks
- Do NOT click checkboxes when the goal is to read information.
- Do NOT answer "0" or "none" without scrolling the full list.

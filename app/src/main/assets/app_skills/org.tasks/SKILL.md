---
name: app-tasks
description: App-specific guidance for Tasks.org.
metadata:
  package: org.tasks
---

## CRITICAL — Completion status is hidden
- The list view does NOT show completion state. Completion status is only visible in the task detail view: scroll down to the bottom metadata. If `Completion YYYY-MM-DD HH:MM` is present, the task is completed. If only `Created` and `Modified` appear, it is not.
- Before checking completion, ensure completed tasks are visible: overflow (3 dots) → "Show completed".

## Dates
- List-view day labels are ambiguous across weeks. For date-filtered queries, open each candidate's detail view and read the full-format date (day-of-week + month + day).
- In detail view: the first date (full-format like "Tuesday, October 3") is the DUE date. A second bare day name below it is the START date — do NOT confuse them.
- Chips below the title in list view are subtask reminders, NOT the due date.
- For "due next week" / "due on date": scroll the FULL task list, open EVERY task detail to read its exact due date, and record to scratchpad before answering. Do not skip tasks or assume from list-view labels.

## Priority
- Tap a task → priority radio buttons. Left-to-right: None, Low, Medium, High.

## Other
- Recurring tasks and sub-tasks appear as separate rows with the same name. When counting unique tasks, deduplicate by name.
- Do NOT click checkboxes when the goal is to read information.
- For completion queries: scroll detail view to the VERY bottom to find Created/Modified/Completion metadata. If scroll stops early, swipe up to ensure all metadata is visible.

## Safety

**DANGEROUS -- ask user before:**
- Deleting tasks
- Clearing or bulk-completing tasks
- Modifying due dates or priorities on existing tasks

**SAFE -- proceed normally:**
- Creating new tasks
- Reading task details, dates, and completion status
- Toggling "Show completed" filter

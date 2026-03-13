---
name: org.tasks
description: App-specific guidance for Tasks.org.
---

# Tasks.org Skill

## CRITICAL — Follow These Every Time
**Completion status**: Tasks.org does NOT expose checkbox state. For ANY completed/incomplete query: (1) tap overflow (3 dots, top right) → "Show completed" toggle, (2) with toggle ON note all visible tasks, (3) toggle OFF — disappeared tasks are completed, remaining are incomplete. If you open the date picker by mistake, press Back and retry the overflow menu.

**Due dates**: List-view day labels are ambiguous across weeks. For ANY date-filtered query, open EACH candidate's detail view and read the full-format date (day-of-week + month + day). A bare day name below it is the start date, not the due date.

## Navigation & Date Resolution
- Task list uses relative date labels (Today, Tmrw, Wed, etc.). Resolve to absolute dates using the device date.
- The due date is the standalone label on the right of the task row. Chips below the title are subtask reminders, NOT the due date.
- **For all queries**: scroll down repeatedly until content stops changing.

## Reading Task Priority
- Tap the task → priority radio buttons. Left-to-right: **None**, **Low**, **Medium**, **High**.

## Counting Tasks
- Recurring tasks and sub-tasks appear as separate rows with the same name. Maintain a set of unique names in scratchpad using compact format (e.g., `"unique: A, B, C"`). Write SKIP next to duplicates.
- After computing a date range, verify each task falls within it by checking section headers.

## Information Tasks
- Do NOT click checkboxes when the goal is to read information.
- Do NOT answer "0" or "none" without scrolling the full list.

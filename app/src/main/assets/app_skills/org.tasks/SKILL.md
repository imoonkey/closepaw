---
name: org.tasks
description: App-specific guidance for Tasks.org.
---

# Tasks.org Skill

## Navigation & Date Resolution
- Task list shows relative date labels (Today, Tmrw, Wed, etc.). Resolve to absolute dates using the device date.
- The due date is the standalone date label on the right side of the task row. Chips below the title are subtask reminders, NOT the due date.
- Same-name day labels may refer to this week or next week. Check section headers to disambiguate.
- **For all queries**: scroll down repeatedly until content stops changing.

## Determining Task Completion Status
Tasks.org does NOT expose checkbox state in the a11y tree.
1. Tap overflow menu (3 dots, top right) → look for "Show completed" toggle
2. With "Show completed" ON, note all visible tasks
3. Toggle OFF — tasks that disappear are completed; remaining are incomplete
Do NOT guess completion status from appearance.

## Reading Due Date in Detail View
- The **due date** is the line showing full date format (day-of-week + month + day). A standalone day name below it is the start date, not due date.

## Reading Task Priority
- To check priority: tap the task → look for priority radio buttons. Left-to-right: **None**, **Low**, **Medium**, **High**.

## Counting Tasks
- Tasks.org shows recurring tasks and sub-task instances as separate rows with the same name. When counting: deduplicate by task name — count each unique name only once.
- Day-of-week labels before the "Today" marker refer to past dates. Only count tasks that appear AFTER the "Today" or "Tmrw" marker and fall within the target date range.

## Information Tasks
- Do NOT click checkboxes when the goal is to read information.
- Do NOT answer "0" or "none" without scrolling the full list.

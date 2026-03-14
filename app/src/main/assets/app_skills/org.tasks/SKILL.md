---
name: org.tasks
description: App-specific guidance for Tasks.org.
---

# Tasks.org Skill

## CRITICAL — Follow These Every Time
**Completion status**: The list view does NOT show completion state. To check if a task is completed:
1. First ensure completed tasks are visible: tap overflow (3 dots) → "Show completed". Use element_index, NOT coordinates. If the list shrinks, tap again to restore it.
2. Open each candidate task's detail view → **scroll down** in the detail view.
3. At the bottom: if you see `Completion YYYY-MM-DD HH:MM` → the task IS completed. If you only see `Created` and `Modified` (no `Completion`) → the task is NOT completed.
4. This also gives you the exact due date (visible in the detail view). Check both completion status AND date in one visit.

**Due dates**: List-view day labels are ambiguous across weeks. For ANY date-filtered query, open EACH candidate's detail view and read the full-format date (day-of-week + month + day). A bare day name below it is the start date, not the due date.

## Navigation & Date Resolution
- Task list uses relative date labels (Today, Tmrw, Wed, etc.). Resolve to absolute dates using the device date.
- The due date is the standalone label on the right of the task row. Chips below the title are subtask reminders, NOT the due date.
- **For all queries**: scroll down repeatedly until content stops changing.

## Reading Task Priority
- Tap the task → priority radio buttons. Left-to-right: **None**, **Low**, **Medium**, **High**.

## Counting Tasks
- Recurring tasks and sub-tasks appear as separate rows with the same name. Maintain a set of unique names in scratchpad using compact format (e.g., `"unique: A, B, C"`). Write SKIP next to duplicates.
- For "how many" queries: you MUST open EVERY candidate task's detail view to confirm its absolute date before counting it. Relative day labels ("Mon", "Thu") do NOT tell you which week. Do NOT shortcut — verify all candidates even if it takes many turns.

## Information Tasks
- Do NOT click checkboxes when the goal is to read information.
- Do NOT answer "0" or "none" without scrolling the full list.

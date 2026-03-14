---
name: org.tasks
description: App-specific guidance for Tasks.org.
---

# Tasks.org Skill

## CRITICAL — Follow These Every Time
**Completion status protocol** (MUST follow for any completed/incomplete query):
1. Scroll the FULL task list. Write ALL visible task titles to scratchpad as `before_list`.
2. Tap overflow menu (3 dots) → "Show completed" to toggle. Use element_index, NOT coordinates — the button is near date chips that open the date picker by accident.
3. Scroll the full list again. Write ALL visible task titles to scratchpad as `after_list`.
4. Compare: if `after_list` has MORE items → you turned completed ON; extra items are completed tasks. If FEWER → you turned completed OFF; missing items are completed tasks.
5. Do NOT answer until both lists are in scratchpad and the diff is computed.

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

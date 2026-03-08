# TasksCompletedTasksForDate — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 8/30 | **Reason**: GoalAchieved (false completion) | **Duration**: 55s

**Goal**: Identify which tasks have been completed for October 16, 2023 in Tasks.org app and return their titles.

## Root Cause

**Category**: FalseCompletion + PerceptionGap

The app skill WAS injected (turn 2, input item 4) with guidance to not click checkboxes and to read checked/unchecked from a11y state. However, the a11y tree does not expose `checked` property for task checkboxes — they appear as generic clickable `ImageView` elements with empty text, indistinguishable from navigation icons. The agent misidentified a checkbox as a nav drawer icon (turn 4), accidentally completing a task. It then failed to find any tasks specifically dated Oct 16 (dates show as relative day abbreviations), scrolled the list twice, and answered "None" — a false completion since it never successfully identified the completed tasks.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("Tasks")` | Y | G | G | G | Opened Tasks.org successfully |
| 2 | `click(element_index=57)` "More options" | Y | G | G | G | Opened overflow menu to find completed task filter |
| 3 | `click(text="Show completed")` | Y | B | G | G | Toggled "Show completed" ON — good intent but reasoning said "hide completed" |
| 4 | `click(element_index=8)` | Y | B | B | B | Thought it was nav drawer; was a checkbox — accidentally completed "Organize Closet" |
| 5 | `click(element_index=12)` | N | B | B | B | Thought it was a nav/menu area; clicked "Thu" date chip, no meaningful screen change |
| 6 | `scroll(down, element_index=5)` | Y | G | G | G | Scrolled to scan more tasks |
| 7 | `scroll(down)` | Y | G | G | G | Continued scrolling, looking for Oct 16 dates |
| 8 | `complete_task("None")` | — | B | — | — | False completion: answered "None" without identifying completed tasks |

### Key Turns

**Turn 4** — Accidental checkbox click:
- **What was on screen**: Full task list with "Show completed" enabled. Element 8 was a clickable ImageView at bounds [0,275,147,422] with empty text — the checkbox icon for "Organize Closet" (whose RelativeLayout starts at the same y-coordinate, bounds [0,275,1080,427]).
- **What agent decided**: "Open the navigation drawer to look for a completed/archive or calendar view."
- **What action was taken**: `click(element_index=8)` — the empty ImageView on the left side of the first task row.
- **What actually happened**: The checkbox was toggled, completing "Organize Closet." A "Task completed" snackbar with UNDO button appeared.
- **What should have happened**: The agent should have recognized that empty ImageView elements within task rows (bounds overlapping RelativeLayout bounds) are checkboxes, not navigation icons. The nav drawer icon would be near "My Tasks" header area.
- **Perception check**: The a11y tree provides no `checked` property, no `desc`, and no `text` for these ImageView checkboxes. They are indistinguishable from decorative icons. The app skill says to "observe the checked/unchecked property directly from the screen state" but this property simply does not exist in the a11y tree.

**Turn 8** — False completion:
- **What was on screen**: Task list showing tasks with relative date labels (Thu, Wed, Tue, Fri, Sat, Oct 8). No task explicitly labeled "Oct 16" or "Mon" (which would be Oct 16 on a Sunday Oct 15 device date).
- **What agent decided**: No tasks found for Oct 16, answer "None."
- **What actually happened**: Agent answered "None" and scorer gave 0.0 — there were completed tasks for that date.
- **What should have happened**: The agent needed to resolve relative dates. Device date is Oct 15 (Sunday), so "Mon" = Oct 16. Tasks with "Mon" dates that have completed status should have been reported. However, "Show completed" was toggled ON but the a11y tree has no way to distinguish completed vs incomplete tasks (no `checked` property, no strikethrough indicator in text).

## What Worked

- App skill was successfully injected when org.tasks became the foreground app (turn 2).
- Agent correctly opened the overflow menu and toggled "Show completed" to reveal completed tasks.
- Agent's stated intent to "avoid checkbox toggles" shows the skill guidance was read.

## What Didn't Work

- **Checkbox perception gap**: Task checkboxes render as empty-text clickable ImageView elements with no `checked` state, no content description, and no distinguishing feature from other icons. The app skill's advice to "observe the checked/unchecked property" is impossible to follow because the property is not exposed.
- **Grounding error on element 8**: Agent confused a task-row checkbox with a navigation drawer icon due to identical element signatures (both are clickable ImageView with empty text).
- **No date resolution**: Agent never resolved relative date labels to absolute dates. "Mon" = Oct 16 on a Sunday Oct 15 device, but agent kept looking for literal "Oct 16" text.
- **No completed-state detection strategy**: After toggling "Show completed," the agent had no way to distinguish completed from incomplete tasks in the a11y tree, and did not attempt an alternative approach (e.g., looking at visual strikethrough in screenshots, or cross-referencing the list before/after toggling "Show completed").

## Recommendations

- **Fix a11y tree checkbox exposure**: Investigate why Tasks.org checkboxes lack `isChecked` in the a11y tree. This may be a sanitization issue in the perception pipeline (the raw tree may have it but it gets dropped) or a genuine Tasks.org accessibility bug. Check `raw_a11y_tree` vs `sanitized_a11y_tree` for `checked` field.
- **Update app skill with diff-based strategy**: Since `checked` property is missing, the skill should advise: "Toggle 'Show completed' OFF, note the visible tasks, then toggle ON and note new tasks that appeared. The difference is the completed tasks."
- **Add date resolution to app skill**: Current skill mentions relative dates but does not provide explicit resolution guidance like "use the device date from system prompt to compute absolute dates from relative labels."
- **Improve checkbox identification**: Empty-text clickable ImageView elements within a RecyclerView task row (left-aligned, small bounds) are almost certainly checkboxes. The skill or perception layer should flag these to prevent accidental clicks.

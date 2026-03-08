# TasksIncompleteTasksOnDate -- Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 11/30 | **Reason**: GoalAchieved (FalseCompletion) | **Duration**: 153s

**Goal**: What incomplete tasks do I have still have to do by October 19 2023 in Tasks app? Answer with titles only, comma-separated.

## Root Cause

**Category**: FalseCompletion

The org.tasks app skill was NOT injected into the prompt despite existing in source code -- the user context section was empty ("(built by PromptBuilder)"). Without the skill, the agent had no guidance that default My Tasks view shows only incomplete tasks, or how to distinguish completed from incomplete. The agent correctly opened the app, scrolled methodically, and recorded tasks per viewport (major improvement over R7). However, the final 14-item answer included tasks due AFTER Oct 19 (e.g., "Back Up Computer Files" due Sat=Oct 21, "Submit Expense Report" due Fri=Oct 20 in first viewport) and may have confused duplicate task entries appearing at different scroll positions with different relative date labels. The agent also did not click any checkboxes -- the R7 destructive-exploration bug is fixed -- but the score remains 0.0 due to incorrect date filtering producing false positives.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("Tasks")` | Y -- Tasks app opened | G | G | G | -- |
| 2 | `wait(1500ms)` | Y -- 5 -> 59 elements, list loaded | G | G | G | Good: waited for slow load |
| 3 | `write_todos` | N | G | G | G | Planned 3-step approach: scan, scroll, answer |
| 4 | `scratchpad(write)` | N | B | G | G | Recorded 7 tasks from first viewport. Date mapping errors (see Key Turns) |
| 5 | `scroll(down)` | Y -- new tasks visible | G | G | G | -- |
| 6 | `scratchpad(write)` | N | B | G | G | Added 5 more tasks. Included Water Plants (Yest=OK) but also confusing duplicates |
| 7 | `scroll(down)` | Y -- more tasks visible | G | G | G | -- |
| 8 | `scratchpad(write)` | N | B | G | G | Added Reply to Emails, Submit Expense Report, Organize Closet |
| 9 | `scroll(down)` | N -- same content, bottom of list | G | G | G | Verified end of list reached |
| 10 | `write_todos(all completed)` | N | G | G | G | -- |
| 11 | `complete_task(14 items)` | N/A | B | N/A | N/A | Answer includes false positives |

### Key Turns

**Turn 4** -- First scratchpad write with date mapping errors:
- **What was on screen**: Tasks app "My Tasks" list. Tasks visible: Attend training session (Thu), Call client for follow-up (Thu), Water Plants (Fri), Back Up Computer Files (Sat/Tmrw), Research market trends (Thu), Change Air Filter (Today), Renew Driver's License (Tue), Clean Out Fridge (Tue).
- **What agent decided**: "Thu=Oct 19, Tue=Oct 17, Today=Oct 15, Tmrw=Oct 16, Fri=Oct 20, Sat=Oct 21". Included "Back Up Computer Files" claiming it was due "Tmrw". Also included "Change Air Filter" (Today=Oct 15, correct to include).
- **What actually happened**: The a11y tree shows Back Up Computer Files with TWO date indicators: primary "Sat" (index 27) = Oct 21 and a checkable "Tmrw" chip (index 29). The agent read the RelativeLayout compound text "Back Up Computer Files | ... | Tmrw" and used "Tmrw" as the due date, ignoring that the actual due date "Sat" was the separate date label. This is a perception/reasoning error caused by confusing a subtask reminder chip with the primary due date.
- **What should have happened**: Agent should have used the "Sat" label (top-right of task row, consistent with other tasks' date positions) as the due date = Oct 21 > Oct 19, therefore excluded.

**Turn 6** -- Second scratchpad write adds more false positives:
- **What was on screen**: Scrolled viewport showing additional tasks. Submit Expense Report shows "Fri" = Oct 20 > Oct 19. Water Plants appears again with "Yest" = Oct 14 (correct to include). Grocery Shopping "Tue" (ambiguous -- could be Oct 17 in this week or a different context).
- **What agent decided**: Included Submit Expense Report and Water Plants. Agent reasoning included Submit Expense Report with Fri=Oct 20 > Oct 19, which should have been excluded.
- **What actually happened**: Agent included at least 2 tasks with dates past Oct 19.

**Turn 8** -- Third scratchpad write:
- **What was on screen**: Further scrolled view. Organize Closet shows "Oct 8" (overdue, correct to include as it's before Oct 19).
- **What agent decided**: Added Reply to Emails (Thu=Oct 19, correct), Submit Expense Report (Thu in this view -- different from Fri in earlier view), Organize Closet (Oct 8, correct).
- **Note**: The same tasks appear with different date labels at different scroll positions, creating confusion. Submit Expense Report showed "Fri" in viewport 1 but "Thu" in viewport 3.

**Turn 11** -- Final answer:
- **Agent answer**: "Attend training session, Call client for follow-up, Back Up Computer Files, Research market trends, Renew Driver's License, Clean Out Fridge, Grocery Shopping, Learn to Play Guitar, Water Plants, Research Vacation Destinations, Read 'The Martian', Reply to Emails, Submit Expense Report, Organize Closet"
- **Issues**: 14 items submitted. Agent dropped "Change Air Filter" from scratchpad 1 (correctly excluded or erroneously dropped -- Today=Oct 15 is before Oct 19, so it should have been included). "Back Up Computer Files" (Sat=Oct 21) is a false positive. The exact ground truth is unknown from trace alone, but the 0.0 score confirms significant mismatch.

## What Worked

- No checkbox clicking -- the R7 destructive-exploration bug is eliminated even without the app skill being injected. The model (gpt-5.4) naturally avoided clicking checkboxes this time.
- Systematic scroll-and-record approach: 3 viewports scanned, findings accumulated in scratchpad progressively.
- Correct date resolution for most tasks (Thu=Oct 19, Tue=Oct 17, Today=Oct 15).
- Good use of write_todos for planning and tracking progress.
- Wait for app load (turn 2) prevented the empty-screen false start seen in some other runs.

## What Didn't Work

- **App skill not injected**: The org.tasks SKILL.md file exists at `app/src/main/assets/app_skills/org.tasks/SKILL.md` but was NOT present in the LLM prompt. The user context section was empty. This is the same issue flagged in R7 changelog: "Tasks app org.tasks skill was not injected -- need to verify package name on emulator." The skill was NOT deployed in the APK used for this eval run (build predates skill addition, or PromptBuilder failed to load it).
- **Date label confusion on compound RelativeLayout text**: Agent used "Tmrw" from the concatenated RelativeLayout text instead of the actual "Sat" due date label for "Back Up Computer Files", leading to a false inclusion.
- **Duplicate task entries with different dates**: The same task names appear at multiple scroll positions with different relative date labels (e.g., Submit Expense Report shows "Fri" in one viewport and "Thu" in another). This may indicate the list has recurrence instances or the agent saw different task instances. The agent did not reconcile these duplicates.
- **Dropped "Change Air Filter"**: This task (Today=Oct 15, before Oct 19) appeared in scratchpad 1 but was dropped from the final answer without explanation. If the eval expected it, this is a false negative.

## Recommendations

1. **Fix app skill injection**: The org.tasks SKILL.md must be included in the deployed APK. Verify the build used for eval includes the `app_skills/org.tasks/SKILL.md` asset. The PromptBuilder should inject it when `package == "org.tasks"`. Debug why user context was empty despite the app being active.

2. **Date disambiguation for compound a11y text**: When a RelativeLayout contains multiple date-like tokens in its concatenated text (e.g., "Back Up Computer Files | ... | Tmrw"), the agent should prefer the standalone date label element at the top-right of the task row (consistent position for all tasks) rather than a chip/tag element embedded in the compound text.

3. **Duplicate entry reconciliation**: When the same task name appears with different date labels across scroll viewports, the agent should note the discrepancy and use the most specific/recent date, or recognize that different instances may represent different data (subtasks, recurrences).

4. **Update meta/per_task**: Record R8 result showing the app skill injection is still broken, but agent behavior improved (no checkbox clicking, systematic scrolling). Score remains 0.0 due to date filtering errors.

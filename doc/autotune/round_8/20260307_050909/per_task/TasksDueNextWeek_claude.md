# TasksDueNextWeek -- Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 7/30 | **Reason**: GoalAchieved | **Duration**: 42s

**Goal**: Count tasks due next week (Mon Oct 16 - Sun Oct 22) in the Tasks.org app and return a single integer.

## Root Cause

**Category**: ReasoningError

The agent answered **12** but the correct answer was **6**. Two compounding errors: (1) the agent counted pre-existing tasks on the device alongside the eval-injected tasks, treating every visible task with a "next week" date label as relevant; (2) the agent double-counted tasks visible in overlapping viewports after scrolling. Additionally, the Tasks.org app skill was **not injected** in this run despite being a direct R8 fix target, so the agent had no guidance on interpreting Tasks.org's relative date labels or navigating its UI.

## App Skill Injection Check

**Injected**: No. The system prompt was 47 lines (base prompt only). No Tasks.org-specific content was present in any turn's system prompt. This means the R8 fix for Tasks app skill did not take effect for this run.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("Tasks")` | Y | G | G | G | Correctly opens Tasks.org |
| 2 | `write_todos` (plan) | N | G | G | G | Good: establishes Oct 16-22 range |
| 3 | `scratchpad` (count=5) | N | B | -- | G | Counts from first viewport |
| 4 | `scroll down` | Y | G | G | G | Scrolls to see more tasks |
| 5 | `scratchpad` (count=12) | N | B | -- | G | Adds 7 new matches, double-counting |
| 6 | `scroll down` | Y | G | G | G | Third viewport reveals more tasks |
| 7 | `complete_task("12")` | N | B | -- | G | Wrong answer submitted |

### Key Turns

**Turn 3** -- Incorrect initial count of 5:
- **What was on screen**: Tasks.org "My Tasks" list sorted by due date. Visible tasks with their date labels: Finish Project Proposal x2 (Oct 8), Finish Project Proposal (Fri), Call Grandma (Wed), Call Grandma (Thu), Water Plants (Thu), Call Grandma (Today), Reply to Emails (Fri), Water Plants (Yest), Create Budget for Next Month (Fri).
- **What agent decided**: "Wed, Thu, Thu, Fri, Fri = 5 tasks" matching next week. Excluded Today, Yest, Oct 8.
- **What actually happened**: The agent correctly excluded Today/Yest/Oct 8 but had no way to distinguish eval-injected tasks from pre-existing device tasks. The Tasks.org app shows many duplicate task names (e.g., "Finish Project Proposal" appears 3 times, "Call Grandma" appears 4 times) because Tasks.org has recurring tasks and the eval also injects tasks with similar names.
- **Core problem**: Without the app skill, the agent has no guidance that the "My Tasks" view includes both eval-injected and pre-existing tasks. There is no filter for "due next week" in the default view.

**Turn 5** -- Double-counting and over-counting to 12:
- **What was on screen**: After scrolling, viewport shows: Water Plants (Yest), Create Budget (Fri), Read The Martian (Today), Follow up on support tickets (Tmrw), Plan family reunion (Tue), Book flights for conference (Wed), Research market trends (Fri), Send report to manager (Sat), Organize files and folders (Oct 22), Call Grandma (Wed).
- **What agent decided**: Added 7 new matches: "Create Budget for Next Month (Fri), Plan family reunion (Tue), Book flights for conference (Wed), Research market trends (Fri), Send report to manager (Sat), Organize files and folders (Oct 22), Call Grandma (Wed)". Updated count to 12.
- **What went wrong**: The agent re-counted "Create Budget for Next Month (Fri)" which was already visible and counted in viewport 1. Also included "Follow up on support tickets (Tmrw)" by excluding it but then counted "Sat" as next week -- Sat Oct 21 is valid but the agent failed to verify whether these are the same named tasks it already counted from viewport 1. The fundamental problem is that the list has many tasks with the same name at different due dates, making scroll-and-count extremely error-prone.

**Turn 7** -- False completion with answer "12":
- **What should have happened**: The correct answer was **6** (the number of eval-injected tasks). The agent should have recognized that with so many tasks visible, it needed a more systematic approach -- either using the Tasks.org filter/sort features or clicking into each task to verify its exact due date rather than relying on ambiguous relative day labels.

## What Worked

- Correct date range calculation: agent correctly identified Oct 16-22 as "next week" from Sunday Oct 15
- Systematic approach: created a plan, used scratchpad for memory, scrolled to see full list
- Excluded "Today", "Yest", and "Oct 8" correctly as outside next week

## What Didn't Work

- **No app skill injected**: The R8 fix target was to provide Tasks.org navigation guidance, but the skill was not present in the system prompt. This means the agent had no knowledge of Tasks.org's filter capabilities.
- **Double-counting across viewports**: Scrolling caused viewport overlap, and the agent re-counted tasks visible in both viewports (e.g., "Create Budget for Next Month (Fri)").
- **No deduplication**: The agent treated every row as a unique task without verifying uniqueness. Tasks.org shows recurring tasks as separate rows, and the eval injects tasks alongside pre-existing ones.
- **Ambiguous relative dates**: "Fri", "Wed", "Thu" are relative to "today" (Oct 15), so they map to the upcoming occurrence. But the agent had no way to confirm which specific date each label referred to without clicking into individual tasks.
- **No use of Tasks.org filtering**: The app has sort/filter capabilities visible in the bottom bar ("Sort" button, index 56). A filter by date range would have been more reliable than manual counting.

## Recommendations

- **Fix app skill injection**: Verify the Tasks.org app skill is actually being injected into the system prompt. The 47-line system prompt confirms it was missing. This is the primary R8 fix that did not activate.
- **App skill should teach date filtering**: The Tasks.org skill should instruct the agent to use the app's built-in sort/filter (by due date) or to click into individual tasks to see exact dates, rather than relying on ambiguous relative day-of-week labels.
- **App skill should warn about duplicates**: The skill should note that the default "My Tasks" view may include recurring tasks and tasks from multiple lists, making manual counting unreliable.
- **Viewport overlap tracking**: The agent's scratchpad-based counting needs a deduplication strategy -- store task identifiers (name + date) to avoid re-counting items visible in overlapping viewports.

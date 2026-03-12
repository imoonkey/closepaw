# TasksCompletedTasksForDate -- Cog-Tune Analysis (R91)

- **Score**: 0.0 | **Turns**: 6/30 | **Completion**: GoalAchieved (wrong answer)
- **Answer given**: "Water Plants" | **Strategy**: toggle "Show completed" off, diff the list, pick tasks for Mon (Oct 16)
- **Root cause**: **Pre-existing (Execution)**. The "Show completed" toggle click registered (Success reported), but the task list did not change -- before: 65 elements with `Participate in brainstorming session` visible at bottom; after: 61 elements, same tasks, `Participate` gone (scrolled off). No completed tasks appeared or disappeared, so the diff approach produced a wrong answer. The agent picked "Water Plants" (due Tue, not Mon Oct 16) based on flawed reasoning from an unchanged list.
- **Prompt cuts contribution**: **No**. The R91 condensed prompt retained all relevant guidance: the Completion section still says "verify the view shows the target date" and "do not guess metadata." The agent's strategy (open menu, toggle, diff) was sound and matches R16's successful approach. The failure is that the toggle click did not actually change the UI state -- a known pre-existing execution issue documented since R13.
- **History**: Passed in R16 (gpt-5.4, 11 turns). Failed in R13/R14 (same toggle-click issue). This seed (3870170669) differs from R16's, so different task data may produce different completed-task visibility behavior.
- **Summary**: Pre-existing toggle-click execution failure; prompt cuts did not contribute.

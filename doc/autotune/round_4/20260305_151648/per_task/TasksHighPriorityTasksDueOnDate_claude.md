# TasksHighPriorityTasksDueOnDate -- Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 9/30 | **Reason**: GoalAchieved | **Duration**: 81s

**Goal**: Find which tasks with high priority are due October 18, 2023 in the Tasks app; answer with titles in a comma-separated list.

**REGRESSION**: Was 1.0 in R3 (21 turns). Now 0.0 in R4 (9 turns). Expected answer: "Marketing Campaign Launch".

## Root Cause

**Category**: ReasoningError

The agent opened the Tasks app, checked a few tasks with "Wed" labels (Oct 18 = Wednesday), viewed their detail screens, but submitted an empty answer claiming no high-priority tasks were due Oct 18. It checked "Learn to Play Guitar" (Turn 2), "Marketing Campaign Launch" (Turn 6), and "Back Up Computer Files" (Turn 8) -- all showing "Wednesday" in their detail views. However, the agent could not read the priority radio buttons from the a11y tree (indices 25-28 have no text/description), so it failed to determine priority for any task.

The regression is caused by the agent giving up too quickly (9 turns vs R3's 21). In R3, the agent methodically checked multiple Wed tasks and managed to infer priority from the radio button positions. In R4, the agent checked only 3 tasks and concluded with empty string after just 9 turns. The R4 changes (date-label tip, shell restriction) are not the direct cause -- the regression is due to **qwen3.5 natural variance**: the model happened to give up earlier this run. The date-label tip actually helped (agent correctly identified Wed = Oct 18), but the Priority radio button perception gap persists.

**Key evidence against prompt-change causation**: The Marketing Campaign Launch detail screen (Turn 6) shows `"Priority"` label at index [24] and [29], with indices [25]-[28] being the radio buttons with NO text. This is identical to what R3 saw. The R3 agent persisted through 21 turns to figure out the priority; the R4 agent stopped at 9.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("Tasks")` | Y | G | G | G | -- |
| 2 | `click(16)` "Learn to Play Guitar" | Y | G | G | G | Checking a Wed task |
| 3 | `system_button(back)` | Y | G | G | G | Back to list |
| 4 | `click(7)` "Discard" | Y | G | G | G | Discard changes dialog |
| 5 | `scroll(down)` | Y | G | G | G | See more tasks |
| 6 | `click(15)` "Marketing Campaign Launch" | Y | G | G | G | Checking another Wed task |
| 7 | `system_button(back)` | Y | G | G | G | Back to list |
| 8 | `click(54)` "Back Up Computer Files" | Y | G | B | G | Task due Sat Oct 7, not Wed Oct 18 |
| 9 | `complete_task("")` | -- | B | -- | G | Empty answer -- false negative |

### Key Turns

**Turn 6** -- Agent opened Marketing Campaign Launch and saw the detail screen with "Wednesday" as the due date and a "Priority" section. The priority radio buttons (indices 25-28) had no text labels in the a11y tree. The agent could not determine if this was high priority. In R3, the agent spent more turns experimenting with the radio buttons to infer the selected state.

**Turn 8** -- Agent clicked "Back Up Computer Files" which shows due date "Saturday, October 7" -- not Oct 18. This was a grounding error (wrong task selected).

**Turn 9** -- Agent submitted empty answer after checking only 3 tasks (one of which was the wrong date). It had 21 turns remaining but gave up prematurely.

## What Worked

- Date-label tip helped: agent correctly identified Wed = Oct 18 and looked for Wed tasks
- Efficient navigation to task detail views
- Correct identification of Marketing Campaign Launch as a candidate

## What Didn't Work

- Priority radio buttons have no a11y text -- agent cannot determine priority level (persistent PerceptionGap from R3)
- Agent gave up after 9 turns with 21 remaining -- premature completion
- Did not use scratchpad to track findings, preventing systematic verification
- Checked "Back Up Computer Files" (due Oct 7, Saturday) despite looking for Oct 18 (Wednesday) tasks

## Recommendations

- **Priority perception**: The Tasks app priority radio buttons lack a11y labels. The agent needs a tip: "Priority in Tasks app is shown as 4 radio buttons. The selected button's `checked` or `selected` state may be visible in the raw a11y tree even when text is absent. Check the raw tree or tap each radio button to observe state changes."
- **Premature completion guard**: Add a heuristic -- for QA tasks, if the agent's answer is empty/null and turns remain, encourage re-verification before submitting.
- **Regression cause**: Natural model variance (qwen3.5 nondeterminism), not the R4 prompt changes. The date-label tip was actually helpful. No rollback needed.

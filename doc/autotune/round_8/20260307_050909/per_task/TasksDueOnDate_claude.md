# TasksDueOnDate — Cog-Tune Analysis

**Score**: 0.0 | **Turns**: 4/4 | **Reason**: GoalAchieved (FalseCompletion) | **Duration**: 74s

**Goal**: List task titles due October 24 2023 in the Tasks app, comma-separated.

## Root Cause

**Category**: FalseCompletion

The agent correctly identified the three tasks with "Oct 24" due dates (Follow up on support tickets, Draft marketing email, Research market trends) but then hallucinated two additional tasks — Grocery Shopping and Organize Closet — into the final answer. Neither of those tasks shows "Oct 24" in the a11y tree; they show "Tue" and "Oct 22"/"Tue" respectively. The agent likely confused relative day-of-week labels with Oct 24 after scrolling, or simply fabricated entries without verifying each task's date.

## Turn-by-Turn Analysis

| Turn | Action | Screen Changed? | Reasoning | Grounding | Execution | Note |
|------|--------|-----------------|-----------|-----------|-----------|------|
| 1 | `open_app("Tasks")` | Y | G | G | G | App launched, only status bar visible |
| 2 | `wait(1500ms)` | Y | G | G | G | Tasks loaded, 60 elements. Three "Oct 24" tasks visible |
| 3 | `scroll(down, element_index=8)` | Y | G | G | G | Good — scroll to check for more Oct 24 tasks |
| 4 | `complete_task(answer)` | — | B | B | — | Answer includes 2 hallucinated tasks |

### Key Turns

**Turn 2** — Screen loaded with full task list:
- **What was on screen**: "My Tasks" list with tasks and their due dates. Three tasks show "Oct 24": Follow up on support tickets (index 29), Draft marketing email (index 34), Research market trends (index 39). Other tasks show Tue, Fri, Tmrw, Oct 22, Wed.
- **What agent decided**: Scroll to see if there are more tasks below.
- **Correct decision**: Yes, scrolling to verify completeness was correct.

**Turn 4** — FalseCompletion:
- **What was on screen (after scroll)**: Additional tasks visible — Grocery Shopping (due "Tue"), Organize Closet (due "Oct 22"/"Tue"), Research Vacation Destinations (due "Sat"), Meal Prep for the Week (due "Fri"), Change Air Filter (due "Sat"). None show "Oct 24".
- **What agent answered**: "Follow up on support tickets, Draft marketing email, Research market trends, Grocery Shopping, Organize Closet"
- **What should have been answered**: "Follow up on support tickets, Draft marketing email, Research market trends"
- **Error**: Agent included Grocery Shopping (due "Tue", not Oct 24) and Organize Closet (due "Oct 22", not Oct 24). The agent either (a) assumed "Tue" relative dates mapped to Oct 24 without verification, or (b) hallucinated after losing track of which dates map to which tasks across the scroll boundary.

## What Worked

- App launch and wait strategy was efficient (2 turns to get full screen).
- Scrolling to ensure completeness before answering was the right instinct.
- Three correct tasks were identified.

## What Didn't Work

- Agent did not use scratchpad to record confirmed Oct 24 tasks as it found them, making it prone to post-hoc hallucination.
- Agent did not strictly verify each task's date label before including it in the answer. Relative date labels like "Tue" were possibly misinterpreted as Oct 24 (Tuesday is Oct 24, 2023), but this is speculative inference the agent should not make without explicit evidence.
- No Tasks app skill was injected in the system prompt, so the agent had no domain guidance for this app.

## Recommendations

- **Scratchpad discipline for information tasks**: For information-gathering goals, the system prompt should enforce writing confirmed facts to scratchpad before completing. This prevents hallucination at the answer stage.
- **Strict date matching**: The agent should only include tasks whose displayed date exactly matches the query. Relative labels ("Tue", "Tmrw", "Wed") require clicking into the task or cross-referencing the device date to resolve — the agent should not silently assume they match.
- **Tasks app skill injection**: The Tasks app skill was NOT injected for this run. Skill guidance could instruct the agent to use date-based filtering or sort, and to write confirmed matches to scratchpad before answering.

# TasksHighPriorityTasksDueOnDate -- R20 Analysis

**Score**: 0.0 | **Turns**: 25/50 | **Stop**: GoalAchieved (FalseCompletion) | **Duration**: 121s

**Goal**: Which tasks with high priority are due October 18 2023 in the Tasks app? Answer with the title only.

**Expected**: "Marketing Campaign Launch" | **Agent answered**: "Renew Driver's License"

## Behavior Summary

The agent opened Tasks, set up a plan (write_todos turn 2), used scratchpad to track candidates, scrolled the task list 3 times (turns 4, 6, 8), then individually opened 4 candidates to verify due date and priority. Answered "Renew Driver's License" because it was the only candidate with the 1st/leftmost priority radio button checked (High). Marketing Campaign Launch had the 4th/rightmost button checked (None).

## Exhaustive Scroll?

**Yes, this time.** The agent scrolled 3 times (turns 4, 6, 8). By turn 8, the screen shows "Meal Prep for the Week" followed by "Create new task" button -- the bottom of the list. This is an improvement over R19 (only 2 scrolls). The agent saw all tasks in the list.

However, the agent did NOT find any "dynamically-created eval task below the fold" mentioned in R19's analysis. The turn 8 screen shows the full list ends at "Meal Prep for the Week". Either: (a) no dynamic task was injected in this seed/run, or (b) the dynamic task was already visible during scanning and the agent didn't recognize it.

## Candidates Found

Turn 3 scratchpad initial candidates (from first view): "Learn to Play Guitar; Renew Driver's License; Marketing Campaign Launch"
Turn 5 scratchpad (after 1st scroll): added "Back Up Computer Files"
Turn 7 scratchpad (after 2nd scroll): added "Schedule Car Maintenance"

**Problem**: "Schedule Car Maintenance" was HALLUCINATED. It never appears in any screen observation. The agent fabricated this candidate -- it does not exist in the task list. This wasted turns 17 (click by text, which would have clicked the wrong element or failed) and 18 (scroll up to find other tasks).

## All Candidates Checked?

The agent opened and verified 4 of 5 candidates (5th was hallucinated):

| Candidate | Due date (detail view) | Priority (radio position) | Correctly read? |
|---|---|---|---|
| Back Up Computer Files | Wednesday (Oct 18) | 4th (None) | Yes |
| Learn to Play Guitar | Thursday (Oct 19) -- NOT Oct 18 | 3rd (Low) | Yes (also correctly excluded as not Oct 18) |
| Renew Driver's License | "Wednesday" in detail | 1st (High) | Priority correct; due date likely WRONG -- see below |
| Marketing Campaign Launch | "Wednesday" in detail | 4th (None) | Yes |
| Schedule Car Maintenance | Never opened (hallucinated) | N/A | N/A |

## Scratchpad Accumulation

**Partially followed.** The candidate list was correctly accumulated across turns 3, 5, 7 -- each append preserved prior candidates. During the priority-checking phase (turns 10, 14, 20, 24), the agent wrote individual `priority_<TaskName>` keys rather than appending to a single accumulator. This is acceptable but fragmented -- the agent never maintained a single unified view of "all candidates with their verified statuses."

The agent DID cross-check before answering: turn 24 scratchpad thought says "enough evidence exists to answer because only Renew Driver's License is verified high among checked Oct 18 tasks."

## Root Cause

**Category**: ReasoningError (wrong answer despite good mechanics)

The agent answered "Renew Driver's License" = High priority + "Wednesday" due date. But the expected answer is "Marketing Campaign Launch". This creates a contradiction: the agent correctly read Marketing Campaign Launch as None priority (4th radio button), yet the eval expects it as the answer.

Key observations about this contradiction:
1. **Renew Driver's License shows "Sat" in the list view** (turn 8 screen, index 22 text="Sat") but its detail view shows "Wednesday" as the due date field. The list day-label "Sat" suggests its actual due date is Saturday (Oct 21), NOT Wednesday (Oct 18). The "Wednesday" field in the detail view may be something else (e.g., start date, or the detail view shows a different date context).
2. **The eval is seeded** (seed=2278904713). The dynamic task setup may change priority assignments between seeds. In R3 (which scored 1.0), Marketing Campaign Launch was apparently High priority. In R20, the radio button state shows it as None.
3. **The agent may have been looking at a stale/different instance of Renew Driver's License.** The initial screen shows Renew Driver's License appearing THREE times with different day labels (Fri, Thu, Wed) -- likely under different list groupings. The agent may have clicked the wrong instance or the detail view shows a different task's properties.

The most likely explanation: the eval dynamically sets Marketing Campaign Launch to High priority, but the radio button `checked` state in the a11y tree does not match what was set. Alternatively, the agent's priority reading (1st=High, 4th=None) is inverted in this version of Tasks.org -- 1st might be None and 4th might be High.

## Comparison to R19

| Aspect | R19 | R20 |
|---|---|---|
| Scrolls | 2 (incomplete) | 3 (reached list bottom) |
| Candidates found | 4 | 5 (1 hallucinated) |
| Candidates checked | 3/4 | 4/4 real candidates |
| Priority reading | Correct (1st=High) | Same logic applied |
| Answer | "Renew Driver's License" | "Renew Driver's License" |
| Score | 0.0 | 0.0 |

Mechanics improved (exhaustive scroll, all real candidates checked). Same wrong answer.

## Recommendations

1. **Priority radio button scale verification**: The agent needs to determine whether 1st radio button = High or None in the current Tasks.org version. Add to skill: "To verify the priority scale direction, open a task with KNOWN priority (visible in the list as a colored indicator) and check which radio button is selected. In Tasks.org the priority colors are: red=High, orange=Medium, blue=Low, no-color=None."

2. **List-view day label vs detail-view due date cross-check**: Add to skill: "When verifying a candidate, confirm the detail-view due date matches the list-view day abbreviation. If the list shows 'Sat' but the detail shows 'Wednesday', the task is NOT due on Wednesday -- investigate further."

3. **Hallucination guard for candidates**: The agent added "Schedule Car Maintenance" to the candidate list despite it never appearing on screen. Add to skill: "Only add a task to the candidate list if you can point to the exact element index where you see it on the current screen. Never infer task names from memory."

4. **Priority scale may be inverted**: If Tasks.org uses 4th/rightmost = High (opposite of what the agent assumes), then Marketing Campaign Launch (4th checked) IS High priority, and Renew Driver's License (1st checked) is None. This would make the expected answer correct. This hypothesis should be tested by examining the R3 trace where the task scored 1.0.

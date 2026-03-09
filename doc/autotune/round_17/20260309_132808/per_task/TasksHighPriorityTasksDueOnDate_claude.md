# TasksHighPriorityTasksDueOnDate — R17 Analysis

**Run**: 20260309_132808 | **Model**: gpt-5.4 | **Score**: 0.0 | **Turns**: 13

## Summary
Agent scrolled once, lost pre-scroll candidates (overwrote list), checked 2 tasks. Both had rightmost radio button = None priority, but agent read it as High (inverted scale). Answered "Marketing Campaign Launch, Back Up Computer Files" — both wrong.

## Root Cause: ReasoningError (priority scale inverted) + NavigationError
1. Priority radio buttons: 4 buttons, left-to-right = High, Med, Low, None. Agent assumed rightmost = High. Every priority read was wrong.
2. Single scroll, lost candidates — didn't reach end of list.

## Fix
1. Document priority scale in Tasks skill: "Left-to-right: High, Medium, Low, None"
2. Scroll until stuck, append candidates (don't overwrite)

# Note 6: Replanning and Error Recovery

> Comparative analysis of how agents detect failures, trigger replanning, and recover from errors.

---

## Overview

| Framework | Failure Detection | Replanning Trigger | Recovery Strategy |
|-----------|------------------|-------------------|-------------------|
| **DroidRun** | `error_flag_plan` (2 failures) | Manager sees `<potentially_stuck>` | Strategy revision |
| **AutoDev** | MAX_EXECUTOR_STEPS (10) | Executor returns failure report | Planner tries different approach |
| **Mobile Agent v3** | ActionReflector (A/B/C) | `error_flag_plan` (2 failures) | Manager revises plan |
| **MiniTap** | Subgoal FAILURE status | convergence_gate → replan | Planner preserves progress |

---

## Failure Detection Mechanisms

### DroidRun
```python
if consecutive_failures >= err_to_manager_thresh:  # default: 2
    error_flag_plan = True
    
# Manager sees:
<potentially_stuck>
Recent failures:
1. click(5) - element not found
2. swipe("up") - no change
</potentially_stuck>
```

### AutoDev
```python
MAX_EXECUTOR_STEPS = 10
# If Executor fails to complete after 10 steps → returns narrative failure report
# Planner reads report and decides next action
```

### Mobile Agent v3
```python
# ActionReflector outcomes:
# A: Success/Partial success
# B: Wrong page (need to return)
# C: No change (action failed)

if consecutive_non_A >= err_to_manager_thresh:
    info_pool.error_flag_plan = True
```

### MiniTap
```python
# convergence_gate checks subgoal status
if any(sg.status == FAILURE for sg in subgoal_plan):
    return "replan"  # Route to Planner
```

---

## Replanning Strategies

### DroidRun: Manager Revision
- Sees error history in prompt
- Can completely rewrite plan
- Memory persists across replan

### AutoDev: Executor Report
- Executor provides narrative summary of failure
- Planner reads and adjusts strategy
- Scratchpad data preserved

### Mobile Agent v3: Error-Aware Re-planning
- Manager prompted with "Potentially Stuck!" section
- Historical Operations preserved
- Plan revised from current state

### MiniTap: Preserve-and-Adapt
Prompt rules:
1. Keep completed subgoals (don't redo)
2. Use agents_thoughts as truth source
3. Adjust strategy based on observations
4. Continue from current state

---

## Key Patterns

### 1. Consecutive Failure Threshold
Both DroidRun and Mobile Agent v3 use `err_to_manager_thresh = 2`:
- Single failure → retry with Executor
- 2+ consecutive → escalate to Planner

### 2. Narrative Failure Reports (AutoDev)
Executor returns human-readable summary:
```
"Could not find the login button. The screen shows a popup dialog blocking the view."
```
Better than raw tool call logs.

### 3. Before/After Verification (Mobile Agent v3)
ActionReflector with two screenshots catches:
- Wrong page navigation (B)
- No visible change (C)
- Partial success (A)

### 4. Progress Preservation (All)
- Never restart from scratch
- Completed work is never redone
- Replan from current state

---

## Design Recommendations

1. **Threshold before escalation**: Allow 1-2 retries before replanning
2. **Narrative error context**: Show failure reasons, not just logs
3. **Preserve completed work**: Track what's done, continue from there
4. **Before/after comparison**: Verify actions actually had effect
5. **Strategy change signal**: Indicate when to try different approach

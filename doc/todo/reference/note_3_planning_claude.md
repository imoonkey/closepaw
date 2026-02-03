# Note 3: TODO Lists, Subgoals, and Planning

> Comparative analysis of how agents decompose goals, track progress, and manage subgoal lifecycles.

---

## Overview

Planning mechanisms serve to:
1. **Decompose complex goals** into manageable subgoals
2. **Track progress** through task execution
3. **Enable replanning** when strategies fail

| Framework | Planning Entity | Format | Progress Tracking | Replanning Trigger |
|-----------|----------------|--------|-------------------|-------------------|
| **DroidRun** | Manager | Numbered list in `<plan>` | `plan` string updates | `error_flag_plan = True` |
| **AutoDev** | Planner | `update_todos()` tool | TODO List with status | Executor failure after N steps |
| **Mobile Agent v3** | Manager | Numbered list string | `completed_plan` string | `error_flag_plan = True` |
| **MiniTap** | Planner + Orchestrator | Structured `Subgoal` list | Status enum per subgoal | SubgoalStatus.FAILURE |

---

## Planning Mechanisms by Framework

### 1. DroidRun: Plan String with Manager

**Format**:
```xml
<plan>
1. Open the Settings app
2. Navigate to Network settings
<script>Make HTTP request to verify connection</script>
3. Check connection status and report if successful
</plan>
```

**Progress Tracking**:
- Manager updates `plan` string each step
- Completed items removed or marked
- Special tags: `<script>` for off-device operations

**Current Subgoal Extraction**:
```python
# First non-completed item in plan becomes current_subgoal
current_subgoal = "Navigate to Network settings"
```

**Replanning**:
- Triggered when `error_flag_plan = True` (after 2 consecutive failures)
- Manager sees `<potentially_stuck>` section with recent errors
- Manager rewrites entire plan based on new observations

---

### 2. AutoDev: Structured TODO List

**Format**:
```python
update_todos([
    {"id": 1, "text": "Open Contacts app", "status": "completed"},
    {"id": 2, "text": "Find John's contact", "status": "in_progress"},
    {"id": 3, "text": "Update phone number", "status": "pending"}
])
```

**Status Values**:
- `pending`: Not started
- `in_progress`: Currently working on
- `completed`: Done

**Progress Tracking**:
- Planner calls `update_todos()` to modify status
- System maintains TODO list state
- Planner sees current TODO list in each step

**Current Subgoal**:
- First item with `status != "completed"`

**Replanning**:
- Implicit: Planner can rewrite TODO list at any time
- Triggered when Executor returns failure after MAX_EXECUTOR_STEPS
- Planner reads Executor's narrative failure report

---

### 3. Mobile Agent v3: Historical Operations + Plan

**Format**:
```
### Plan ###
1. Open the weather app
2. Search for New York
3. Note the current temperature
4. perform the `answer` action
```

**Progress Tracking**:
```
### Historical Operations ###
Operations that have been completed before:
- Opened the weather app
- Searched for New York

### Plan ###
3. Note the current temperature
4. perform the `answer` action
```

**Two-Part Structure**:
- `completed_plan`: Historical operations (what's done)
- `plan`: Remaining steps (what's left)

**Finish Detection**:
```python
if "Finished" in plan and len(plan) < 15:
    # Task complete
```

**Replanning**:
- Manager prompted to revise when `error_flag_plan = True`
- Prompt includes: "If you are stuck with errors, think step by step about whether the overall plan needs to be revised"

---

### 4. MiniTap: Structured Subgoal Objects

**Format**:
```python
class Subgoal(BaseModel):
    id: str
    description: str
    status: SubgoalStatus  # NOT_STARTED, PENDING, SUCCESS, FAILURE
    completion_reason: str | None
    started_at: datetime | None
    ended_at: datetime | None

# Example
[
    Subgoal(id="1", description="Open Settings app", status=SUCCESS, ...),
    Subgoal(id="2", description="Navigate to Network", status=PENDING, ...),
    Subgoal(id="3", description="Disable WiFi", status=NOT_STARTED, ...)
]
```

**Status Enum**:
- `NOT_STARTED`: In queue
- `PENDING`: Currently active
- `SUCCESS`: Completed successfully
- `FAILURE`: Failed (triggers replanning)

**Progress Tracking**:
- Orchestrator manages subgoal lifecycle
- Cortex can request `complete_subgoals_by_ids`
- Status changes trigger routing decisions

**Current Subgoal**:
```python
def get_current_subgoal(plan: list[Subgoal]) -> Subgoal | None:
    for sg in plan:
        if sg.status in [NOT_STARTED, PENDING]:
            return sg
    return None
```

**Replanning** (via convergence_gate):
```python
if one_of_them_is_failure(subgoal_plan):
    return "replan"  # Go back to Planner
```

**Replanning Rules**:
- Keep completed subgoals (don't redo)
- Use `agents_thoughts` as truth source
- Adjust strategy based on observations
- Start from current state, not beginning

---

## Comparison: Plan Formats

| Framework | Structure | Granularity | Example |
|-----------|-----------|-------------|---------|
| DroidRun | Free-form numbered list | Mixed (atomic + complex) | "1. Open app\n2. Navigate to settings" |
| AutoDev | JSON array of objects | High-level semantic | `[{id:1, text:"Open app", status:"pending"}]` |
| Mobile Agent v3 | Numbered list + history | Mixed | "### Plan ###\n1. First step" |
| MiniTap | Pydantic objects | Atomic checkpoints | `Subgoal(id="1", description="...", status=PENDING)` |

---

## Subgoal Granularity

### DroidRun Manager Output Examples:
```
1. Open the Chrome browser
2. Navigate to google.com
3. Search for "weather in Tokyo"
<script>Parse the search results HTML</script>
4. Report the temperature
```
**Observations**: Mix of atomic actions and compound steps. `<script>` for non-UI operations.

### AutoDev Planner Tools:
```python
# Semantic-level, not atomic
tap(intent="click on the login button")
scroll(intent="scroll down to find the signup link")
type_text(text="john@email.com", intent="enter email in the input field")
```
**Observations**: Intent-based, leaves grounding to Executor.

### Mobile Agent v3 Plan:
```
1. Open the Contacts app
2. Search for "John"
3. Click on John's contact
4. Edit the phone number to "+1-555-0123"
5. Save the changes
```
**Observations**: Step-by-step but not atomic. Executor decides specific actions.

### MiniTap Subgoals:
```python
[
    Subgoal(description="Open Settings app"),
    Subgoal(description="Verify WiFi toggle is visible"),
    Subgoal(description="Disable WiFi"),
    Subgoal(description="Confirm WiFi is disabled")
]
```
**Observations**: Atomic checkpoints. Includes verification steps. Self-correcting pattern.

---

## Replanning Strategies

### DroidRun: Manager-Level Replan
```
Trigger: error_flag_plan = True (2+ failures)

Manager sees:
<potentially_stuck>
The last 3 actions failed:
1. click(5) - element not found
2. click(5) - element not found  
3. swipe("up") - no change
</potentially_stuck>

Manager response:
<thought>The element index 5 no longer exists. I should try a different approach...</thought>
<plan>
1. Use search function instead of scrolling
2. Search for "Settings"
3. ...
</plan>
```

### AutoDev: Planner Reads Executor Report
```
Trigger: Executor returns after MAX_EXECUTOR_STEPS with failure

Planner sees:
- Executor's narrative summary: "Could not find the login button. The screen shows a popup dialog blocking the view."

Planner response:
- New tool call: `tap(intent="dismiss the popup dialog")`
- Then retry original goal
```

### Mobile Agent v3: Error-Aware Re-Planning
```
Trigger: error_flag_plan = True

Prompt addition:
### Potentially Stuck! ###
You have encountered several failed attempts. Here are some logs:
- Attempt: Action: {...} | Description: scroll down | Outcome: Failed | Feedback: No new content

Manager re-plans with awareness of failure mode.
```

### MiniTap: Convergence Gate + Replanning Prompt
```
Trigger: SubgoalStatus.FAILURE

Planner prompt rules:
- Preserve completed subgoals
- Use agent_thoughts as truth source
- Based on observations, adjust strategy (e.g., if scrolling fails, use search)
- Continue from current state
```

---

## Key Insights

### 1. Structured vs Freeform Plans
- **Structured (AutoDev, MiniTap)**: Machine-parseable, enables automated status tracking
- **Freeform (DroidRun, Mobile Agent v3)**: More flexible but requires LLM to maintain consistency

### 2. Verification Subgoals (MiniTap)
MiniTap's prompt explicitly encourages:
```
If there are format constraints, add a final subgoal to verify the result.
```
This self-correcting pattern improves success rates.

### 3. No Loops in Plans (MiniTap)
```
❌ "Repeat step 2 three times"
✅ "2a. Do X\n2b. Do X\n2c. Do X"
```
Explicit unrolling prevents infinite loops.

### 4. Planner Defines WHAT, Not HOW (MiniTap)
```
Planner: "What to achieve" (Open app, Find contact)
Cortex: "How to achieve" (Which element to tap, what coordinates)
```
Clear separation prevents mixed-level reasoning.

### 5. Historical Operations Preservation
All frameworks preserve completed work:
- DroidRun: Removes completed items from plan
- AutoDev: status = "completed" 
- Mobile Agent v3: Moves to `completed_plan`
- MiniTap: status = SUCCESS (never re-run)

---

## Design Recommendations

1. **Use structured subgoals**: Pydantic/JSON objects enable reliable status tracking

2. **Include verification steps**: Self-correcting subgoals improve success rate

3. **Preserve progress**: Never restart from scratch; continue from current state

4. **Separate WHAT from HOW**: Planner sets goals, Executor determines actions

5. **Failure context for replanning**: Show recent errors to enable strategy change

6. **Avoid loops in plans**: Unroll repetitive tasks into explicit steps

7. **Semantic granularity**: Subgoals should be "clear checkpoints", not atomic actions

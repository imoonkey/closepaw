# Note 4: State Management and Context Sharing

> Comparative analysis of shared state, agent-specific context, and information flow between agents.

---

## Overview

State management determines:
1. **What information is shared** across all agents
2. **What information is private** to specific agents
3. **How information flows** between components

| Framework | State Container | Shared State | Agent-Specific | Update Pattern |
|-----------|-----------------|--------------|----------------|----------------|
| **DroidRun** | `DroidAgentState` | All fields | (none) | Mutable class |
| **AutoDev** | Multiple objects | Screenshot, Scratchpad | Message history | Method params |
| **Mobile Agent v3** | `InfoPool` | All fields | (none) | Mutable dataclass |
| **MiniTap** | `State` (LangGraph) | Most fields | `executor_messages` | Immutable updates |

---

## State Structures by Framework

### 1. DroidRun: DroidAgentState

```python
class DroidAgentState:
    # === TASK CONTEXT (shared) ===
    instruction: str          # User goal
    step_number: int          # Current step
    
    # === DEVICE STATE (refreshed each step) ===
    formatted_device_state: str   # UI description
    focused_text: str             # Text in focused input
    a11y_tree: List[Dict]         # Raw accessibility tree
    screenshot: bytes             # Current screenshot
    
    # === PLANNING STATE (Manager owns) ===
    plan: str                     # Current plan
    current_subgoal: str          # Active subgoal
    manager_answer: str           # Final answer
    
    # === HISTORY (append-only) ===
    action_history: List[Dict]    # All actions
    action_outcomes: List[bool]   # Success/failure
    memory: str                   # Accumulated notes
    message_history: List[Dict]   # Conversation log
    
    # === ERROR HANDLING ===
    error_flag_plan: bool         # Stuck indicator
    err_to_manager_thresh: int    # Failure threshold (default: 2)
```

**Access Pattern**:
- All agents read/write same state object
- State passed through workflow events
- Manager updates: `plan`, `current_subgoal`, `memory`
- Executor updates: `action_history`, `action_outcomes`

---

### 2. AutoDev: Distributed State

```python
# Planner receives:
- goal: str                    # User goal (first step only)
- screenshot: Image            # Current screen (scaled 0.4x)
- system_info: str             # Device date
- system_warnings: List[str]   # Navigation warnings
- transcription: str | None    # OCR text (optional)
- todo_list: List[TODO]        # Current TODOs
- scratchpad: Dict[str, str]   # Persistent key-value

# Executor receives (per session):
- query: str                   # Planner's semantic instruction
- screenshot: Image            # Current screen (with dimensions)
- dimensions: Tuple[int, int]  # Screen size for coordinate calc
# Note: NO action history, NO previous context
```

**Access Pattern**:
- **Planner**: Sees global state, manages strategy
- **Executor**: Fresh session each call, only sees current query + screenshot
- **Scratchpad**: Shared persistent storage via `createItem`/`fetchItem`

**Key Insight**: Executor is **intentionally stateless** to force Planner to write complete instructions.

---

### 3. Mobile Agent v3: InfoPool Dataclass

```python
@dataclass
class InfoPool:
    # === USER INPUT ===
    instruction: str = ""                    # User's goal
    additional_knowledge_manager: str = ""   # Task tips for Manager
    additional_knowledge_executor: str = ""  # Guidelines for Executor
    
    # === WORKING MEMORY (all agents read) ===
    action_history: list        # All executed actions
    summary_history: list       # Action descriptions
    action_outcomes: list       # "A" (success), "B" (wrong page), "C" (no change)
    error_descriptions: list    # Error feedback
    important_notes: str = ""   # Notetaker's recorded info
    
    # === PLANNING STATE (Manager owns) ===
    plan: str = ""              # Current step-by-step plan
    completed_plan: str = ""    # Historical operations completed
    progress_status: str = ""   # Current progress description
    
    # === ERROR HANDLING ===
    error_flag_plan: bool = False     # Stuck indicator
    err_to_manager_thresh: int = 2    # Consecutive failures threshold
    
    # === LAST ACTION (for reflection) ===
    last_action: str = ""
    last_summary: str = ""
    last_action_thought: str = ""
```

**Access Pattern**:
- Single `InfoPool` instance passed to all agents
- Manager reads/writes: planning fields, important_notes
- Executor reads: plan, progress_status; writes: action_history
- ActionReflector reads: before/after state; writes: action_outcomes
- Notetaker reads: screenshot, progress; writes: important_notes

---

### 4. MiniTap: LangGraph State

```python
class State(BaseModel):
    # === MESSAGES (LangGraph core) ===
    messages: list[AnyMessage]
    remaining_steps: int | None
    
    # === PLANNER ===
    initial_goal: str
    
    # === ORCHESTRATOR ===
    subgoal_plan: list[Subgoal]
    
    # === CONTEXTOR ===
    latest_ui_hierarchy: list[dict] | None
    latest_screenshot: str | None
    focused_app_info: str | None
    device_date: str | None
    
    # === CORTEX ===
    structured_decisions: str | None
    complete_subgoals_by_ids: list[str]
    
    # === EXECUTOR (agent-specific) ===
    executor_messages: list[AnyMessage]  # Separate history!
    cortex_last_thought: str | None
    
    # === SHARED CONTEXT ===
    agents_thoughts: list[str]      # All agent reasoning
    scratchpad: dict[str, str]      # Persistent key-value
```

**Access Pattern**:
- LangGraph returns new State object each step (immutable updates)
- **Shared**: `initial_goal`, `subgoal_plan`, `agents_thoughts`, `scratchpad`
- **Agent-specific**: `executor_messages` (Executor's private conversation)
- **Transient**: `structured_decisions`, `complete_subgoals_by_ids` (cleared each cycle)

---

## What's Shared vs Agent-Specific

| Framework | Shared Across All | Manager/Planner Only | Executor Only |
|-----------|-------------------|----------------------|---------------|
| DroidRun | Everything | (owns `plan`, `memory`) | (owns action execution) |
| AutoDev | Scratchpad, TODO list | Strategy, TODO management | Query + screenshot only |
| Mobile Agent v3 | InfoPool fields | Planning fields | Action history (writes) |
| MiniTap | `agents_thoughts`, `scratchpad`, `subgoal_plan` | Goal decomposition | `executor_messages` |

---

## Context Flow Diagrams

### DroidRun: Shared State Flow
```
                    DroidAgentState
                          │
        ┌─────────────────┼─────────────────┐
        ▼                 ▼                 ▼
    Manager          Executor          Scripter
        │                 │                 │
        └───► plan ◄──────┼─────────────────┘
                          │
                action_history
```

### AutoDev: Isolated Executor Sessions
```
Planner                  Executor Session 1
   │                            │
   ├──► tap(intent) ──────────► │ (fresh context)
   │                            │──► click(x,y)
   │                            │──► report(notes)
   │◄────── notes ──────────────┘
   │
   ├──► scroll(intent) ────────► Executor Session 2
   │                            │ (fresh context)
   ...
```

### Mobile Agent v3: InfoPool Hub
```
                      InfoPool
                         │
    ┌────────────────────┼────────────────────┐
    │                    │                    │
 Manager              Executor          ActionReflector
    │                    │                    │
    ▼                    ▼                    ▼
  plan              action_history       action_outcomes
  completed_plan    summary_history      error_descriptions
  progress_status                              │
    │                    │                    ▼
    └────────────────────┴──────────────► Notetaker
                                              │
                                              ▼
                                        important_notes
```

### MiniTap: State Machine Flow
```
State
  │
  ├──► Planner ──► subgoal_plan ──────────────────┐
  │                                                │
  ├──► Orchestrator ──► subgoal status ◄──────────┤
  │                                                │
  ├──► Contextor ──► ui_hierarchy, screenshot ────┤
  │                                                │
  ├──► Cortex ──► structured_decisions ───────────┤
  │              complete_subgoals_by_ids         │
  │                        │                      │
  ├──► Executor ◄──────────┘                      │
  │      │                                        │
  │      └──► executor_messages (private)         │
  │                                               │
  └──► agents_thoughts ◄─────────────────────────┘ (all agents write)
```

---

## Screenshot and UI State Handling

| Framework | Screenshot Refresh | UI Tree | Shared How |
|-----------|-------------------|---------|------------|
| DroidRun | Each step | `formatted_device_state` | State field |
| AutoDev | Each Planner/Executor call | (not in base) | Method param |
| Mobile Agent v3 | Each step + after action | (not used) | Captured fresh |
| MiniTap | Contextor captures | JSON hierarchy | State field |

### Mobile Agent v3 Two-Screenshot Pattern:
```python
# Before action
screenshot_before = controller.get_screenshot()

# Execute
controller.execute(action)

# After action
screenshot_after = controller.get_screenshot()

# ActionReflector sees BOTH
action_reflector.predict([screenshot_before, screenshot_after])
```

### MiniTap Dual Perception:
```python
# Contextor captures both
latest_ui_hierarchy = adb.get_ui_hierarchy()  # JSON tree
latest_screenshot = adb.screenshot()          # Image

# Cortex uses both together
cortex.prompt(ui_hierarchy, screenshot)
```

---

## Error Context Propagation

### DroidRun:
```python
# Consecutive failures trigger flag
if consecutive_failures >= err_to_manager_thresh:
    error_flag_plan = True

# Manager sees error history
error_history = recent_failed_actions[-3:]  # in <potentially_stuck>
```

### Mobile Agent v3:
```python
# Same mechanism
if consecutive_failures >= err_to_manager_thresh:
    info_pool.error_flag_plan = True

# Manager sees failure logs
for action, outcome, error in zip(history, outcomes, errors):
    if outcome != "A":
        add_to_stuck_prompt(action, outcome, error)
```

### MiniTap:
```python
# Automatic via convergence_gate
if any(sg.status == FAILURE for sg in subgoal_plan):
    return "replan"  # Routes to Planner

# Planner sees all thoughts
agents_thoughts  # Contains reasoning from failed attempts
```

---

## Key Insights

### 1. Executor Isolation (AutoDev)
AutoDev's Executor is **stateless by design**:
- No memory of previous tool calls
- Forces Planner to write complete, self-contained instructions
- Prevents Executor from making assumptions
- Downside: More tokens per instruction

### 2. Shared vs Private Message History (MiniTap)
```python
# Global: agents_thoughts (reasoning for all)
# Private: executor_messages (tool call history)
```
This allows Executor to have its own conversation while other agents see summarized thoughts.

### 3. Two-Screenshot Verification (Mobile Agent v3)
ActionReflector seeing before/after enables:
- Accurate detection of "no change" failures
- Understanding if action went to wrong page
- Confirmation of expected behavior

### 4. Transient vs Persistent Fields
- **Transient**: `structured_decisions`, `current_subgoal` (reset each step)
- **Persistent**: `memory`, `scratchpad`, `important_notes` (accumulate)

---

## Design Recommendations

1. **Centralized state object**: Single source of truth reduces sync bugs

2. **Agent-specific message history**: Allow agents private reasoning space

3. **Explicit error context**: Propagate failure details to planning layer

4. **Transient action fields**: Clear per-step decisions to prevent stale data

5. **Persistent memory fields**: Accumulate important information

6. **Before/after comparison**: Capture screen state around action execution

7. **Stateless executor option**: Consider for complex tasks requiring precise instructions

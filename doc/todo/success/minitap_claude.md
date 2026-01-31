# Minitap Mobile-Use Analysis

**Status**: 100% AndroidWorld benchmark (First to complete all tasks)  
**Architecture**: Multi-agent with LangGraph  
**Source**: `.reference/minitap-mobile-use/`

## Executive Summary

Minitap achieved the highest score on AndroidWorld through a sophisticated multi-agent architecture that separates concerns between planning, perception, decision-making, and execution. Key innovations include:
- **Clear agent separation** (Planner → Orchestrator → Cortex → Executor)
- **Robust fallback targeting** (coordinates → resource_id → text)
- **Persistent scratchpad memory** for cross-app data transfer
- **Systematic failure handling** with replanning

---

## Architecture Overview

```
START → Planner → Orchestrator → Contextor → Cortex
                       ↑                        ↓
                       └── convergence ←── Executor → Tool Node → Summarizer
```

### Agent Roles

| Agent | Role | Key Responsibility |
|-------|------|-------------------|
| **Planner** | Strategic | Creates subgoals from user goal, handles replanning |
| **Orchestrator** | Coordination | Tracks subgoal completion, decides replanning |
| **Contextor** | Guard | Verifies app lock compliance, relaunches if needed |
| **Cortex** | Brain | Analyzes screen + UI hierarchy, decides actions |
| **Executor** | Hands | Interprets Cortex decisions, calls tools |
| **Summarizer** | Memory | Records agent thoughts for history |

### Why Multi-Agent Works

1. **Separation of concerns**: Each agent has a focused responsibility
2. **Graceful failure recovery**: Orchestrator can trigger replanning when stuck
3. **Context preservation**: Agent thoughts persist across execution loops
4. **Parallel paths**: Cortex can trigger both orchestrator (completion) AND executor (action)

---

## Tool Design

### Tool List
```python
EXECUTOR_WRAPPERS_TOOLS = [
    back_wrapper,
    open_link_wrapper,
    tap_wrapper,
    long_press_on_wrapper,
    swipe_wrapper,
    focus_and_input_text_wrapper,
    erase_one_char_wrapper,
    launch_app_wrapper,
    stop_app_wrapper,
    focus_and_clear_text_wrapper,
    press_key_wrapper,
    wait_for_delay_wrapper,
    # Scratchpad tools for persistent memory
    save_note_wrapper,
    read_note_wrapper,
    list_notes_wrapper,
]
```

### Key Tool Innovations

#### 1. Fallback Targeting (tap.py)
```python
# Order: coordinates → resource_id → text
1. Try with COORDINATES FIRST (visual approach)
2. If coordinates failed, try with resource_id
3. If resource_id failed, try with text (last resort)
```

**Target Object Structure**:
```json
{
  "target": {
    "resource_id": "com.app:id/button",
    "resource_id_index": 0,
    "bounds": {"x": 100, "y": 200, "width": 50, "height": 50},
    "text": "Submit",
    "text_index": 0
  }
}
```

This multi-selector approach handles:
- Stale coordinates ("Out of bounds")
- Screen changes ("No element found")
- Duplicate elements (using indexes)

#### 2. Agent Thought Parameter
Every tool requires `agent_thought` parameter:
```python
async def tap(
    agent_thought: str,  # WHY this action is being performed
    target: Target,
    ...
)
```

This creates an audit trail and helps with debugging/failure analysis.

#### 3. Scratchpad Memory
Persistent key-value storage for cross-app data:
```python
save_note(key="recipe_ingredients", content="...")
read_note(key="recipe_ingredients")
list_notes()
```

Use case: Copy data from app A, paste in app B

---

## System Prompts Analysis

### Planner Prompt (Highlights)
```markdown
## Planning Guidelines

**Subgoals should be:**
- Purpose-driven: "Open conversation with Alice to send message" not just "Tap chat"
- Sequential: Each step prepares the next
- Not too granular: High-level milestones, not button-by-button
- No loops: Instead of "repeat 3 times", write 3 separate subgoals
- Self-Correcting: Include final subgoal to verify and fix if necessary

**Shortcuts**: Always prefer `launch_app` over manual app drawer navigation
```

### Cortex Prompt (Key Rules)
```markdown
## 🚨 CRITICAL RULES

1. **Analyze Agent Thoughts Before Acting**
   - Detect repeated failures → change strategy, don't retry blindly

2. **Never Repeat Failed Actions**
   - Ask: "How would a human solve this differently?"

3. **Unpredictable Actions = Isolate Them**
   - `back`, `launch_app`, navigation taps → ONLY action in that turn

4. **Complete Goals Only on OBSERVED Evidence**
   - Never mark complete "in advance"

5. **Data Fidelity Over "Helpfulness"**
   - Transcribe content exactly as-is
```

### Executor Prompt (Key Rules)
```markdown
## Your Job
1. **Parse** structured decisions from Cortex
2. **Call tools** in the specified order
3. **Always include `agent_thought`** for each tool

## Rules
- Don't reason about strategy - just execute what Cortex decided
- `agent_thought` must be specific - not generic/vague
- Order matters - tools execute in the order you return them
```

---

## State Management

### LangGraph State (state.py)
```python
class State(BaseModel):
    messages: list[AnyMessage]
    remaining_steps: int | None
    
    # planner
    initial_goal: str
    
    # orchestrator
    subgoal_plan: list[Subgoal]
    
    # contextor
    latest_ui_hierarchy: list[dict] | None
    latest_screenshot: str | None
    focused_app_info: str | None
    device_date: str | None
    
    # cortex
    structured_decisions: str | None
    complete_subgoals_by_ids: list[str]
    
    # executor
    executor_messages: list[AnyMessage]
    cortex_last_thought: str | None
    
    # common
    agents_thoughts: list[str]
    scratchpad: dict[str, str]
```

---

## Success Factors

### 1. Explicit Failure Handling
- Agent thoughts track what worked/failed
- Orchestrator triggers replanning when stuck
- Cortex analyzes history before deciding

### 2. Isolated Unpredictable Actions
- Navigation changes (`back`, `launch_app`) are single actions
- Wait to observe new screen before next decision

### 3. Multi-Selector Targeting
- Never rely on single selector
- Coordinates → resource_id → text fallback chain

### 4. Purpose-Driven Subgoals
- High-level milestones, not button-by-button
- Self-correcting verification steps

### 5. Cross-App Memory
- Scratchpad persists across agent loops
- Enables complex data transfer tasks

---

## Applicability to Our Agent

### Can Adopt Immediately
1. **Multi-selector targeting** in tap/click tools
2. **Agent thought parameter** for all tool calls
3. **Scratchpad/memory tool** for cross-app tasks
4. **Isolated navigation actions** rule

### Requires Architecture Change
1. Multi-agent separation (Planner/Cortex/Executor)
2. LangGraph-style state management
3. Conditional routing based on completion/failure

### Key Takeaways
- **Separation matters**: Cortex (brain) vs Executor (hands) prevents confusion
- **Failure analysis**: Always check what failed before retrying
- **Robust targeting**: Multiple selectors > single selector
- **Memory persistence**: Critical for multi-app workflows

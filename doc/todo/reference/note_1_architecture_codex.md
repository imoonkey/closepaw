# Note 1: Multi-Agent Architecture Patterns (Codex)

> Comparative synthesis across DroidRun, AutoDev, Mobile Agent v3, and MiniTap.

## Sources (local)
- doc/todo/reference/droidrun_architecture.md
- doc/todo/reference/autodevice_android_world.md
- doc/todo/reference/mobile_agent_v3_architecture.md
- doc/todo/reference/minitap-mobile-use.md
- .reference/mobile_agent/droidrun/droidrun/agent/manager/manager_agent.py
- .reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py
- .reference/mobile_agent/MobileAgent/Mobile-Agent-v3/android_world_v3/android_world/agents/mobile_agent_v3_agent.py
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/planner.md
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/orchestrator/orchestrator.md

---

## Overview
All four frameworks converge on a **two-layer architecture** (Planner/Manager + Executor), then add specialized agents to handle verification, memory, off-device work, or context control.

| Framework | Base Split | Additional Agents / Modes | Primary Loop Shape |
|-----------|------------|---------------------------|--------------------|
| DroidRun | Manager -> Executor | Scripter, TextManipulator, StructuredOutput, CodeAct (direct mode) | Event-driven workflow |
| AutoDev | Planner -> Executor | None (but direct planner tools) | Planner loop + per-call executor sessions |
| Mobile Agent v3 | Manager -> Executor | ActionReflector, Notetaker | Fixed loop with verification stage |
| MiniTap | Planner -> Cortex -> Executor | Orchestrator, Contextor, Summarizer (plus Hopper/Outputter) | LangGraph state machine |

---

## Framework Details

### DroidRun: Manager-Executor with Specialist Branches
- **Core**: Manager plans and selects subgoals; Executor performs single atomic action per step.
- **Specialists**: 
  - **Scripter** for off-device Python tasks (< script> plan blocks).
  - **TextManipulator** for complex text editing tasks (TEXT_TASK).
  - **StructuredOutput** for schema-constrained responses.
- **Direct Mode**: CodeActAgent bypasses planning and executes Python directly for simple or data tasks.

### AutoDev: Planner-Executor with Direct Tool Routing
- **Core**: Planner issues semantic tool calls; Executor grounds them to coordinates and executes.
- **Direct Planner Tools**: open_app, go_back, update_todos, createItem/fetchItem, finish_task bypass Executor.
- **Executor Sessions**: Executor is stateless across calls, enforcing self-contained queries.

### Mobile Agent v3: Manager-Executor with Verification & Notes
- **Core**: Manager produces a plan; Executor performs atomic actions.
- **ActionReflector**: Verifies action outcome with before/after screenshots (A/B/C outcomes).
- **Notetaker**: Optional memory agent triggered after successful actions.

### MiniTap: Planner + Orchestrator + Cortex Pipeline
- **Core**: Planner creates subgoals; Cortex decides structured actions; Executor only formats tool calls.
- **Orchestrator**: Tracks completion and decides when to replan.
- **Contextor**: Captures UI state and enforces app lock.
- **Summarizer**: Prunes context to avoid overflow.

---

## Key Pattern Variations

1. **Separation of strategy vs. grounding**
   - AutoDev keeps Planner entirely semantic.
   - MiniTap pushes strategy into Cortex, leaving Executor “dumb.”

2. **Verification as first-class agent**
   - Mobile Agent v3 adds explicit action verification (Reflector).
   - Others rely on next planning cycle to detect failures.

3. **Optional specialist routing**
   - DroidRun and MiniTap branch to specialized agents based on task type or constraints.
   - AutoDev keeps a single planner but uses direct tools to reduce overhead.

4. **Control logic style**
   - DroidRun uses event-driven workflow orchestration.
   - MiniTap uses a state machine with convergence gates.
   - AutoDev uses looped planning with bounded executor sessions.
   - Mobile Agent v3 uses a fixed step loop with verification.

---

## Takeaways
- Two-layer planning/execution is the stable baseline.
- High-performing agents add **verification** and **specialist routing** rather than deeper planning logic.
- Strong control logic (state machine or event-driven) reduces drift and repeated failures.

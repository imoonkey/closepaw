# Note 4: State Management and Shared Context (Codex)

> What context is shared across agents and how state is organized.

## Sources (local)
- doc/todo/reference/droidrun_architecture.md
- doc/todo/reference/autodevice_android_world.md
- doc/todo/reference/mobile_agent_v3_architecture.md
- doc/todo/reference/minitap-mobile-use.md
- .reference/mobile_agent/droidrun/droidrun/agent/droid/state.py
- .reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py
- .reference/mobile_agent/MobileAgent/Mobile-Agent-v3/android_world_v3/android_world/agents/mobile_agent_v3_agent.py
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/planner.md

---

## Overview
State sharing ranges from a **single shared state object** (DroidRun, Mobile Agent v3) to **tool-mediated context passing** (AutoDev) and **state-machine snapshots** (MiniTap).

---

## DroidRun
- **Shared state object**: `DroidAgentState` is the coordination hub.
- **Shared fields**: instruction, formatted device state, screenshot, previous device state, plan/subgoal, memory, action history, error flags, app tracking, custom variables.
- **Implication**: Manager and Executor operate over the same mutable state, enabling rich cross-step context.

## AutoDev
- **State distribution**:
  - Shared scratchpad (`Scratchpad`) for persistent data.
  - Shared TODO list (tool-based).
  - Agent-level navigation state (seen items, scroll history, visited screens).
- **Isolation**: Planner and Executor exchange only the query/screenshot context; executor sessions are stateless.
- **Implication**: Strong separation of responsibilities, but requires explicit scratchpad usage for memory.

## Mobile Agent v3
- **Shared state object**: `InfoPool` dataclass is used by all agents.
- **Shared fields**: plan, completed_plan, progress_status, action history/outcomes, error logs, important_notes, UI element lists, last action metadata.
- **Implication**: Similar to DroidRun, but with explicit verification fields (action outcomes) and more structured progress tracking.

## MiniTap
- **State machine**: LangGraph state passed between agents.
- **Shared fields**: subgoal_plan, current subgoal, UI hierarchy, screenshot, device date, app lock state, scratchpad, agents_thoughts.
- **Implication**: Each agent reads/writes only its slice; Summarizer prunes to keep state compact.

---

## Comparison Highlights

| Aspect | DroidRun | AutoDev | Mobile Agent v3 | MiniTap |
|--------|----------|---------|-----------------|---------|
| Core state type | Shared object | Tool-mediated + local | Shared object | State machine |
| Device context | Full device state + screenshot | Screenshot + dimensions | Screenshot + UI lists | UI hierarchy + screenshot |
| Memory sharing | Memory string in state | Scratchpad | InfoPool notes | Scratchpad + thoughts |
| Error tracking | error_flag_plan + history | Executor reports + nav state | action_outcomes + error_flag_plan | Orchestrator decisions |

---

## Takeaways
- Shared state objects reduce coordination friction but can over-share context.
- Tool-mediated state (AutoDev) enforces clean separation at the cost of more prompt discipline.
- State-machine routing (MiniTap) provides explicit control over what each agent sees.

# Note 5: Planner Output Formats and Granularity (Codex)

> How planning agents express intent and how much grounding is left to executors.

## Sources (local)
- doc/todo/reference/droidrun_prompts.md
- doc/todo/reference/autodevice_android_world.md
- doc/todo/reference/mobile_agent_v3_architecture.md
- doc/todo/reference/minitap-mobile-use.md
- .reference/mobile_agent/droidrun/droidrun/agent/manager/manager_agent.py
- .reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/prompts.py
- .reference/mobile_agent/MobileAgent/Mobile-Agent-v3/android_world_v3/android_world/agents/mobile_agent_v3_agent.py
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/planner.md

---

## Overview
Planner outputs range from **freeform text plans** (DroidRun, Mobile Agent v3) to **explicit tool calls** (AutoDev) and **structured subgoal lists** (MiniTap).

---

## DroidRun
- **Format**: XML response with `<plan>`, `<current_subgoal>`, `<add_memory>`, and optional `<script>` blocks.
- **Granularity**: High-level steps; Executor decides atomic actions.
- **Special handling**: If `<script>` appears, Scripter executes off-device tasks.

## AutoDev
- **Format**: Tool-call outputs (planner emits semantic tools like `tap(intent)`, `type_text(text, intent)`).
- **Granularity**: Intent-level; grounding to coordinates is entirely Executor’s job.
- **Direct actions**: Some tools are executed without Executor (open_app, update_todos, createItem/fetchItem).

## Mobile Agent v3
- **Format**: Text plan with numbered steps and progress updates.
- **Granularity**: Broad subgoals; Executor selects the next atomic action.
- **Completion signal**: Manager outputs "Finished" in plan to terminate.

## MiniTap
- **Format**: JSON list of subgoals (descriptions only).
- **Granularity**: Milestone subgoals, not button-by-button.
- **Delegation**: Cortex translates subgoal into structured decisions; Executor only formats tool calls.

---

## Comparison Highlights

| Aspect | DroidRun | AutoDev | Mobile Agent v3 | MiniTap |
|--------|----------|---------|-----------------|---------|
| Output structure | XML + text | Tool calls | Text plan | JSON subgoals |
| Granularity | High-level | Intent-level | High-level | Milestone-level |
| Grounding owner | Executor | Executor | Executor | Cortex -> Executor |
| Extra routing | Scripter/TextManipulator | Direct tools | Reflector/Notetaker | Orchestrator/Contextor |

---

## Takeaways
- Structured planner outputs reduce ambiguity (AutoDev, MiniTap) but require stricter schemas.
- Freeform plans are flexible but rely heavily on Executor discipline and error feedback.
- Introducing an intermediate “decision” layer (MiniTap Cortex) separates strategy from tool formatting cleanly.

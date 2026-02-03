# Note 3: Subgoals, TODOs, and Progress Tracking (Codex)

> How each framework represents and updates task progress.

## Sources (local)
- doc/todo/reference/droidrun_architecture.md
- doc/todo/reference/droidrun_prompts.md
- doc/todo/reference/autodevice_android_world.md
- doc/todo/reference/mobile_agent_v3_architecture.md
- doc/todo/reference/minitap-mobile-use.md
- .reference/mobile_agent/droidrun/droidrun/agent/droid/state.py
- .reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/todo_list.py
- .reference/mobile_agent/MobileAgent/Mobile-Agent-v3/android_world_v3/android_world/agents/mobile_agent_v3_agent.py
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/planner.md
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/orchestrator/orchestrator.md

---

## Overview
All systems track progress, but with different structures and update rules:
- **DroidRun**: XML plan + current subgoal string (Manager updates each step).
- **AutoDev**: explicit TODO list via tool calls (Planner owns updates).
- **Mobile Agent v3**: plan text + completed_plan history (Manager updates).
- **MiniTap**: structured subgoal list with statuses (Planner creates, Orchestrator completes).

---

## DroidRun
- **Format**: `<plan>` with numbered steps + `current_subgoal` selected by Manager.
- **Update rule**: Manager regenerates/updates plan each cycle; Executor only acts on current subgoal.
- **Completion**: Manager outputs `<request_accomplished>` when done.

## AutoDev
- **Format**: `update_todos([...])` tool, each item includes `id`, `content`, `priority`, `status`.
- **Update rule**: Planner marks one item `in_progress` and advances status to `completed` as steps finish.
- **Completion**: Planner calls `finish_task(success=...)` when TODOs indicate done.

## Mobile Agent v3
- **Format**: `plan` (numbered list string) + `completed_plan` (history string).
- **Update rule**: Manager updates plan and completed_plan every step; Executor focuses on first few subgoals.
- **Completion**: Plan becomes "Finished" (short string check) to terminate loop.

## MiniTap
- **Format**: JSON list of subgoals with IDs and statuses in `subgoal_plan`.
- **Update rule**: Planner creates; Cortex proposes completion; Orchestrator confirms via `completed_subgoal_ids`.
- **Completion**: When all subgoals are `SUCCESS` or Orchestrator returns `needs_replanning`/end.

---

## Comparison Highlights

| Aspect | DroidRun | AutoDev | Mobile Agent v3 | MiniTap |
|--------|----------|---------|-----------------|---------|
| Data structure | XML plan + string | Structured list (tool) | Plan + history strings | Structured list with status |
| Owner | Manager | Planner | Manager | Orchestrator (with Planner + Cortex) |
| Update cadence | Every step | When needed | Every step | On completion/replan |
| Granularity | Subgoal text | Explicit TODOs | Subgoal text | Milestone subgoals |

---

## Takeaways
- Structured TODO lists (AutoDev) are best for reliable progress tracking across long tasks.
- MiniTap’s explicit subgoal status + Orchestrator gating gives the cleanest replan triggers.
- DroidRun and Mobile Agent v3 rely on freeform text plans, which are flexible but harder to validate.

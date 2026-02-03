# Note 6: Subgoal Updates and Replanning Triggers (Codex)

> How each framework detects failure and decides to replan.

## Sources (local)
- doc/todo/reference/droidrun_architecture.md
- doc/todo/reference/droidrun_prompts.md
- doc/todo/reference/autodevice_android_world.md
- doc/todo/reference/mobile_agent_v3_architecture.md
- doc/todo/reference/minitap-mobile-use.md
- .reference/mobile_agent/droidrun/droidrun/agent/droid/state.py
- .reference/mobile_agent/autodevice_android_world/android_world/agents/autodev_agent.py
- .reference/mobile_agent/MobileAgent/Mobile-Agent-v3/android_world_v3/android_world/agents/mobile_agent_v3_agent.py
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/orchestrator/orchestrator.md

---

## Overview
Replanning is typically triggered by **repeat failures** or **stalled progress**, but each framework codifies this differently.

---

## DroidRun
- **Trigger**: `error_flag_plan` set after consecutive failures (threshold `err_to_manager_thresh`, default 2).
- **Mechanism**: Manager prompt injects `<potentially_stuck>` with error history when flagged.
- **Behavior**: Manager reissues a revised plan and new subgoal each cycle.

## AutoDev
- **Trigger**: Executor reports failure or hits `MAX_EXECUTOR_STEPS` (default 10).
- **Mechanism**: Planner reads Executor narrative summary and updates TODO list.
- **Behavior**: Planner switches strategy; avoids repeating failed approach.

## Mobile Agent v3
- **Trigger**: ActionReflector returns repeated failures (outcome B or C), incrementing failure count.
- **Mechanism**: `error_flag_plan` activates after `err_to_manager_thresh` (default 2); Manager replans.
- **Behavior**: If last action is invalid, Manager is skipped for a quick retry; otherwise full replan.

## MiniTap
- **Trigger**: Orchestrator decides `needs_replanning = true` when subgoal failures repeat or plan is unworkable.
- **Mechanism**: Convergence gate routes to Planner with `previous_plan` and agent thoughts.
- **Behavior**: Planner keeps completed subgoals and pivots strategy based on observed failures.

---

## Comparison Highlights

| Aspect | DroidRun | AutoDev | Mobile Agent v3 | MiniTap |
|--------|----------|---------|-----------------|---------|
| Failure signal | error_flag_plan | Executor failure/step limit | Reflector outcomes + error_flag_plan | Orchestrator decision |
| Threshold | err_to_manager_thresh (2) | MAX_EXECUTOR_STEPS (10) | err_to_manager_thresh (2) | Subgoal failure pattern |
| Replan owner | Manager | Planner | Manager | Planner (via Orchestrator) |

---

## Takeaways
- Explicit failure thresholds (DroidRun, Mobile Agent v3) create predictable replan timing.
- Bounded executor sessions (AutoDev) prevent infinite loops and force replanning.
- Orchestrator-driven replanning (MiniTap) cleanly separates detection from plan generation.

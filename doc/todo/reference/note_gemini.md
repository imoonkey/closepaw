# Android Agent Architecture Reference Notes

Based on analysis of DroidRun, AutoDev, Mobile Agent v3, and MiniTap.

## 1. Two-layer architecture is common, while some add more agents on top of it.
Most successful agents employ a **Planner-Executor** pattern to separate high-level reasoning from low-level grounding.

- **DroidRun**: Uses **Manager-Executor** (Reasoning Mode).
    - **Manager**: Planning, context tracking, deciding next step.
    - **Executor**: "Dumb robot" executing atomic actions (no planning).
    - *Extensions*: Adds **Scripter** (off-device code), **TextManipulator** (complex edits), and **StructuredOutput** agents.
- **AutoDev**: Uses **Planner-Executor** (Hierarchy).
    - **Planner**: Semantics-level tools (`tap(intent)`), manages TODOs.
    - **Executor**: Coordinates-level tools (`click(x,y)`), handles grounding.
- **Mobile Agent v3**: Uses **Manager-Executor** with helper agents.
    - **Manager**: High-level planning.
    - **Executor**: Atomic action selection.
    - *Extensions*: **ActionReflector** (verifies outcome), **Notetaker** (records memory).
- **MiniTap**: Uses **Planner-Executor** with Orchestrator, Contextor, Cortex and Summarizer.
    - **Planner**: Break goal into subgoals.
    - **Orchestrator**: Manages subgoal lifecycle.
    - **Contextor**: Gathers perception (State).
    - **Cortex**: The "Brain", makes structural decisions.
    - **Executor**: "Hands", converts decisions to tool calls.

## 2. Memory is common, organized in different formats. Note taking is often done by a particular agent.
Agents use explicit memory structures to handle long-horizon tasks and cross-app data transfer.

- **DroidRun**:
    - **Format**: `memory` (append-only string) and `action_history` (list of dicts).
    - **Writer**: **ManagerAgent** updates memory via XML tags `<add_memory>`.
    - **Usage**: Passed to Manager in every prompt.
- **AutoDev**:
    - **Format**: `scratchpad` (key-value store, e.g., `fetchItem(key)`).
    - **Writer**: Both **Planner** and **Executor** can use `createItem`/`fetchItem` tools.
    - **Usage**: Critical for transferring data like extracted text or list items between apps.
- **Mobile Agent v3**:
    - **Format**: `important_notes` (string) stored in `InfoPool`.
    - **Writer**: **Notetaker Agent** (specialized agent), triggered after successful actions (`outcome == "A"`) when significant info needs recording.
    - **Usage**: Injected into Manager/Executor prompts.
- **MiniTap**:
    - **Format**: `scratchpad` (KV store) + `agent_thoughts` (history log).
    - **Writer**: **Executor** can call `save_note` / `read_note`. **Summarizer** prunes old message history to manage context window.
    - **Usage**: `scratchpad` for cross-app data; `thoughts` for reasoning history.

## 3. Todo list or subgoals are common, formats vary, usually managed by planner/manager.
Structured progress tracking helps agents stay focused and recover from errors.

- **DroidRun**:
    - **Format**: XML `<plan>` section containing numbered steps.
    - **Update**: **Manager** regenerates the full plan at each step.
- **AutoDev**:
    - **Format**: Explicit `todo_list` object (list of dicts: `{id, text, status}`).
    - **Update**: **Planner** calls `update_todos([...])` tool to modify statuses (completed/in_progress).
- **Mobile Agent v3**:
    - **Format**: `plan` string (numbered list) + `completed_plan` (history string).
    - **Update**: **Manager** outputs updated plan or "Finished".
- **MiniTap**:
    - **Format**: `subgoal_plan` (List of `Subgoal` objects: `{id, description, status}`).
    - **Update**: **Planner** generates initial list. **Orchestrator** marks them as complete/failed based on Cortex feedback.

## 4. State management and contexts shared across agents.
How agents share perception and execution state.

- **DroidRun**:
    - **Mechanism**: `DroidAgentState` object.
    - **Shared**: Instruction, step number, `formatted_device_state` (a11y tree), screenshot, plan, action history, memory.
    - **Isolation**: Minimal; Manager and Executor share most state via the object.
- **AutoDev**:
    - **Mechanism**: Tool call parameters and returns.
    - **Shared**: Planner passes `goal`/`instruction` to Executor. Executor returns specific report/summary. `scratchpad` and `todos` are persistent.
    - **Isolation**: Stronger separation. Planner doesn't see coordinates; Executor doesn't see full plan history (received as "query").
- **Mobile Agent v3**:
    - **Mechanism**: `InfoPool` dataclass.
    - **Shared**: Everything (instruction, history, plan, notes, error logs, screenshots).
    - **Isolation**: Agents access specific fields relevant to them (e.g., Manager sees plan, Executor sees current subgoal).
- **MiniTap**:
    - **Mechanism**: `State` (LangGraph state).
    - **Shared**: `ui_hierarchy`, `screenshot`, `subgoal_plan`, `scratchpad`, `agents_thoughts`.
    - **Isolation**: Different agents process different slices. Contextor handles raw perception; Cortex handles logic; Executor handles tool formatting.

## 5. Planner agents' output goal granularity and formats differ.
The "interface" between the Brain (Planner) and the Hands (Executor).

- **DroidRun**:
    - **Planner Output**: Natural language steps (e.g., "1. Open Settings") + `<script>` for non-UI tasks.
    - **Granularity**: High-level semantic actions. Grounding is done by Executor ("Click 'Network'").
- **AutoDev**:
    - **Planner Output**: High-level semantic tool calls: `tap(element_intent)`, `scan_for_element(intent)`, `type_text(text, intent)`.
    - **Granularity**: Intent-based. "Tap the send button" (Planner) -> `click(500, 1000)` (Executor).
- **Mobile Agent v3**:
    - **Planner Output**: Free-text plan steps.
    - **Granularity**: Broad steps. Executor picks the atomic action (`click`, `swipe`) to fulfill the immediate text instruction.
- **MiniTap**:
    - **Planner Output**: List of strings (descriptions).
    - **Granularity**: "Subgoals" (milestones). Cortex (Brain) breaks a subgoal into specific decisions; Executor converts decision to `tap`, `swipe`, etc.

## 6. Subgoal updates and replanning triggering mechanisms vary.
How the agents adapt to failure or changing conditions.

- **DroidRun**:
    - **Trigger**: Every Manager step (since it runs in a loop).
    - **Replanning**: Explicit `<potentially_stuck>` tag injected into Manager prompt if `error_history` suggests failure.
- **AutoDev**:
    - **Trigger**: **Planner** loop.
    - **Replanning**: If Executor fails (reports failure summary) or `MAX_EXECUTOR_STEPS` reached, Planner sees the report and adjusts the TODO list / params.
- **Mobile Agent v3**:
    - **Trigger**: **Manager** usually runs every step.
    - **Optimization**: Skips Manager if last action was `invalid` (quick retry).
    - **Replanning**: Explicitly requested via `error_flag_plan` if **Executor** fails `err_to_manager_thresh` (2) times consecutively.
- **MiniTap**:
    - **Trigger**: `convergence_gate`.
    - **Replanning**:
        - If any subgoal fails (`SubgoalStatus.FAILURE`), flow routes back to **Planner** with `previous_plan` and failure context.
        - **Orchestrator** can also verify completeness and trigger replan if goal isn't met.

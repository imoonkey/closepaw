# Android Agent Improvement Recommendations (Codex)

Based on strengths observed in Minitap (mobile-use), AutoDevice (AutoDev), and **DroidRun** (repo in `.reference/mobile_agent/droidrun`) plus the current implementation in this repo.

## Current Implementation Snapshot (from code)
- Single ReAct agent with **one tool call per turn**; completion only after observed success (`Agent.kt`, `Turn.kt`).
- Tool set: `mobile_action` (click/long_press/type/swipe/system_button/wait), `app_control` (list/open apps), `complete_task`.
- Targeting is **element_index-only** for click/long_press/type; swipe uses coordinates.
- ToolRouter provides policy gating + approval flow (already a good safety foundation).
- Conversation history exists; **no scratchpad/memory tool** and **no planner/executor split** yet.

## Verified Strengths From DroidRun (New Data)
- **Multi-agent modes**: reasoning mode (Manager → Executor → Scripter) + direct mode (CodeAct). (`docs/concepts/architecture.mdx`)
- **Shared state** for plan/subgoal, action history, error flags, memory, and current/previous device state. (`docs/concepts/shared-state.mdx`, `agent/droid/state.py`)
- **Prompt templating (Jinja2)** with device date, app cards, error history, memory, and structured output schema injection. (`docs/concepts/prompts.mdx`, `config/prompts/*`)
- **App cards** for app-specific guidance (local/server/composite providers). (`agent/manager/manager_agent.py`, `config/app_cards/*`)
- **Differential context**: previous device state and last action summary injected into prompts. (`manager_agent.py`)
- **Off-device scripter** for API/data tasks, plus explicit text-manipulation flow. (`config/prompts/manager/system.jinja2`)

---

## Priority 0: High Impact, Low-to-Medium Effort

### 0.1 Add Scratchpad Memory Tool (cross-app tasks)
**Why**: AutoDev + DroidRun both use memory; DroidRun injects memory into prompts and prefers memory over clipboard.
**Change**: Add `memory_tool` with `save/read/list` and prompt rules: “store actual content with step context; use memory before clipboard.”

### 0.2 Add Multi-Selector Targeting + Fallback Order
**Why**: Minitap relies on fallback targeting when indices shift.
**Change**: Extend `mobile_action` to accept `resource_id`, `bounds`, `text`, and `text_index`.
**Execution order** (suggested): `element_index -> resource_id -> bounds -> text`.

### 0.3 Add On-Demand Screen Transcription Tool
**Why**: AutoDev transcribes only when needed; reduces token load and improves list extraction accuracy.
**Change**: Add `transcribe_screen` tool returning visible text + element mapping.

### 0.4 Add Loop / Stuck Detection
**Why**: AutoDev tracks seen items/scroll history; DroidRun tracks action history + errors.
**Change**: Add screen hash + scroll counter; if repeated N times, force a strategy change or replan.

---

## Priority 1: Medium Effort, High Impact

### 1.1 Todo/Subgoal Tracking + Verification Gate
**Why**: Minitap and AutoDev both rely on explicit subgoals; DroidRun Manager tracks plan/subgoal in shared state.
**Change**: Add `todo_list` tool and require verification subgoal for formatting-sensitive outputs.

### 1.2 Failure Narrative Injection
**Why**: AutoDev uses failure summaries to pivot; DroidRun stores error history and injects it into prompts.
**Change**: Capture structured failure summaries after tool errors and inject into next LLM turn; forbid repeating the same failed action verbatim.

### 1.3 App Lock / Contextor Policy
**Why**: Minitap prevents accidental app drift; DroidRun tracks current package and visited apps.
**Change**: Add a lightweight policy layer that checks current package vs target app and relaunches if deviated (except OAuth/permissions).

### 1.4 App Cards for Top Apps
**Why**: DroidRun uses app cards to encode app-specific affordances.
**Change**: Create short guidance cards for Gmail/Maps/Settings and inject into the system prompt based on current package.

---

## Priority 2: Architecture Upgrades (High Effort, Highest Impact)

### 2.1 Planner / Executor Split + Orchestrator
**Why**: Minitap + DroidRun both separate planning and execution; AutoDev uses planner LLM with strong policies.
**Change**: Introduce Manager (plan), Executor (act), Orchestrator (progress + replanning) with shared state.

### 2.2 Mode Switching (Reasoning vs Direct)
**Why**: DroidRun uses direct mode for simple tasks to reduce overhead.
**Change**: Add a “direct” pathway for single-step tasks and a “reasoning” pathway for multi-step tasks.

### 2.3 Differential Context Injection
**Why**: DroidRun injects previous device state + last action summary to aid transition reasoning.
**Change**: Pass `(previous_state, last_action_summary)` in prompt context.

### 2.4 Off-Device Scripter
**Why**: DroidRun offloads API/data work to Python for speed and correctness.
**Change**: Add a scripter tool for data extraction and calculations, and let the planner delegate non-UI tasks.

### 2.5 Structured Output Schema Support
**Why**: DroidRun supports schema injection for precise answer formatting.
**Change**: Provide optional schema or format hints to the planner and enforce output formatting on completion.

---

## Keep / Build On Existing Strengths
- One-tool-per-turn + completion-after-evidence already match top-agent safety patterns.
- ToolRouter’s policy gating is a strong base for app-lock and approval flows.
- App alias mapping in `app_control` can seed app-card bootstrapping.

## Suggested Next Steps (Concrete)
1. Implement `memory_tool` + multi-selector targeting in `mobile_action`.
2. Add `transcribe_screen` + loop detection (screen hash + scroll counter).
3. Introduce app cards (start with Gmail/Maps/Settings) and inject into prompts.
4. Pilot a small Planner/Executor split for 1-2 workflows (cross-app copy/paste).

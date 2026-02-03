# Note 2: Memory and Note-Taking (Codex)

> How agents store and reuse task-relevant information across steps and apps.

## Sources (local)
- doc/todo/reference/droidrun_prompts.md
- doc/todo/reference/autodevice_android_world.md
- doc/todo/reference/mobile_agent_v3_architecture.md
- doc/todo/reference/minitap-mobile-use.md
- .reference/mobile_agent/droidrun/droidrun/agent/droid/state.py
- .reference/mobile_agent/droidrun/droidrun/agent/manager/manager_agent.py
- .reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/scratchpad.py
- .reference/mobile_agent/MobileAgent/Mobile-Agent-v3/android_world_v3/android_world/agents/mobile_agent_v3_agent.py
- .reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/agents/planner/planner.md

---

## Overview
All systems implement memory, but the **format and ownership** differ:
- **DroidRun**: append-only memory string in shared state.
- **AutoDev**: persistent scratchpad (key-value) shared by planner/executor.
- **Mobile Agent v3**: InfoPool fields + optional Notetaker agent.
- **MiniTap**: scratchpad tools + agent_thoughts log + summarizer pruning.

---

## DroidRun
- **Format**: single `memory` string in `DroidAgentState`.
- **Write path**:
  - Manager can emit `<add_memory>`; parser appends to memory.
  - Executor can call `remember(information)` tool.
- **Read path**: Manager injects `<memory>` into the latest user message each turn.
- **When notes happen**: On demand; prompt encourages storing key facts with step context.

## AutoDev
- **Format**: scratchpad key-value store (`createItem`, `fetchItem`).
- **Write path**: planner and executor can store structured data (JSON strings).
- **Read path**: planner or executor can fetch by key; persists across executor sessions.
- **When notes happen**: Required for multi-item and cross-app workflows because executor sessions are stateless.

## Mobile Agent v3
- **Format**: `InfoPool` fields, especially `important_notes`.
- **Write path**: Notetaker agent (optional) runs after successful actions and records only significant info.
- **Read path**: Manager sees `important_notes` during replanning; Executor sees recent summaries and outcomes.
- **When notes happen**: After success (outcome A) and only if Notetaker is enabled.

## MiniTap
- **Format**:
  - Scratchpad via `save_note` / `read_note` / `list_notes`.
  - `agents_thoughts` log for reasoning history.
- **Write path**: Cortex/Executor call note tools explicitly; all agent thoughts appended automatically.
- **Read path**: Planner and Cortex use thoughts and scratchpad during replanning and cross-app transfer.
- **When notes happen**: On demand for data transfer; Summarizer trims history to avoid context overflow.

---

## Comparison Highlights

| Aspect | DroidRun | AutoDev | Mobile Agent v3 | MiniTap |
|--------|----------|---------|-----------------|---------|
| Memory format | Freeform string | KV scratchpad | InfoPool fields | KV + thoughts log |
| Primary writer | Manager/Executor | Planner/Executor | Notetaker | Cortex/Executor |
| Persistence | Session | Session | Session | Session |
| Cross-app transfer | Manual via memory | Strong via scratchpad | Via notes | Strong via scratchpad |
| Note trigger | On demand | On demand | After success (optional) | On demand |

---

## Practical Takeaways
- Scratchpad-style memory is the most reliable for cross-app data transfer.
- Dedicated note-taking agents help reduce noise, but only work if explicitly enabled (Mobile Agent v3).
- Append-only memory (DroidRun) is simple, but less structured for complex tasks.

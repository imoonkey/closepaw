# Final Design: Multi-Agent Infrastructure (KISS)

> **Author**: Claude (updated with Codex comparison)
> **Date**: 2026-02-03
> **Principle**: Minimal code, maximum flexibility

---

## Summary

Based on reconciliation discussions, this design follows these decisions:

| Decision | Choice | Rationale |
|----------|--------|-----------|
| **Architecture** | Use `design.md` infra, Planner = main agent | No new loop types |
| **State** | Isolated, structured context passing | Cleaner, no sync bugs |
| **Memory** | Tools (`write_todos`, `scratchpad`) | Stateful tools, no shared state |
| **History** | Text-only, latest screen per turn | Core mobile-use insight |
| **Delegation** | Natural language via `delegate_task` | No semantic wrappers |

**Claude-Codex Agreement**: Both designs converge on keeping the existing ReAct loop, using tools for planning state, and isolating sub-agents. No new loop classes or ExecutionMode enum.

---

## Phased Implementation (Updated per Codex)

### Phase 0: Foundation Tools (Very Low Risk)
1. `write_todos` tool — Subgoal/task tracking
2. `scratchpad` tool — Key-value memory
3. Update main agent prompt to use these tools

### Phase 1: Context Hygiene (Low Risk) ← *Codex rightly puts this before multi-agent*
4. Stop storing screenshots/a11y trees in chat history
5. Inject only latest screen state into each LLM prompt
6. Add `screen_summary` string to history instead of raw data

### Phase 2: Sub-Agent Infra (Medium Risk)
7. `AgentDefinition` + `AgentRegistry`
8. `SubAgentRunner` with isolation
9. `DelegateTaskTool` + event bridging

### Phase 3: Executor Agent (Medium Risk)
10. Register `ExecutorAgent` with grounded tools
11. Parent prompt: "delegate_task for screen grounding"
12. Test end-to-end flow

---

## Context Passing (Parent → Executor)

When delegating, pass only:
- `query` — Self-contained instruction (required)
- `goal` — Overall task context (optional)
- `current_subgoal` — What we're trying to achieve (optional)
- `important_notes` — Short list of key facts (optional)
- `latest_screen` — Current screen state (injected by runner)

Do NOT pass:
- ❌ Full history
- ❌ Prior screenshots
- ❌ Prior a11y trees

---

## What's NOT Included (KISS)

- ❌ No new loop classes (`PlannerLoop`)
- ❌ No `ExecutionMode` enum
- ❌ No shared mutable `AgentState`
- ❌ No semantic tool wrappers (`TapIntent`, `ScrollIntent`)
- ❌ No subgoal-specific events (unless UI demands)
- ❌ No nested delegation
- ❌ No parallel sub-agents

---

## References

### Implementation Docs (Source of Truth)
- [Phase 0 Design: Tools](./phase0_tools_claude.md)
- [Phase 1 Design: Context Hygiene](./history_compression_claude.md)  
- [Phase 2-3 Design: Sub-Agent Infra](./phase1_subagent_claude.md)
- [Codex Overview](./final_design_overview_codex.md)

### Original Research Docs (Reference Only)

> [!WARNING]
> These docs were inputs to our reconciliation process. They do **NOT** 100% align with the final design above and should **NOT** be treated as source of truth. Refer to them only for deeper context on the original patterns.

- [design.md](../multiagent_infra/design.md) — Original sub-agent-as-tool infrastructure proposal
- [two_level_multiagent_design.md](../reference/two_level_multiagent_design.md) — Planner-Executor pattern from mobile-use research (AutoDev, MiniTap, Mobile Agent v3)

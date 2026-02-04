# Final Design: Multi-Agent Infrastructure (KISS)

> **Author**: Claude (updated with Codex comparison)
> **Date**: 2026-02-04 (updated)
> **Principle**: Minimal code, maximum flexibility

---

## Summary

Based on reconciliation discussions, this design follows these decisions:

| Decision | Choice | Rationale |
|----------|--------|-----------|
| **Architecture** | Use `design.md` infra, Planner = main agent | No new loop types |
| **State** | Isolated child runtime + shared scratchpad (intentional) | Keep child history/tool isolation while allowing planner-executor data handoff |
| **Memory** | Tools (`write_todos`, `scratchpad`) | Stateful planning memory with explicit cross-agent channel |
| **History** | Text-only, latest screen per turn | Core mobile-use insight |
| **Delegation** | Natural language via `delegate_task` | No semantic wrappers |

**Claude-Codex Agreement**: Both designs converge on keeping the existing ReAct loop, using tools for planning state, and isolating sub-agents. No new loop classes or ExecutionMode enum.

---

## Phased Implementation (Updated per Codex)

### Phase 0: Foundation Tools (Very Low Risk)
1. ✅ `write_todos` tool — Subgoal/task tracking
2. ✅ `scratchpad` tool — Key-value memory
3. ✅ Update main agent prompt to use these tools
4. ✅ Hardened: thread-safe state, size limits, extra tests, icon update

### Phase 1: Context Hygiene (Low Risk) ← *Codex rightly puts this before multi-agent*
4. ✅ Stop storing screenshots/a11y trees in chat history
5. ✅ Inject only latest screen state into each LLM prompt
6. ✅ Add `screen_summary` string to history instead of raw data

### Phase 2: Sub-Agent Infra (Medium Risk)
7. ✅ `AgentDefinition` + `AgentRegistry`
8. ✅ `SubAgentRunner` with isolation
9. ✅ `DelegateTaskTool` + event bridging

### Phase 3: Executor Agent (Medium Risk)
10. ✅ Register `ExecutorAgent` with grounded tools
11. ✅ Parent prompt: "delegate_task for screen grounding"
12. ✅ Delegation flow tests (unit-level) and review fixes merged

---

## Implementation Status (as of 2026-02-04)

**Completed**
- Phase 0 tools + state + prompt injection + UI events.
- Added safety hardening: thread-safe state access, scratchpad size limits, `TodoState.clear()`.
- Expanded tests for edge cases and prompt formatting.
- Updated WriteTodos icon to distinct list icon.
- Phase 1 context hygiene shipped: text-only history observations + latest screen grounding per turn.
- Phase 2/3 shipped: planner-executor delegation infrastructure, executor registration, tool filtering, event bridging.
- Clarified design choice: scratchpad is intentionally shared between planner and executor for data passing.

**Pending**
- Optional follow-up improvements (prompt compaction, broader device-level E2E scenarios).

---

## Context Passing (Parent → Executor)

When delegating, pass only:
- `query` — Self-contained instruction (required)
- `current_subgoal` — What we're trying to achieve (optional)
- `important_notes` — Short list of key facts (optional)

Do NOT pass:
- ❌ Full history
- ❌ Prior screenshots
- ❌ Prior a11y trees

Notes:
- `latest_screen` is not passed explicitly in delegation payload; executor reads current screen in its own turn loop.
- `query + current_subgoal` is sufficient task context for delegated execution.
- `scratchpad` is intentionally shared between planner and executor for structured data handoff.

---

## What's NOT Included (KISS)

- ❌ No new loop classes (`PlannerLoop`)
- ❌ No `ExecutionMode` enum
- ❌ No broad shared mutable `AgentState` (only `scratchpad` is intentionally shared)
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
- [Codex Overview](./codex_final_design/final_design_overview_codex.md)

### Original Research Docs (Reference Only)

> [!WARNING]
> These docs were inputs to our reconciliation process. They do **NOT** 100% align with the final design above and should **NOT** be treated as source of truth. Refer to them only for deeper context on the original patterns.

- [design.md](../multiagent_infra/design.md) — Original sub-agent-as-tool infrastructure proposal
- [two_level_multiagent_design.md](../reference/two_level_multiagent_design.md) — Planner-Executor pattern from mobile-use research (AutoDev, MiniTap, Mobile Agent v3)

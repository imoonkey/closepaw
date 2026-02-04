# Multi-Agent Design Reconciliation (Codex)

> Date: 2026-02-04
> Sources:
> 1) doc/todo/reference/two_level_multiagent_design.md (Planner-Executor pattern)
> 2) doc/todo/multiagent_infra/design.md (Sub-agent infrastructure)

## 1. Where They Do Not Align

1) Core architecture stance
- two_level: Hard split into Planner + Executor roles with a dedicated Planner loop.
- design.md: Generic sub-agent-as-tool model; parent agent stays ReAct and delegates to specialized agents.
- Result: One proposes a new loop; the other prefers reuse of the existing loop.

2) State model
- two_level: Central shared mutable AgentState (subgoals, scratchpad, screen snapshots, errors) read/written by both roles.
- design.md: Isolation-by-default; parent passes inputs, child returns outputs; child has its own history manager.
- Result: Shared mutable state conflicts with isolated services and clean delegation boundaries.

3) Tool semantics
- two_level: Semantic intent tools for Planner (tapIntent, scrollIntent, typeIntent) that implicitly spawn an Executor.
- design.md: DelegateTaskTool + standard tool allowlists; no semantic wrappers.
- Result: Two different layers for expressing actions.

4) Event and protocol shape
- two_level: New subgoal and executor session events (SubgoalsUpdated, SubgoalStarted, ExecutorSessionStarted, etc.).
- design.md: SubAgentStarted/Activity/Completed + approval bridging to parent.
- Result: Event models are adjacent but not unified, risking duplicated or conflicting UI streams.

5) Verification and error recovery
- two_level: Before/after verification and ActionReflector; explicit replanning thresholds.
- design.md: Guardrails via turn limits, timeouts, and approval routing; no explicit verifier role.
- Result: Failure handling lives at different layers.

6) Memory and message history
- two_level: Planner sees summaries, Executor is stateless between calls.
- design.md: Sub-agent has its own history manager, but not necessarily stateless across delegated calls.
- Result: Executor statelessness is not guaranteed in the infra design.

7) File/module structure
- two_level: New modules (agent/planner, agent/executor, agent/state) and a custom PlannerLoop.
- design.md: AgentDefinition/Registry/Runner/DelegateTaskTool; minimal new agent modules.
- Result: Scope and file placement differ significantly.

---

## 2. Reconciliation Proposal

### A) Treat design.md as the platform, two_level as the pattern
Adopt the sub-agent infrastructure (Registry + SubAgentRunner + DelegateTaskTool + approval bridging) as the stable base, then implement Planner-Executor as a first-class pattern on top of it.

Why: This preserves the existing Agent loop and reduces re-architecture risk while still enabling the Planner-Executor behavior.

### B) Model Planner and Executor as AgentDefinitions
- PlannerAgent: The default parent agent. Tools = Subgoal tools + DelegateTaskTool (targeting ExecutorAgent) + direct tools (open app, go back).
- ExecutorAgent: A registered sub-agent with low-level tools only; maxTurns and timeout are short (e.g., 10 turns, 30-60s).
- Result: Planner-Executor becomes a configuration of the existing infra rather than a separate runtime.

### C) Replace shared mutable AgentState with structured context passing
- Keep long-term state (subgoals, scratchpad, error counters) in the Planner only.
- Pass only the minimum required context to the Executor via inputs (goal, subgoal, key notes, latest screen snapshot).
- Executor returns a structured report; Planner merges it into its own state.
- Optional: Keep AgentState as a Planner-owned model, not a shared object.

This keeps two_level's logical state while honoring design.md isolation.

### D) Make Executor stateless by construction
- Configure SubAgentRunner to always create a new history manager per delegation.
- Ensure ExecutorAgent definitions do not reuse history across calls.
- This aligns with two_level's "fresh session per instruction" without new runtime primitives.

### E) Unify tool semantics with a thin wrapper
Option 1 (minimal): Map two_level semantic intent tools to DelegateTaskTool inputs:
- tapIntent -> delegate_task(agent_name="executor", query="tap the login button")
- scrollIntent -> delegate_task(..., query="scroll down to find signup link")

Option 2 (cleaner): Define Planner-only tools (TapIntent/ScrollIntent/TypeIntent) that are pure wrappers around DelegateTaskTool.

### F) Merge event models
- Keep SubAgentStarted/Activity/Completed as the canonical child-agent stream.
- Add two_level subgoal events (SubgoalsUpdated/SubgoalStarted/SubgoalCompleted) to the parent stream only.
- Map Executor session events to SubAgentStarted/Completed to avoid UI duplication.

### G) Add verification as an optional VerifierAgent or post-step hook
- Implement ActionReflector as a dedicated VerifierAgent (tool allowlist: read-only perception).
- Planner decides when to call it (critical subgoals or repeated failures).
- Alternatively, Executor can be configured to always return pre/post screenshots and a confidence score.

### H) Consolidate failure handling
- Use design.md guardrails (timeouts/turn limits) as the baseline.
- Add two_level replanning triggers in Planner state (consecutive failures, repeated executor reports).
- Emit PlannerReplanning events when thresholds are reached.

---

## 3. Concrete Integration Plan (Minimal Change Path)

1) Implement platform pieces from design.md (Registry, Runner, DelegateTaskTool, bridging).
2) Add Planner-only subgoal types + events from two_level; store them in Planner state.
3) Register ExecutorAgent and route Planner semantic actions through DelegateTaskTool.
4) Enforce Executor statelessness (new history manager per run).
5) Add optional VerifierAgent (can be a later phase).

---

## 4. Resulting Combined Architecture (Summary)

- The system remains a single ReAct parent agent loop.
- Planner behavior is achieved via prompt + subgoal tools + structured state.
- Executor is a short-lived sub-agent with restricted tools.
- State is logically shared but physically isolated, preventing coupling bugs.
- Events remain coherent: parent sees subgoals + child activity without duplication.

This delivers the Planner-Executor benefits without rewriting the core runtime.

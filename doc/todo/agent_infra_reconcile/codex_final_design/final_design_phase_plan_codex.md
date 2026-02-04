# Final Design (KISS) - Minimal Phase Plan

Date: 2026-02-04
Principle: Write the smallest code that keeps options open.

## Phase 0 - Planning Tools First (Very Low Risk)

1) Add TODO tool with in-session storage.
2) Add Scratchpad tool with in-session storage.
3) Update main agent prompt to use these tools for planning and memory.

Why first:
- Planning state is needed regardless of multi-agent infra.
- Reduces prompt size and history noise immediately.

## Phase 1 - Context Hygiene (Low Risk)

1) Stop storing screenshots/a11y trees in chat history.
2) Inject only the latest screen state into each LLM prompt.
3) Add a short screen_summary string to history instead of raw trees.

Why now:
- Solves the most painful mobile-agent context issue with minimal code.

## Phase 2 - Minimal Multi-Agent Infra (Medium Risk)

1) Implement AgentDefinition + AgentRegistry (from design.md).
2) Implement SubAgentRunner with isolated services + fresh HistoryManager.
3) Implement DelegateTaskTool and event/approval bridging.

Keep it minimal:
- No new loop classes.
- No ExecutionMode enum.
- No new event families beyond SubAgentStarted/Activity/Completed.

## Phase 3 - Executor Agent (Medium Risk)

1) Register ExecutorAgent in the registry.
2) Parent prompt: "delegate_task for any screen grounding."
3) Executor tools: mobile_action + app_control only.
4) Set tight maxTurns and timeout.

Optional later:
- Verifier agent
- Subgoal-specific events
- Additional specialized sub-agents


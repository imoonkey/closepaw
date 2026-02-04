# Final Design (KISS) - Overview

Date: 2026-02-04
Scope: Minimal multi-agent infra + Planner-Executor behavior without new loops

## 1) Core Decisions (Keep It Simple)

- Keep the existing ReAct loop as the only runtime loop.
- Do not introduce a new "PlannerLoop" or an ExecutionMode enum.
- "Planner" is just the current main agent with a prompt that delegates.
- "Executor" is a registered sub-agent, spawned per delegation.
- No shared mutable AgentState across agents; use isolation + context passing.
- No semantic tool wrappers (tapIntent, scrollIntent). Use delegate_task with natural-language query.

## 2) Minimal Architecture

Parent Agent (existing ReAct)
- Has DelegateTaskTool.
- Has planning tools (todo/scratchpad).
- Delegates any UI grounding to Executor via delegate_task(query = "...").

Executor Agent (registered sub-agent)
- Runs in SubAgentRunner (fresh HistoryManager per call).
- Tools: mobile_action, app_control (or the minimal subset for UI control).
- Short maxTurns and timeout to avoid loops.

Infrastructure (from design.md, minimal subset)
- AgentDefinition
- AgentRegistry
- SubAgentRunner
- DelegateTaskTool
- Event bridge + approval bridge (only what's required for parent UI).

## 3) Prompt Contract

Parent prompt adds a single rule:
- "Use delegate_task for any action that requires grounding on the screen."

Executor prompt is short:
- "You are an executor. You receive a self-contained instruction. Use tools to complete it. Return a concise result."

## 4) Event Model (Minimal)

- Keep SubAgentStarted / SubAgentActivity / SubAgentCompleted.
- Do NOT add new subgoal events unless UI needs them.
- Use tool outputs (todo/scratchpad) to show planning progress in text.

## 5) Why This Is KISS

- No new loop classes or state machines.
- No shared mutable state objects or new global stores.
- No new protocol event families unless demanded by UI.
- Same infra works for both Executor and future specialized agents.


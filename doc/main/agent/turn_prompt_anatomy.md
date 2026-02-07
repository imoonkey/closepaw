# Turn Prompt Anatomy

> What each turn sends to the LLM: system prompt, input items, and tool schemas.
> Last updated: 2026-02-06 (commit: 847080b221f528073d640535b587509d202804c2)

## Overview

Each turn request is assembled from three parts:

1. `instructions` (system prompt)
2. `input` (history + current user context)
3. `tools` (function schemas filtered by active agent definition)

Primary wiring:
- `agent/AgentTurnRunner.kt`
- `agent/Turn.kt`
- `agent/cognition/prompt/PromptUtils.kt`

---

## 1. System Prompt Source

`PromptUtils.buildSystemPrompt(basePrompt)` requires `basePrompt` and does not fall back to templates.

System prompt now comes from `AgentDef` selected by runtime mode:

- Main agent (`SessionAgentRunner`):
  - `AgentMode.BASIC` -> `StandaloneAgentDef.systemPrompt`
  - `AgentMode.PRO` -> `PlannerAgentDef.systemPrompt`
- Sub-agent executor (`IsolatedSubAgentRunner`):
  - `ExecutorAgentDef.systemPrompt`

→ See: `agent/definition/AgentDefRegistry.kt`

---

## 2. Input Items Composition

`Turn.buildInputItems()` constructs request input in order:

1. History messages (`ResponseItem.Message`)
2. Historical function calls (`ResponseItem.FunctionCall`)
3. Historical function outputs (`ResponseItem.FunctionCallOutput`)
4. Current turn user-context message from `PromptUtils.buildUserMessage(...)`

The current user-context message contains:
- current screen state JSON (`Perceptor.toPromptJson(...)`)
- available tools summary
- next-action instruction
- optional dynamic reminders (loop warnings, turn-budget warning, todos, scratchpad)

If screenshot input is enabled, the final user item includes both text and image content.

→ See: `agent/Turn.kt`

---

## 3. Tool Schema Set

Tools are generated from `ToolRegistry` and filtered by `allowedToolNames` from `AgentExecutionConfig`.

Examples:

- Standalone (`BASIC`):
  - `mobile_action`, `system_button`, `wait`, `open_app`, `write_todos`, `scratchpad`, `complete_task`
- Planner (`PRO` main agent):
  - `open_app`, `write_todos`, `scratchpad`, `delegate_task`, `complete_task`
- Executor (delegated child):
  - `mobile_action`, `system_button`, `wait`, `open_app`, `scratchpad`, `complete_task`

`delegate_task` is registered only when main agent mode requires it.

→ See: `session/SessionAgentRunner.kt`

---

## 4. Request Skeleton

Conceptual shape of a turn request:

```json
{
  "model": "gpt-5.2",
  "instructions": "...agent-def system prompt...",
  "input": [
    {"role": "user", "content": "Goal: ..."},
    {"type": "function_call", "name": "...", "arguments": "..."},
    {"type": "function_call_output", "output": "..."},
    {"role": "user", "content": "Current screen state ..."}
  ],
  "tools": [
    {"type": "function", "name": "mobile_action", "parameters": {...}},
    {"type": "function", "name": "complete_task", "parameters": {...}}
  ]
}
```

---

## 5. Turn Completion Semantics

`Turn.processResponse(...)` marks a turn complete when either:

- a `complete_task` tool call appears, or
- the response has assistant text and no tool calls

Tool calls not in the current allowlist are dropped before completion analysis.

---

## 6. Trace Artifacts

When trace is enabled, each LLM request/response writes artifacts such as:

- `llm_system_prompt/turn_{n}_system.txt`
- `llm_user_context/turn_{n}_user_context.txt`
- `llm_full_prompt/turn_{n}_full_prompt.txt`
- `llm_input_items/turn_{n}_llm_input_items.json`
- `llm_history/turn_{n}_history.json`
- `llm_tool_calls/turn_{n}_tool_calls.json`

→ See: `trace/AgentTrace.kt`

---

## Related Docs

- [Loop Execution](loop.md) - Turn orchestration and stop conditions
- [Multi-Agent](multiagent.md) - Planner/executor delegation model
- [Session](../infra/session.md) - Runtime mode selection and wiring

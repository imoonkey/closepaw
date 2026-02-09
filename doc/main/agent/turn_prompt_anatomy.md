# Turn Prompt Anatomy

> What each turn sends to the LLM: system prompt, input items, and tool schemas.
> Last updated: 2026-02-08 (commit: 0d3864e3fa9d30dfa8db6b66b8ec89901e6f5ebd)

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

## 1. System Prompt (Instructions)

The system prompt defines the agent's role and behavioral guidelines. It comes from `AgentDef` selected by runtime mode:

- Main agent (`SessionAgentRunner`):
  - `AgentMode.BASIC` → `StandaloneAgentDef.systemPrompt`
  - `AgentMode.PRO` → `PlannerAgentDef.systemPrompt`
- Sub-agent executor (`IsolatedSubAgentRunner`):
  - `ExecutorAgentDef.systemPrompt`

### Standalone Agent System Prompt Structure

From actual runs (standalone mode), the system prompt contains:

```
You are a standalone Android automation agent.

## Your Job
Complete the user's goal end-to-end by directly interacting with the Android UI.
You are not a planner-only role and should execute grounded actions yourself.

## Tool Calling
- Use function calling tools only; do NOT emit raw JSON or <action> tags.
- Execute ONE UI action per turn when possible, then observe.
- Use `write_todos` for multi-step goals to keep progress explicit.
- Use `scratchpad` to store extracted facts and avoid repeated extraction.
- Scratchpad context shows keys only; use `scratchpad(action="read", key="...")` when value is needed.

## Core Loop
1. Observe current screen state (JSON element list)
2. Pick the best next action
3. Execute one tool action
4. Verify progress and continue
5. Complete the task promptly when done

## Execution Quality
- Be precise and evidence-driven from the current accessibility JSON.
- Prefer semantic selectors (`element_index`, `text`) over coordinate taps.
- Use coordinate taps only as a last resort, and never probe blank/unlabeled areas.
- Avoid repeated identical actions when no state change occurs.
- If an action fails, switch strategy instead of brute-force retries.
- Use `system_button(button="enter")` only when a text field is focused after typing.
- Keep answers concise and factual in complete_task.
```

→ See: `agent/definition/AgentDefRegistry.kt`

---

## 2. Input Items Composition

`Turn.buildInputItems()` constructs request input in order:

1. **Goal message** (first turn only): `{"role": "user", "content": "Goal: ..."}`
2. **Historical function calls**: `{"type": "function_call", "id": "...", "name": "...", "arguments_json": "..."}`
3. **Historical function outputs**: `{"type": "function_call_output", "call_id": "...", "success": true, "content": "..."}`
4. **Current turn user-context message**: Screen state + dynamic context

### User Context Message Structure

The current user-context message (built by `PromptUtils.buildUserMessage(...)`) contains:

```
Current screen state (N elements):
```json
[
  {
    "index": 0,
    "text": "...",
    "class": "View",
    "clickable": true,
    "focused": false,
    "long_clickable": false,
    "bounds": [x1, y1, x2, y2],
    "center": [cx, cy]
  },
  ...
]
```

Available tools: complete_task, mobile_action, open_app, scratchpad, system_button, wait, write_todos

## Current Todos
1. [IN_PROGRESS] First task...
2. [PENDING] Second task...

## Scratchpad
- key1 (use scratchpad(action="read", key="key1") to retrieve value)
- (empty) Store important facts with scratchpad(action="write", key="...", value="...")...

What action should I take next to achieve the goal?

<system_reminder>
Todo status: N actionable item(s). In progress: ... Next: ...
</system_reminder>
```

### Dynamic Context Sections

- **Current Todos**: Shows todo items when `write_todos` has been used, with status markers (`[IN_PROGRESS]`, `[PENDING]`, `[DONE]`)
- **Scratchpad**: Shows stored keys (not values) when scratchpad has entries; hints how to read/write
- **System Reminder**: XML-tagged block with todo status summary; added when todos exist

If screenshot input is enabled, the final user item includes both text and image content.

→ See: `agent/Turn.kt`, `agent/cognition/prompt/PromptUtils.kt`

---

## 3. Tool Schema Set

Tools are generated from `ToolRegistry` and filtered by `allowedToolNames` from `AgentExecutionConfig`.

From actual runs, standalone agent uses:
- `complete_task`, `mobile_action`, `open_app`, `scratchpad`, `system_button`, `wait`, `write_todos`

Mode-specific tool sets:

| Mode | Available Tools |
|------|-----------------|
| Standalone (`BASIC`) | `mobile_action`, `system_button`, `wait`, `open_app`, `write_todos`, `scratchpad`, `complete_task` |
| Planner (`PRO` main) | `open_app`, `write_todos`, `scratchpad`, `delegate_task`, `complete_task` |
| Executor (delegated) | `mobile_action`, `system_button`, `wait`, `open_app`, `scratchpad`, `complete_task` |

→ See: `session/SessionAgentRunner.kt`

---

## 4. Request Skeleton

Conceptual shape of a turn request:

```json
{
  "model": "gpt-5.2",
  "instructions": "You are a standalone Android automation agent...",
  "input": [
    {"role": "user", "content": "Goal: I want to buy..."},
    {"type": "function_call", "id": "...", "name": "write_todos", "arguments_json": "{...}"},
    {"type": "function_call_output", "call_id": "...", "success": true, "content": "Plan updated (3 items)."},
    {"role": "user", "content": "Current screen state (16 elements):\n```json\n[...]```\n\nAvailable tools: ...\n\n## Current Todos\n..."}
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

When trace is enabled, each LLM request/response writes artifacts to `trace/artifacts/`:

| Artifact Folder | Content |
|-----------------|---------|
| `llm_system_prompt/` | `{seq}_turn_{n}_system.txt` - System prompt text |
| `llm_user_context/` | `{seq}_turn_{n}_user_context.txt` - User context message |
| `llm_full_prompt/` | `{seq}_turn_{n}_full_prompt.txt` - Combined system + user prompt |
| `llm_input_items/` | `{seq}_turn_{n}_llm_input_items.json` - Full input array as JSON |
| `llm_history/` | `{seq}_turn_{n}_history.json` - Conversation history |
| `llm_tool_calls/` | `{seq}_turn_{n}_tool_calls.json` - Tool calls from LLM response |

Additional artifacts:
- `raw_a11y_tree/` - Raw accessibility tree data
- `sanitized_a11y_tree/` - Processed accessibility tree
- `screenshot/` - Screen captures
- `tool_call_args/` - Tool call arguments
- `tool_result/` - Tool execution results
- `tool_observation_text/` - Text observations after tool execution
- `tool_observation_screen/` - Screen state after tool execution

→ See: `trace/AgentTrace.kt`

---

## Related Docs

- [Loop Execution](loop.md) - Turn orchestration and stop conditions
- [Multi-Agent](multiagent.md) - Planner/executor delegation model
- [Session](../infra/session.md) - Runtime mode selection and wiring

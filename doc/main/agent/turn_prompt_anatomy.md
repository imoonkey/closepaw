# Turn Prompt Anatomy

> What each turn sends to the LLM: instructions, input items, and filtered tools.
> Last updated: 2026-02-17 (commit: c57e349)

## Overview

Each turn request is assembled by three runtime pieces:

1. `TurnPlanningPhaseRunner` chooses warnings and system prompt
2. `PromptBuilder` constructs `input` items
3. `Turn` sends `instructions/input/tools` to the selected model and parses response

Primary wiring:
- `agent/TurnPlanningPhaseRunner.kt`
- `agent/cognition/prompt/PromptBuilder.kt`
- `agent/Turn.kt`

---

## 1. System Prompt (Instructions)

System prompt text is sourced from the active `AgentDef` and passed unchanged to `Turn`.

- Main agent (`SessionAgentRunner`):
  - `AgentMode.BASIC` → `StandaloneAgentDef.systemPrompt`
  - `AgentMode.PRO` → `PlannerAgentDef.systemPrompt`
- Sub-agent executor:
  - `ExecutorAgentDef.systemPrompt`

`AgentTurnRunner` enforces prompt presence with `requireNotNull(config.systemPrompt)`.

→ See: `agent/definition/AgentDefRegistry.kt`, `agent/AgentTurnRunner.kt`

---

## 2. Input Items Composition

`PromptBuilder.buildInputItems(snapshot, image, warnings)` constructs the full `input` list in fixed order:

1. **History section** (`HistoryManager.forPrompt()`, normalized)
2. **Memory section** (optional single user message)
3. **Current observation section** (screen JSON + optional screenshot)

### 2.1 History Section

History items are converted from `ResponseItem` to Responses API `ResponseInputItem`:

- `ResponseItem.Message` → `easy_input_message`
- `ResponseItem.FunctionCall` → `function_call`
- `ResponseItem.FunctionCallOutput` → `function_call_output`

Screen observations are tagged by `ResponseItem.Message(isScreenObservation = true)`.
To control growth, `PromptBuilder` keeps only recent full screen observations and compresses older ones to:

`Screen: {N} elements (compressed)`

Default retained full observations: `recentFullScreenTurns = 3`.

### 2.2 Memory Section (Optional)

When todos or scratchpad keys exist, a single user message is inserted:

```text
## Working Memory

### Todo List
1. [IN_PROGRESS] ...

### Scratchpad
- key_a
- key_b
```

- Omitted when both todo list and scratchpad are empty.
- Scratchpad exposes keys only (not values) in the memory section.

### 2.3 Observation Section

Final user message always includes current screen JSON:

````text
[warning] ...optional warning...
[final-turn] ...optional final-turn warning...

Screen state (N elements):
```json
...
```
````

If screenshot input is available and backend supports vision, the message also attaches image content and appends:

`Screenshot attached (compressed).`

### Warnings Included

Warnings are prepared in `AgentTurnRunner.buildWarnings(...)`:
- Loop warning from `LoopDetectionPolicy` (WARNING or CRITICAL severity)
- Final-turn warning when step policy enters `ForceStop`

→ See: `agent/cognition/policy/LoopDetectionPolicy.kt`, `agent/cognition/policy/ExecutorStepPolicy.kt`

---

## 3. Screen Observation Recording

After input items are built (so current turn does not duplicate itself), `TurnPlanningPhaseRunner` records the current screen into history as:

- `role = "user"`
- `isScreenObservation = true`
- content format: `Screen state (N elements):` + fenced JSON block

This makes the next turn history-aware while still keeping the current prompt deterministic.

→ See: `agent/TurnPlanningPhaseRunner.kt`, `history/HistoryManager.kt`

---

## 4. Tool Schema Set

`Turn` generates tool schemas from `ToolRegistry` and applies `allowedToolNames` filtering.

Mode-level allowlists are defined by agent definitions:

| Mode | Available Tools |
|------|-----------------|
| Standalone (`BASIC`) | `mobile_action`, `system_button`, `wait`, `open_app`, `write_todos`, `scratchpad`, `complete_task`, `ask_user` |
| Planner (`PRO` main) | `open_app`, `write_todos`, `scratchpad`, `delegate_task`, `complete_task` |
| Executor (delegated) | `mobile_action`, `system_button`, `wait`, `open_app`, `scratchpad`, `complete_task`, `ask_user` |

Tool calls returned by the model but outside the allowlist are dropped before completion checks.

→ See: `agent/Turn.kt`, `agent/definition/`

---

## 5. Request Skeleton

Conceptual shape of one request:

```json
{
  "model": "gpt-5.2",
  "instructions": "You are a standalone Android automation agent...",
  "input": [
    {"role": "user", "content": "Goal: ..."},
    {"type": "function_call", "id": "...", "name": "write_todos", "arguments": "{...}"},
    {"type": "function_call_output", "call_id": "...", "output": "Success: ..."},
    {"role": "user", "content": "## Working Memory\n..."},
    {"role": "user", "content": "Screen state (16 elements):\n```json\n...\n```"}
  ],
  "tools": [
    {"type": "function", "name": "mobile_action", "parameters": {...}},
    {"type": "function", "name": "complete_task", "parameters": {...}}
  ]
}
```

---

## 6. Turn Completion Semantics

`Turn.processResponse(...)` marks completion when either:

- a `complete_task` call exists, or
- there are no tool calls and assistant text is present

`TurnPlanningPhaseRunner` then applies `TurnToolPolicy` arbitration for one-tool-per-turn execution and completion deferral rules.

### Tool Call Recovery

When the LLM returns tool calls formatted as text instead of structured tool calls, `Turn` attempts recovery:
- Parses JSON objects with `name`/`tool_name` and `arguments`/`args` fields
- Matches inline `toolName {...}` patterns
- Strips markdown code fences before parsing

→ See: `agent/Turn.kt`

---

## 7. Trace Artifacts

When trace is enabled, request/response artifacts are written under `trace/artifacts/`:

| Artifact Folder | Content |
|-----------------|---------|
| `llm_system_prompt/` | `{seq}_turn_{n}_system.txt` |
| `llm_user_context/` | `{seq}_turn_{n}_user_context.txt` |
| `llm_full_prompt/` | `{seq}_turn_{n}_full_prompt.txt` |
| `llm_input_items/` | `{seq}_turn_{n}_llm_input_items.json` |
| `llm_history/` | `{seq}_turn_{n}_history.json` |
| `llm_tool_calls/` | `{seq}_turn_{n}_tool_calls.json` |

Additional artifacts include raw/sanitized a11y trees, screenshots, tool args/results, and post-action observation records.

→ See: `trace/AgentTrace.kt`

---

## Related Docs

- [Loop Execution](loop.md) - turn orchestration and stop conditions
- [Planning State](planning.md) - memory/todo/scratchpad behavior
- [Session](../infra/session.md) - runtime mode selection and wiring

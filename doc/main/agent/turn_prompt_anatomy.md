# Turn Prompt Anatomy

> What each turn sends to the LLM: instructions, input items, and filtered tools.
> Last updated: 2026-03-06 (uncommitted)

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

### Prompt Structure

The standalone and planner prompts now keep only cross-tool policy in the system prompt:

1. **Role** — Agent identity and success criteria
2. **Critical Rules** — Turn-shape, evidence, retry/pivot, and coordination policy
3. **Execution Loop** — Observe → act/delegate → verify
4. **Working Memory** — How to use todos and scratchpad
5. **Task Modes** — Manipulation, information gathering, blocked/unsupported handling
6. **Completion** — Verification doctrine before `complete_task`
7. **Device Environment** — Runtime context (device, screen, date)

Tool-local semantics now live in tool descriptions, and app-specific guidance lives in
`app/src/main/assets/app_skills/<package>/SKILL.md`.

→ See: `agent/definition/StandaloneAgentDef.kt`, `agent/definition/PlannerAgentDef.kt`

---

## 2. Input Items Composition

`PromptBuilder.buildInputItems(observation, warnings, ..., appSkill)` constructs the full
`input` list in fixed order:

1. **History section** (`HistoryManager.forPrompt()`, normalized)
2. **Working memory section** (optional — todos + scratchpad)
3. **Recalled memory section** (optional — cross-session memories from `MemoryRecaller`)
4. **App skill section** (optional single user message)
5. **Current observation section** (screen JSON + optional screenshot)

### 2.1 History Section

History items are converted from `ResponseItem` to Responses API `ResponseInputItem`:

- `ResponseItem.Message` → `easy_input_message`
- `ResponseItem.FunctionCall` → `function_call`
- `ResponseItem.FunctionCallOutput` → `function_call_output`

Screen observations are tagged by `ResponseItem.Message(kind = MessageKind.SCREEN_OBSERVATION)`.
To control growth, `HistoryManager` proactively keeps only the last `recentFullScreens` full screen observations and compresses older ones to:

`Screen: {N} elements (compressed)`

Default retained full observations: `recentFullScreens = 3`.

### 2.2 Working Memory Section (Optional)

When todos or scratchpad keys exist, a single user message is inserted:

```text
## Working Memory

### Todo List
1. [IN_PROGRESS] ...

### Scratchpad
- key_a
- key_b
```

### 2.3 Recalled Memory Section (Optional)

When `MemoryRecaller.recall(currentPackageName)` returns content, it is injected as a single user message after working memory. This provides cross-session learnings to the LLM.

```text
## Recalled Memory

These are learnings from previous sessions. Use them to avoid repeating mistakes.

### App: com.android.settings
- [2026-03-11] [workflow] Developer Options is under System > Developer Options
- [2026-03-11] [pitfall] "About phone" scroll position resets on back-navigate
```

The recaller uses an elastic budget (device 1KB + user_prefs 1.5KB + app remainder, total ≤6KB). Newest entries are kept on truncation.

→ See: [memory.md](memory.md) for full memory system details.

- Omitted when both todo list and scratchpad are empty.
- Scratchpad exposes keys only (not values) in the memory section.

### 2.3 App Skill Section (Optional)

When the foreground package matches an asset under `app_skills/<package>/SKILL.md`,
`TurnPlanningPhaseRunner` loads the whole file through `AppSkillRepository` and injects:

```text
## App Skill
Package: net.gsantner.markor

...full SKILL.md contents...
```

This keeps app knowledge out of the static system prompt while ensuring the active app's guidance
is adjacent to the current observation.

### 2.4 Observation Section

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
- Loop warning from `LoopDetectionPolicy` (factual message only—no severity levels)
- Final-turn warning when `isFinalTurn()` returns true

> See: `agent/cognition/policy/LoopDetectionPolicy.kt`, `agent/cognition/policy/TurnBudget.kt`

---

## 3. Screen Observation Recording

`TurnPlanningPhaseRunner` creates a canonical `TurnObservation` from the screen snapshot and perception config. Both prompt rendering and history recording project from this single payload — `Perceptor.toPromptJson()` is called once.

- `PromptBuilder` wraps `observation.screenBlock` with turn-specific decorations (budget, warnings, screenshot note)
- History records `observation.screenBlock` directly as `kind = MessageKind.SCREEN_OBSERVATION`

No ordering dependency: the observation is immutable, so prompt building and history recording can happen in any order.

→ See: `agent/cognition/prompt/TurnObservation.kt`, `agent/TurnPlanningPhaseRunner.kt`, `history/HistoryManager.kt`

---

## 4. Tool Schema Set

`Turn` generates tool schemas from `ToolRegistry` and applies `allowedToolNames` filtering.

Mode-level allowlists are defined by agent definitions:

| Mode | Available Tools |
|------|-----------------|
| Standalone (`BASIC`) | `mobile_action`, `system_button`, `wait`, `open_app`, `shell`, `write_todos`, `scratchpad`, `complete_task`, `ask_user` |
| Planner (`PRO` main) | `open_app`, `write_todos`, `scratchpad`, `delegate_task`, `complete_task` |
| Executor (delegated) | `mobile_action`, `system_button`, `wait`, `open_app`, `scratchpad`, `complete_task`, `ask_user` |

Tool calls returned by the model but outside the allowlist are dropped before completion checks.

→ See: `agent/Turn.kt`, `agent/definition/`

---

## 5. Request Skeleton

Conceptual shape of one request:

```json
{
  "model": "glm-5",
  "instructions": "You are a standalone Android automation agent...",
  "input": [
    {"role": "user", "content": "Goal: ..."},
    {"type": "function_call", "id": "...", "name": "write_todos", "arguments": "{...}"},
    {"type": "function_call_output", "call_id": "...", "output": "Success: ..."},
    {"role": "user", "content": "## Working Memory\n..."},
    {"role": "user", "content": "## App Skill\nPackage: net.gsantner.markor\n..."},
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

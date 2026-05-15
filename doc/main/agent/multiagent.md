# Multi-Agent System

> Subagent delegation and unified agent-mode wiring.
> Last updated: 2026-05-15

## Unified Delegation Model

ClosePaw runs one default main agent. The main agent has the full Android toolset and may optionally call `delegate_task` for an isolated subtask. There is no Basic/Pro mode switch, no separate planner prompt, and no separate executor role definition.

```
Main Agent (DefaultRoleDef, AgentExecutionRole.MAIN)
  - Uses the full UI/shell/cognitive toolset
  - Owns the persistent session history
  - May delegate one isolated subtask with delegate_task

delegate_task(query)
  -> Subagent (DefaultRoleDef prompt, AgentExecutionRole.SUBAGENT)
     - Uses a fresh ephemeral HistoryManager
     - Shares scratchpad state with the parent
     - Cannot call delegate_task or remember_experience
     - Returns a compact success/failure result
```

The parent history only records the `delegate_task` call and result. The subagent's internal LLM turns are not copied into the parent LLM context.

---

## Core Components

### AgentRoleDef + AgentDefRegistry

> See: `agent/definition/AgentRoleDef.kt`, `agent/definition/DefaultAgentDef.kt`, and `agent/definition/AgentDefRegistry.kt`

`DefaultRoleDef` is the single role definition:
- system prompt text
- allowed tool set, including `delegate_task`
- runtime role metadata for the main agent
- delegation properties (`delegatable`, `maxTurns`, `timeoutMs`, `description`)

`AgentDefRegistry.main` returns `DefaultRoleDef`. `AgentDefRegistry.delegatableRoles()` returns the same single definition because `DefaultRoleDef.delegatable = true`.

### IsolatedSubAgentRunner

> See: `agent/subagent/SubAgentRunner.kt`

Executes one delegated request with isolated runtime state:
- Creates a child `Agent` with a fresh `HistoryManager`
- Reuses the default role prompt and tool allowlist
- Filters child tools to remove `delegate_task` and `remember_experience`
- Shares parent scratchpad intentionally for data handoff
- Runs as `AgentExecutionRole.SUBAGENT`
- Returns normalized `SubAgentResult(success, message)`
- Produces a narrative summary when the turn limit is reached
- Handles timeout via `withTimeoutOrNull`

### DelegateTaskTool

> See: `tool/impl/DelegateTaskTool.kt`

Tool for delegating to the subagent:

```kotlin
delegate_task(
    query = "Tap on the 'Send' button",
    current_subgoal = "Send the email",               // optional
    important_notes = ["Recipient: john@example.com"] // optional
)
```

Delegation always routes to the single delegatable role. The delegated query should be self-contained; pass only the current subgoal and short notes needed to complete that subtask.

---

## Runtime Wiring

> See: `session/SessionAgentRunner.kt`

`SessionAgentRunner` resolves `AgentDefRegistry.main`, registers `delegate_task` when the resolved main-agent allowlist includes it, and registers `ask_user`.

When `delegate_task` runs, `DelegateTaskTool` emits `SubAgentStarted`, creates an `IsolatedSubAgentRunner`, waits for its result, emits `SubAgentCompleted`, and returns the compact result to the parent as the tool observation.

## Model Resolution

Each runtime agent can use a different model:
- Main agent: `SessionConfig.mainModel`
- Subagent: `SessionConfig.subagentModel ?: SessionConfig.mainModel`

`SessionLlmBootstrapper` validates credentials for both selected models before session start.

## Subagent Events

| Event | Description |
|-------|-------------|
| `SubAgentStarted` | Delegation begins (`agentName`, `query`) |
| `SubAgentActivity` | Bridged non-action activity from a subagent |
| `SubAgentCompleted` | Subagent finished (`agentName`, `success`, `message`) |
| `ActionProposed` / `ActionExecuted` | Subagent action events forwarded unchanged to the parent event stream |

Only action events are streamed into the parent chat row/capsule path. Thought deltas, message deltas, turn phases, and screen captures stay internal to the subagent.

---

## Adding New Subagents

The current architecture intentionally has one delegatable role. Add a new role only if there is a concrete need for a distinct prompt, tool allowlist, turn budget, or timeout; otherwise keep delegation on `DefaultRoleDef` and use runtime exclusions in `SubAgentRunner`.

---

## Related Docs

- [Overview](overview.md) - Architecture context
- [Loop](loop.md) - How delegation fits in the ReAct loop
- [Planning](planning.md) - Scratchpad for cross-agent data
- [Protocol](../protocol/overview.md) - Subagent events

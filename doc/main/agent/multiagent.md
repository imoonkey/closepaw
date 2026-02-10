# Multi-Agent System

> Sub-agent delegation, executor agents, and orchestration.
> Last updated: 2026-02-09 (commit: 917ebf7)

## Planner-Executor Pattern

In `AgentMode.PRO`, the main agent is a **planner** and delegates atomic UI actions to an **executor** sub-agent.

```
┌────────────────────────────────────────────────────────────────┐
│              Main Agent (Planner)                             │
│  - Plans multi-step tasks                                     │
│  - Manages todos and scratchpad                               │
│  - Delegates atomic actions via delegate_task                 │
└───────────────────────┬────────────────────────────────────────┘
                        │ delegate_task(query)
                        ▼
┌────────────────────────────────────────────────────────────────┐
│              Executor Agent (Sub-Agent)                       │
│  - Receives semantic intent                                   │
│  - Grounds to actual UI elements                              │
│  - Executes one atomic action                                 │
│  - Returns success/failure summary to planner                 │
└────────────────────────────────────────────────────────────────┘
```

In `AgentMode.BASIC`, delegation is disabled and the standalone main agent executes UI tools directly.

---

## Core Components

### AgentDef + AgentDefRegistry

→ See: `agent/definition/AgentDef.kt` and `agent/definition/AgentDefRegistry.kt`

Static role definitions centralize:
- system prompt text
- allowed tool set
- execution role
- whether delegation tooling must be wired

Main-agent selection is mode-based (`BASIC` -> standalone, `PRO` -> planner), and executor definition is reused by sub-agent wiring.

### AgentDefinition

→ See: `agent/subagent/SubAgentRunner.kt`

Defines a sub-agent invokable via `delegate_task`:

```kotlin
data class AgentDefinition(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val toolNames: List<String>,
    val maxTurns: Int = 10,
    val timeoutMs: Long = 60_000,
    val narrativeSummaryOnLimit: Boolean = true,
    val executionRole: AgentExecutionRole? = null
)
```

### AgentRegistry

→ See: `agent/subagent/SubAgentRunner.kt`

In-memory registry for available sub-agents:

```kotlin
class AgentRegistry {
    fun register(definition: AgentDefinition)
    fun get(name: String): AgentDefinition?
    fun getAll(): List<AgentDefinition>
    fun getDirectoryPrompt(): String

    companion object {
        fun createDefault(): AgentRegistry
    }
}
```

### IsolatedSubAgentRunner

→ See: `agent/subagent/SubAgentRunner.kt`

Executes one delegated request with isolated runtime state:
- Creates child `Agent` with filtered tools
- Uses child history (no parent history access)
- Shares parent scratchpad intentionally for handoff
- Emits bridged activity events to parent session
- Returns normalized `SubAgentResult(success, message)`
- Produces a narrative summary when step limit is reached

---

## DelegateTaskTool

→ See: `tool/impl/DelegateTaskTool.kt`

Tool for delegating to a sub-agent:

```kotlin
delegate_task(
    agent_name = "executor",
    query = "Tap on the 'Send' button",
    current_subgoal = "Send the email",               // optional
    important_notes = ["Recipient: john@example.com"] // optional
)
```

### Context Passing

When delegating, pass only:
- `query` - self-contained instruction (required)
- `current_subgoal` - current planner objective (optional)
- `important_notes` - key facts (optional)

Do not pass full history/screenshots/raw tree dumps. Executor captures fresh screen state itself.

---

## Built-in Sub-Agent

| Agent | Description | Tools |
|-------|-------------|-------|
| `executor` | UI grounding and atomic action execution | `mobile_action`, `system_button`, `wait`, `open_app`, `scratchpad`, `complete_task` |

### Executor Agent

→ See: `agent/definition/ExecutorAgentDef.kt`

`ExecutorAgentDef` includes:
- Name: `executor`
- Prompt: `ExecutorAgentDef.systemPrompt`
- Max turns: `5` (implicitly via `AgentDefinition` default or override)
- Role: `AgentExecutionRole.EXECUTOR`

### Planner Agent

→ See: `agent/definition/PlannerAgentDef.kt`

`PlannerAgentDef` includes:
- Name: `planner`
- Tools: `open_app`, `write_todos`, `scratchpad`, `delegate_task`, `complete_task`
- Role: `AgentExecutionRole.PLANNER`

---

## Runtime Wiring

→ See: `session/SessionAgentRunner.kt`

`SessionAgentRunner` registers `delegate_task` only when the active main-agent definition requires it (via `requiresDelegationToolRegistration`).

When enabled, it creates a default `AgentRegistry` (`executor` included) and connects it to `IsolatedSubAgentRunner`.

---

## Sub-Agent Events

| Event | Description |
|-------|-------------|
| `SubAgentStarted` | Delegation begins |
| `SubAgentActivity` | Bridged status from sub-agent |
| `SubAgentCompleted` | Sub-agent finished |

→ See: [Protocol Events](../protocol/protocol.md#sub-agent-events)

---

## Adding New Sub-Agents

1. Define a new object inheriting `AgentDef` in `agent/definition/`.
2. Register it in `AgentRegistry` creation.
3. Keep prompt + tool list tightly scoped.
4. Ensure `SessionAgentRunner` wiring handles it.

---

## Related Docs

- [Overview](overview.md) - Architecture context
- [Loop](loop.md) - How delegation fits in ReAct loop
- [Planning](planning.md) - Scratchpad for cross-agent data
- [Protocol](../protocol/protocol.md) - Sub-agent events

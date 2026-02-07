# Multi-Agent System

> Sub-agent delegation, executor agents, and orchestration.
> Last updated: 2026-02-05 (commit: 4fa87d8484fddd0862e63fcc08a740646af9a77c)

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

→ See: `agent/subagent/SubAgentRunner.kt` and `agent/definition/ExecutorAgentDef.kt`

`ExecutorAgent.definition` includes:
- Name: `executor`
- Prompt: `AgentDefRegistry.executor().systemPrompt`
- Max turns: `5`
- Timeout: `30_000ms`
- Role: `AgentExecutionRole.EXECUTOR`

---

## Runtime Wiring

→ See: `session/SessionAgentRunner.kt`

`SessionAgentRunner` lazily registers `delegate_task` only when the active main-agent definition requires it (currently `AgentMode.PRO`).

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

1. Add a new `AgentDef` (if needed) and/or `AgentDefinition`.
2. Register it in `AgentRegistry` creation.
3. Keep prompt + tool list tightly scoped.
4. Ensure `SessionAgentRunner` wiring and mode constraints remain explicit.

---

## Related Docs

- [Overview](overview.md) - Architecture context
- [Loop Execution](loop.md) - How delegation fits in ReAct loop
- [Planning State](planning.md) - Scratchpad for cross-agent data
- [Protocol](../protocol/protocol.md) - Sub-agent events

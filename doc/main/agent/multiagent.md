# Multi-Agent System

> Sub-agent delegation, executor agents, and orchestration.
> Last updated: 2026-02-06

## Planner-Executor Pattern

The Android Agent uses delegation where the main agent is a **planner** and delegates atomic UI actions to an **executor** sub-agent.

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

---

## Core Components

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

Executes one delegated sub-agent request with isolated runtime state:
- Creates a child `Agent` with filtered tools
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
| `executor` | UI grounding and atomic action execution | `mobile_action`, `app_control`, `scratchpad`, `complete_task` |

### Executor Agent

→ See: `agent/subagent/SubAgentRunner.kt`

`ExecutorAgent.definition` includes:
- Name: `executor`
- Prompt: `ExecutorPromptTemplate.systemPrompt`
- Max turns: `5`
- Timeout: `30_000ms`
- Role: `AgentExecutionRole.EXECUTOR`

---

## Runtime Wiring

→ See: `session/SessionAgentRunner.kt`

`SessionAgentRunner` lazily registers `DelegateTaskTool` and creates a default `AgentRegistry` (`executor` included) when delegation is first needed.

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

1. Add a new `AgentDefinition` and register it in `AgentRegistry` creation.
2. Ensure the prompt + tool list are scoped to that agent's responsibility.
3. Expose it through `DelegateTaskTool` registry wiring in `SessionAgentRunner`.

---

## Related Docs

- [Overview](overview.md) - Architecture context
- [Loop Execution](loop.md) - How delegation fits in ReAct loop
- [Planning State](planning.md) - Scratchpad for cross-agent data
- [Protocol](../protocol/protocol.md) - Sub-agent events

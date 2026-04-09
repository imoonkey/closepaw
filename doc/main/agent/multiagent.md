# Multi-Agent System

> Sub-agent delegation, executor agents, and orchestration.
> Last updated: 2026-03-05 (commit: 0b5b379)

## Planner-Executor Pattern

In `AgentMode.PRO`, the main agent is a **planner** and delegates atomic UI actions to an **executor** sub-agent.

```
┌────────────────────────────────────────────────────────────────┐
│              Main Agent (Planner)                              │
│  - Plans multi-step tasks                                      │
│  - Manages todos and scratchpad                                │
│  - Delegates atomic actions via delegate_task                  │
└───────────────────────┬────────────────────────────────────────┘
                        │ delegate_task(query)
                        ▼
┌────────────────────────────────────────────────────────────────┐
│              Executor Agent (Sub-Agent)                        │
│  - Receives semantic intent                                    │
│  - Grounds to actual UI elements                               │
│  - Executes one atomic action                                  │
│  - Returns success/failure summary to planner                  │
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
- execution role (`PLANNER`, `EXECUTOR`, `STANDALONE`)
- whether delegation tooling must be wired

Main-agent selection is mode-based (`BASIC` → standalone, `PRO` → planner), and executor definition is reused by sub-agent wiring.

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
- Shares parent scratchpad intentionally for data handoff
- Emits bridged activity events to parent session
- Returns normalized `SubAgentResult(success, message)`
- Produces a narrative summary when step limit is reached (via `DelegationSummaryFormatter`)
- Handles timeout via `withTimeoutOrNull`

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

## Built-in Sub-Agents

| Agent | Description | Tools |
|-------|-------------|-------|
| `executor` | UI grounding and atomic action execution | `mobile_action`, `system_button`, `wait`, `open_app`, `scratchpad`, `complete_task`, `ask_user` |

### Executor Agent

→ See: `agent/definition/ExecutorAgentDef.kt`

- Name: `executor`
- Role: `AgentExecutionRole.EXECUTOR`
- Max turns: defined in `AgentDefinition` (default 10)
- Prompt: covers query types (TAP, SCROLL, EXTRACT, TYPE, BACK, OPEN APP), failure recovery, one-atomic-action-per-delegation rule

### Planner Agent

→ See: `agent/definition/PlannerAgentDef.kt`

- Name: `planner`
- Role: `AgentExecutionRole.PLANNER`
- Tools: `open_app`, `write_todos`, `scratchpad`, `delegate_task`, `complete_task`
- Prompt: high-level planning, delegation patterns, failure recovery, atomic action guidance for executor

### Standalone Agent

→ See: `agent/definition/StandaloneAgentDef.kt`

- Name: `standalone`
- Role: `AgentExecutionRole.STANDALONE`
- Tools: `mobile_action`, `system_button`, `wait`, `open_app`, `scratchpad`, `write_todos`, `complete_task`, `ask_user`
- Prompt: end-to-end task completion, direct UI interaction

---

## Runtime Wiring

→ See: `session/SessionAgentRunner.kt`

`SessionAgentRunner` registers `delegate_task` only when the active main-agent definition requires it (via `requiresDelegationToolRegistration`). It also always registers `ask_user`.

When delegation is enabled, it creates a default `AgentRegistry` (with executor included) and connects it to `IsolatedSubAgentRunner`.

---

## Model Resolution for Agents

→ See: `agent/AgentModelResolver.kt`

Each agent can use a different model:
- **Main agent**: uses `SessionConfig.mainModel` (default `glm-5`)
- **Executor agent**: uses `SessionConfig.executorModel` if set, otherwise falls back to main model
- Resolution goes through `ModelCatalog` to find provider details and create the appropriate `LLMClient`

---

## Sub-Agent Events

| Event | Description |
|-------|-------------|
| `SubAgentStarted` | Delegation begins (agentName, query) |
| `SubAgentActivity` | Bridged status from sub-agent (agentName, activity) |
| `SubAgentCompleted` | Sub-agent finished (agentName, success, message) |

→ See: [Protocol Events](../protocol/overview.md#sub-agent-events)

---

## Adding New Sub-Agents

1. Define a new object inheriting `AgentDef` in `agent/definition/`.
2. Register it in `AgentRegistry` creation via `SubAgentRunner.kt`.
3. Keep prompt + tool list tightly scoped.
4. Ensure `SessionAgentRunner` wiring handles it.

---

## Related Docs

- [Overview](overview.md) - Architecture context
- [Loop](loop.md) - How delegation fits in ReAct loop
- [Planning](planning.md) - Scratchpad for cross-agent data
- [Protocol](../protocol/overview.md) - Sub-agent events

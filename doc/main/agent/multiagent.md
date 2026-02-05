# Multi-Agent System

> Sub-agent delegation, executor agents, and orchestration.
> Last updated: 2026-02-05

## Planner-Executor Pattern

The Android Agent uses a delegation pattern where the main agent acts as a **planner** and delegates atomic UI actions to specialized **executor** sub-agents.

```
┌────────────────────────────────────────────────────────────────┐
│              Main Agent (Planner)                               │
│  - Plans multi-step tasks                                       │
│  - Manages todos and scratchpad                                 │
│  - Delegates atomic actions via delegate_task                   │
└───────────────────────┬────────────────────────────────────────┘
                        │ delegate_task(query)
                        ▼
┌────────────────────────────────────────────────────────────────┐
│              Executor Agent (Sub-Agent)                         │
│  - Receives semantic intent                                     │
│  - Grounds to actual UI elements                                │
│  - Executes ONE atomic action                                   │
│  - Returns result to planner                                    │
└────────────────────────────────────────────────────────────────┘
```

---

## Core Components

### AgentDefinition

→ See: `agent/subagent/AgentDefinition.kt`

Defines a sub-agent that can be invoked via `delegate_task`:

```kotlin
data class AgentDefinition(
    val name: String,           // e.g., "executor"
    val description: String,    // For agent directory prompt
    val systemPrompt: String,   // Sub-agent system prompt
    val toolNames: List<String>, // Tools available to this agent
    val maxTurns: Int = 10,
    val timeoutMs: Long = 60_000,
    val narrativeSummaryOnLimit: Boolean = true
)
```

### AgentRegistry

→ See: `agent/subagent/AgentRegistry.kt`

Registry for available sub-agents:

```kotlin
class AgentRegistry {
    fun register(definition: AgentDefinition)
    fun get(name: String): AgentDefinition?
    fun getAll(): List<AgentDefinition>
    fun getDirectoryPrompt(): String  // For delegate_task description
}
```

### SubAgentRunner

→ See: `agent/subagent/SubAgentRunner.kt`

Executes a sub-agent with isolated context:
- Creates a fresh `AgentRuntime` with filtered tools
- Injects sub-agent system prompt
- Inherits parent `cognitionProfileId` for consistent planner/executor cognition mode
- Bridges events to parent session
- Returns `SubAgentResult` on completion
- Emits a narrative failure summary when max-turn limit is reached (if enabled)

**Isolation properties:**
- Child has its own history (no parent history access)
- Child reads fresh screen state on each turn
- `scratchpad` is intentionally **shared** for data handoff

---

## DelegateTaskTool

→ See: `tool/impl/DelegateTaskTool.kt`

Tool for delegating tasks to sub-agents:

```kotlin
delegate_task(
    agent_name = "executor",
    query = "Tap on the 'Send' button",
    current_subgoal = "Send the email",        // optional
    important_notes = ["Recipient: john@example.com"]  // optional
)
```

### Context Passing

When delegating, pass only:
- `query` — Self-contained instruction (required)
- `current_subgoal` — What we're trying to achieve (optional)
- `important_notes` — Short list of key facts (optional)

**Do NOT pass:** full history, prior screenshots, prior a11y trees.

The executor reads the current screen in its own turn loop. For structured data handoff, use the shared `scratchpad`.

---

## Built-in Sub-Agents

| Agent | Description | Tools |
|-------|-------------|-------|
| `executor` | UI grounding and atomic action execution | `mobile_action`, `app_control`, `scratchpad`, `complete_task` |

### Executor Agent

→ See: `agent/subagent/ExecutorAgent.kt`

The default executor agent specializes in:
- Reading current screen state
- Grounding semantic instructions to UI elements
- Executing a single atomic action
- Reporting success/failure back to planner

`SessionAgentRunner` tunes executor `maxTurns` from the active cognition profile (`maxExecutorSteps`) before registering `delegate_task`.

---

## Sub-Agent Events

Events emitted during sub-agent execution:

| Event | Description |
|-------|-------------|
| `SubAgentStarted` | Delegation begins |
| `SubAgentActivity` | Bridged status from sub-agent |
| `SubAgentCompleted` | Sub-agent finished |

→ See: [Protocol Events](../protocol/protocol.md#sub-agent-events)

---

## Adding New Sub-Agents

1. Create `AgentDefinition` with:
   - Unique name
   - Specialized system prompt
   - Filtered tool list
   
2. Register in `agent/subagent/AgentRegistry.kt`

3. Document in agent directory for `delegate_task` prompt

---

## Related Docs

- [Overview](overview.md) - Architecture context
- [Loop Execution](loop.md) - How delegation fits in ReAct loop
- [Planning State](planning.md) - Scratchpad for cross-agent data
- [Protocol](../protocol/protocol.md) - Sub-agent events

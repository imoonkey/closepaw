# Agent Loop Execution

> ReAct loop, Turn mechanics, and streaming execution.
> Last updated: 2026-02-04

## ReAct Loop

The agent executes a classic ReAct (Reasoning + Acting) loop within each **Turn**:

```
┌─────────────────────────────────────────────────────────────────┐
│                      ReAct Loop (Per Turn)                       │
│                                                                  │
│   ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌────────┐ │
│   │ PERCEIVE │────►│  THINK   │────►│   ACT    │────►│OBSERVE │ │
│   │ (Screen) │     │ (LLM +   │     │  (Tool)  │     │(Screen)│ │
│   │          │     │ Streaming)│     │          │     │        │ │
│   └──────────┘     └──────────┘     └──────────┘     └────┬───┘ │
│        ▲                                                  │     │
│        │                                                  │     │
│        └──────────────────────────────────────────────────┘     │
│          (Loop until complete_task or text-only response)       │
└─────────────────────────────────────────────────────────────────┘
```

---

## Task Lifecycle

```
Op.UserInput("Check email")
       │
       ▼
┌───────────┐
│TaskStarted│ ◄─── Emit event with taskId
└─────┬─────┘
       │
       ▼
┌───────────────────────────────────────────┐
│  Turn 1: Perceive → Think → Act → Observe │──► MessageDelta
│  Turn 2: Perceive → Think → Act → Observe │──► MessageDelta
│  Turn N: ... (complete_task or text-only) │
└─────┬─────────────────────────────────────┘
       │
       ▼
┌─────────────┐
│TaskCompleted│ ◄─── Emit event, transition to Idle
└─────────────┘
```

---

## Core Components

### AgentRuntime

→ See: `agent/AgentRuntime.kt`

The brain of the system. Executes the ReAct loop until goal achieved or stopped.

**Responsibilities:**
- Run the Perceive → Think → Act → Observe cycle
- Manage turn count and stop conditions
- Emit events for UI updates
- Handle pause/resume/stop lifecycle

**Supporting helpers:**
- `AgentPromptBuilder` builds system prompt + user context
- `agent/cognition/prompt/PromptAssembler` assembles role/profile aware prompts
- `agent/cognition/context/ContextPackager` packages per-turn input context
- `agent/cognition/policy/TurnPolicyEngine` arbitrates tool calls + completion
- `ActionDescriptionFormatter` formats tool action descriptions
- `AgentEventDispatcher` emits `AgentEvent` with timestamps
- `AgentObservation` converts tool observations into agent observations

### Turn

→ See: `agent/Turn.kt`

Encapsulates a single LLM call using the OpenAI Responses API with native tool calling and streaming.

**Responsibilities:**
- Build input items from history + current context (via `TurnInputBuilder`)
- Generate tool schemas dynamically via `ToolRegistry.generateResponsesApiTools()`
- Stream text and tool calls via `runStreaming()` method
- Detect completion (via `complete_task` tool or text-only response)

### Cognition Integration

→ See: `agent/cognition/`

- **Prompt layer**: profile-aware prompt assembly (`baseline` / `concise`)
- **Context layer**: explicit packager boundary before LLM input item build, with system reminders for loop warnings, turn-budget pressure, todos, and scratchpad usage
- **Policy layer**: multi-tool arbitration and completion decision extracted from runner
- **Loop guard**: a11y-tree signature similarity + repeated action/scroll detection
- **Step guard**: warns from `maxTurns - 2` and injects final-turn narrative summary at limit
- **Trace layer**: full prompt/input-item artifacts with redaction, plus tool arbitration decision logging

### Runner State

→ See: `agent/TurnRunnerState.kt`

Per-turn mutable cognition state is carried explicitly by `AgentRuntime` and passed into `AgentTurnRunner`:
- `navigationState` (recent screen signatures/actions)
- `previousActionSignature` (used to detect repeated action loops)

---

## Streaming

### Stream Events

```kotlin
sealed interface TurnStreamEvent {
    data class TextDelta(val text: String)           // Streaming text chunk
    data class ToolCallReceived(val toolCall: ToolCallRequest)  // Tool call ready
    data class Complete(val result: TurnResult)      // Turn finished
    data class Error(val error: Throwable)           // Error occurred
}
```

### Turn Result

```kotlin
data class TurnResult(
    val content: String?,           // Accumulated text from LLM
    val toolCalls: List<ToolCallRequest>,  // Tool calls to execute
    val isComplete: Boolean,        // Whether task is done
    val parseErrors: List<String>?  // Parsing issues (rare)
)
```

---

## Data Flow

```
Turn.runStreaming()          Agent                AgentSession           UI
       │                       │                       │                  │
       │ TextDelta("I'll")     │                       │                  │
       │──────────────────────►│ MessageDelta(delta)   │                  │
       │                       │──────────────────────►│─────────────────►│
       │                       │                       │                  │ Append text
       │                       │                       │                  │
       │ Complete(result)      │                       │                  │
       │──────────────────────►│                       │                  │
       │                       │ Execute tools from result               │
```

---

## Quick Reference

### Stop Conditions

| Condition | Result |
|-----------|--------|
| `complete_task` tool called | `GOAL_ACHIEVED` |
| Text-only response (no tools) | `GOAL_ACHIEVED` |
| Max turns reached | `MAX_TURNS` |
| User interrupt | `INTERRUPTED` |
| Unrecoverable error | `ERROR` |

### Turn Phases

| Phase | Description |
|-------|-------------|
| `PERCEPTION` | Capturing/analyzing screen |
| `PLANNING` | LLM reasoning (streaming) |
| `EXECUTION` | Tool execution |

### Trace Artifacts (Per Turn)

When trace is enabled, `AgentTrace` emits cognition-focused artifacts:

- `turn_{n}_full_prompt.txt`
- `turn_{n}_llm_input_items.json`
- `turn_{n}_history.json`
- `turn_{n}_system.txt`
- `turn_{n}_user_context.txt`

Plus run-level summary:

- `run_summary.json`

---

## Related Docs

- [Overview](overview.md) - Architecture context
- [Multi-Agent](multiagent.md) - Delegation during loop
- [Planning State](planning.md) - State persistence across turns
- [Protocol](../protocol/protocol.md) - Event types

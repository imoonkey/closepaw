# Agent Loop Execution

> ReAct loop, Turn mechanics, and streaming execution.
> Last updated: 2026-02-06

## ReAct Loop

The agent executes a classic ReAct (Reasoning + Acting) loop within each **Turn**:

```
┌─────────────────────────────────────────────────────────────────┐
│                      ReAct Loop (Per Turn)                     │
│                                                                 │
│   ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌────────┐│
│   │ PERCEIVE │────►│  THINK   │────►│   ACT    │────►│OBSERVE ││
│   │ (Screen) │     │ (LLM +   │     │  (Tool)  │     │(Screen)││
│   │          │     │ Streaming)│    │          │     │        ││
│   └──────────┘     └──────────┘     └──────────┘     └────┬───┘│
│        ▲                                                  │    │
│        │                                                  │    │
│        └──────────────────────────────────────────────────┘    │
│          (Loop until complete_task or text-only response)      │
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

### Agent

→ See: `agent/Agent.kt`

Top-level controller that runs turn-by-turn until a stop condition.

**Responsibilities:**
- Manage loop lifecycle (`run`, `pause`, `resume`, `stop`)
- Track turn count and max-turn enforcement
- Carry cross-turn `TurnRunnerState`
- Handle recoverable vs fatal turn errors

### AgentTurnRunner

→ See: `agent/AgentTurnRunner.kt`

Executes one full turn.

**Responsibilities:**
- Capture pre-turn snapshot and emit perception events
- Build prompt context and stream LLM response via `Turn`
- Apply `TurnToolPolicy` to choose executable tool calls
- Execute selected tools and persist outputs/observations
- Decide turn outcome (`Continue`, `Complete`, `Error`, `Cancelled`)

### Turn

→ See: `agent/Turn.kt`

Encapsulates a single Responses API call with streaming.

**Responsibilities:**
- Build input items from history + current user context
- Generate tool schemas from `ToolRegistry`
- Stream text and tool calls via `runStreaming()`
- Mark completion when `complete_task` is present, or text-only response has no tool calls

### Runtime Types

→ See: `agent/AgentRuntimeTypes.kt`

Defines runtime control/result types:
- `AgentStopReason`
- `TurnOutcome`
- `TurnRunnerState`
- `TurnExecutionResult`

---

## Cognition Integration

→ See: `agent/cognition/`

- **Prompt layer**: `PromptUtils` + Planner/Executor templates generate system/user prompt text
- **Context layer**: `NavigationState` tracks recent screens/actions for loop detection
- **Policy layer**: `TurnToolPolicy` arbitrates tool calls and completion
- **Loop guard**: `LoopDetectionPolicy` emits reminders for repeated screens/actions/scroll loops
- **Step guard**: `ExecutorStepPolicy` injects turn-budget reminders and final-turn narrative summary

---

## Streaming

### Stream Events

```kotlin
sealed interface TurnStreamEvent {
    data class TextDelta(val text: String)
    data class ToolCallReceived(val toolCall: ToolCallRequest)
    data class Complete(val result: TurnResult)
    data class Error(val error: Throwable)
}
```

### Turn Result

```kotlin
data class TurnResult(
    val content: String?,
    val toolCalls: List<ToolCallRequest>,
    val isComplete: Boolean
)
```

---

## Tool Arbitration

`TurnToolPolicy` enforces a one-tool-per-turn execution model:
- Prefer the first non-`complete_task` tool call
- Defer `complete_task` if another action tool exists
- Complete task only when no non-completion action remains

→ See: `agent/cognition/policy/TurnToolPolicy.kt`

---

## Quick Reference

### Stop Conditions

| Condition | Result |
|-----------|--------|
| `complete_task` selected without non-completion tool | `GoalAchieved` |
| Text-only response with no tool calls | `GoalAchieved` |
| Max turns reached | `MaxTurnsReached` |
| User interrupt/stop signal | `UserRequested` |
| Unrecoverable error | `Error(message)` |

### Turn Phases

| Phase | Description |
|-------|-------------|
| `PERCEPTION` | Capturing and analyzing screen |
| `PLANNING` | LLM reasoning and tool selection |
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

→ See: `trace/AgentTrace.kt`

---

## Related Docs

- [Overview](overview.md) - Architecture context
- [Multi-Agent](multiagent.md) - Delegation during loop
- [Planning State](planning.md) - State persistence across turns
- [Protocol](../protocol/protocol.md) - Event types

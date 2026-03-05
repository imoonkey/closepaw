# Agent Loop Execution

> ReAct loop, Turn mechanics, and streaming execution.
> Last updated: 2026-03-05 (commit: 0b5b379)

## ReAct Loop

The agent executes a classic ReAct (Reasoning + Acting) loop within each **Turn**:

```
┌─────────────────────────────────────────────────────────────────┐
│                      ReAct Loop (Per Turn)                      │
│                                                                 │
│   ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌────────┐│
│   │ PERCEIVE │────►│  THINK   │────►│   ACT    │────►│OBSERVE ││
│   │ (Screen) │     │ (LLM +   │     │  (Tool)  │     │(Screen)││
│   │          │     │ Streaming)│     │          │     │        ││
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
- Carry cross-turn `TurnRunnerState` (navigation state, previous action signature)
- Handle recoverable vs fatal turn errors via `TurnErrorClassifier`
- Coordinate pause/resume via `lifecycleMutex` and `Deferred<Unit>`

### AgentTurnRunner

→ See: `agent/AgentTurnRunner.kt`

Orchestrates one full turn by delegating to two phase runners.

**Responsibilities:**
- Capture pre-turn snapshot and emit perception events
- Delegate to `TurnPlanningPhaseRunner` for LLM call + arbitration
- Delegate to `TurnExecutionPhaseRunner` for tool execution + observation
- Build warnings (loop detection, final-turn) via `buildWarnings()`
- Decide turn outcome via `decideTurnOutcome()` (`Continue`, `Complete`, `Error`, `Cancelled`)

### TurnPlanningPhaseRunner

→ See: `agent/TurnPlanningPhaseRunner.kt`

Handles the LLM thinking phase.

**Responsibilities:**
- Build prompt input items via `PromptBuilder`
- Record current screen observation into history (before LLM call, so current turn does not duplicate itself)
- Resolve model via `AgentModelResolver` (catalog-driven, supports per-agent model override)
- Stream LLM response via `Turn.runStreaming()`
- Apply `TurnToolPolicy` arbitration (one-tool-per-turn, completion deferral)
- Emit agent thought for Smart Capsule display
- Record trace artifacts when enabled

### TurnExecutionPhaseRunner

→ See: `agent/TurnExecutionPhaseRunner.kt`

Handles tool execution after planning.

**Responsibilities:**
- Execute each selected tool call via `ToolRouter`
- Capture post-action screen observation via `ObservationBuilder`
- Emit `ActionProposed` and `ActionExecuted` events
- Record tool results into history for future turns
- Emit planning events (`TodosUpdated`, `ScratchpadUpdated`) when relevant tools run

### Turn

→ See: `agent/Turn.kt`

Encapsulates a single LLM call with streaming.

**Responsibilities:**
- Execute the LLM call with prebuilt `inputItems`
- Generate tool schemas from `ToolRegistry` with `allowedToolNames` filtering
- Stream text and tool calls via `runStreaming()`
- Recover tool calls from text output when LLM returns tool invocations as text (fallback)
- Mark completion when `complete_task` is present, or text-only response has no tool calls

### Runtime Types

→ See: `agent/AgentRuntimeTypes.kt`

Defines runtime control/result types:
- `AgentStopReason` — `GoalAchieved`, `UserRequested`, `MaxTurnsReached`, `Error`
- `TurnOutcome` — `Continue`, `Complete(message, success)`, `Error(recoverable)`, `Cancelled`
- `TurnRunnerState` — cross-turn state: `navigationState`, `previousActionSignature`
- `TurnExecutionResult` — `outcome` + `nextState`

---

## Cognition Integration

→ See: `agent/cognition/`

- **Prompt layer**: `PromptBuilder` assembles History → Working Memory → Current Observation input items
- **Context layer**: `NavigationState` tracks recent screen signatures and actions for loop detection. Maintains a sliding window of `MAX_ACTION_HISTORY = 8` recent actions. Also tracks `consecutiveLoopTurns` and `blockedActions` for loop escalation.
- **Policy layer**: `TurnToolPolicy` arbitrates tool calls — keeps cognitive tools, at most one screen-changing tool, defers `complete_task` when action tools exist. Can block specific action signatures during loop escalation.
- **Loop guard**: `LoopDetectionPolicy` returns `LoopDetectionResult` with both warning and escalation level. Three tiers of intervention:
  - **Tier 1 (ADVISORY)**: warning text injected into LLM prompt
  - **Tier 2 (BLOCK)**: after 2 consecutive CRITICAL loop turns, block repeated action signatures + strategy-change directive
  - **Tier 3 (FORCE_COMPLETE)**: after 5 consecutive loop turns, force `complete_task(status=failure)`
- **Action signatures**: `ActionSignature.classifyActionSignature()` produces stable signatures (e.g., `mobile_action:click:idx=12`, `open_app:markor`) for loop detection and action blocking
- **Step guard**: `ExecutorStepPolicy` contributes final-turn warning text when limit is reached, produces narrative summary of attempts

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

`TurnToolPolicy` enforces structured tool execution per turn:

- **Cognitive tools** (`write_todos`, `scratchpad`): always allowed alongside a screen action
- **Screen-changing tools** (`mobile_action`, `open_app`, etc.): at most one per turn
- `complete_task`: deferred if a non-completion action tool exists
- Completion decided only when no non-completion action remains

→ See: `agent/cognition/policy/TurnToolPolicy.kt`

---

## Error Classification

→ See: `agent/TurnErrorClassifier.kt`

Errors are classified into recoverable vs fatal:

| Error Pattern | Classification | Recoverable |
|---------------|---------------|-------------|
| DNS failure | Network error | Yes |
| Context length exceeded | Context limit | Yes |
| Transient network error | Network error | Yes |
| Rate limit (429) | Rate limit | Yes |
| Other exceptions | Unknown | No |

Recoverable errors allow the loop to continue; fatal errors stop the agent.

---

## Quick Reference

### Stop Conditions

| Condition | Result |
|-----------|--------|
| `complete_task` selected without non-completion tool | `GoalAchieved` (success=true) or `Error` (success=false) |
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

When trace is enabled, `AgentTrace` emits cognition-focused artifacts organized by category:

| Artifact Folder | Content |
|-----------------|---------|
| `llm_system_prompt/` | System prompt text |
| `llm_user_context/` | User observation + warnings |
| `llm_full_prompt/` | Complete prompt assembly |
| `llm_input_items/` | JSON input items sent to LLM |
| `llm_history/` | Conversation history JSON |
| `llm_tool_calls/` | Tool calls returned by LLM |

Plus run-level summary: `run_summary.json`

### Trace Flush

`TraceRecorder` exposes a `flush()` suspend function that blocks until all enqueued trace events are written to disk. `AgentSession.handleAgentComplete()` calls `flush()` **before** emitting `TaskCompleted`, ensuring trace data is persisted before the eval runner force-stops the process.

`FileTraceRecorder` implements flush via a `CompletableDeferred`-based `WriteOp.Flush` sent through the writer channel. The writer loop completes the deferred after processing all preceding writes. `NoopTraceRecorder.flush()` is a no-op.

→ See: `trace/TraceRecorder.kt`, `trace/FileTraceRecorder.kt`

---

## Related Docs

- [Overview](overview.md) - Architecture context
- [Multi-Agent](multiagent.md) - Delegation during loop
- [Planning State](planning.md) - State persistence across turns
- [Turn Prompt Anatomy](turn_prompt_anatomy.md) - Exact prompt composition
- [Protocol](../protocol/protocol.md) - Event types

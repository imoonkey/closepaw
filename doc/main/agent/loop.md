# Agent Loop Execution

> ReAct loop, Turn mechanics, and streaming execution.
> Last updated: 2026-04-09

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
- Carry cross-turn `TurnRunnerState` (navigation state)
- Handle recoverable vs fatal turn errors via `TurnErrorClassifier`
- Coordinate pause/resume via `lifecycleMutex` and `Deferred<Unit>`

### AgentTurnRunner

→ See: `agent/AgentTurnRunner.kt`

Orchestrates one full turn by delegating to two phase runners.

**Responsibilities:**
- Capture pre-turn snapshot and emit perception events
- **Perception gate:** classify foreground app via `AppClassifier` and mask BLOCKED app screens (empty elements, no image) before the LLM sees them. Inject security warning text guiding the agent to navigate away.
- Delegate to `TurnPlanningPhaseRunner` for LLM call + arbitration
- Delegate to `TurnExecutionPhaseRunner` for tool execution + observation
- Build warnings (loop detection, final-turn, security) via `buildWarnings()`
- Decide turn outcome via `decideTurnOutcome()` (`Continue`, `Complete`, `Error`, `Cancelled`)

### TurnPlanningPhaseRunner

→ See: `agent/TurnPlanningPhaseRunner.kt`

Handles the LLM thinking phase.

**Responsibilities:**
- Build prompt input items via `PromptBuilder`
- Build canonical `TurnObservation` from current screen snapshot (shared by prompt and history)
- Record observation into history
- Resolve model via `AgentModelResolver` (catalog-driven, supports per-agent model override)
- Stream LLM response via `Turn.runStreaming()`
- Apply `TurnToolPolicy` arbitration (cognitive tools always kept, screen-changing tools kept, completion deferral)
- Emit agent thought for Smart Capsule display
- Record trace artifacts when enabled

### TurnExecutionPhaseRunner

→ See: `agent/TurnExecutionPhaseRunner.kt`

Handles tool execution after planning.

**Responsibilities:**
- Execute each selected tool call via `ToolRouter`
- Capture post-action screen observation via `ObservationBuilder` (with BLOCKED app masking applied)
- Emit `ActionProposed` and `ActionExecuted` events via `AgentEventDispatcher`
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
- `TurnRunnerState` — cross-turn state: `navigationState`
- `TurnExecutionResult` — `outcome` + `nextState`

---

## Cognition Integration

→ See: `agent/cognition/`

- **Prompt layer**: `PromptBuilder` assembles History → Working Memory → Recalled Memory → App Skill → Current Observation input items. Current observation uses `TurnObservation` (canonical payload shared with history).
- **Memory layer**: `MemoryRecaller.recall(currentPackageName)` injects cross-session learnings per turn; `Agent.kt` auto-retains `[pitfall]` entries on failure
- **Context layer**: `NavigationState` tracks recent screen signatures for loop detection.
- **Policy layer**: `TurnToolPolicy` arbitrates tool calls — keeps cognitive tools and screen-changing tools, defers `complete_task` when action tools exist. Navigation isolation (one screen-changing action per turn) is enforced at the prompt layer, not in code.
- **Loop guard**: `LoopDetectionPolicy` detects stable screens (near-identical for 5 consecutive turns at Jaccard >= 0.95) and emits a factual warning. No strategy suggestions — the LLM decides what to do. Turn limit is the only hard stop mechanism.
- **Action signatures**: `ActionSignature.classifyActionSignature()` produces stable signatures (e.g., `mobile_action:click:idx=12`, `open_app:markor`) for action descriptions
- **Step guard**: `isFinalTurn()` contributes final-turn warning text when limit is reached; `DelegationSummaryFormatter` produces narrative summary of attempts for delegated agents

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

- **Cognitive tools** (`write_todos`, `scratchpad`): always allowed alongside screen actions
- **Screen-changing tools** (`mobile_action`, `open_app`, etc.): all kept (multi-action for form filling); navigation isolation enforced by prompt
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
| `PERCEPTION` | Capturing screen, classifying app tier, masking BLOCKED content |
| `PLANNING` | LLM reasoning and tool selection |
| `EXECUTION` | Tool execution (policy-gated by app tier) |

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

### Memory Auto-Retain

Before trace flush, `Agent.kt` checks for failed tasks where the LLM never voluntarily called `remember_experience`. If both conditions hold, it auto-saves a `[pitfall]` entry using `lastKnownPackage` as fallback when `getCurrentPackageName()` returns null.

→ See: [memory.md](memory.md) for details.

`FileTraceRecorder` implements flush via a `CompletableDeferred`-based `WriteOp.Flush` sent through the writer channel. The writer loop completes the deferred after processing all preceding writes. `NoopTraceRecorder.flush()` is a no-op.

→ See: `trace/TraceRecorder.kt`, `trace/FileTraceRecorder.kt`

---

## Related Docs

- [Overview](overview.md) - Architecture context
- [Multi-Agent](multiagent.md) - Delegation during loop
- [Planning State](planning.md) - State persistence across turns
- [Turn Prompt Anatomy](turn_prompt_anatomy.md) - Exact prompt composition
- [Protocol](../protocol/overview.md) - Event types

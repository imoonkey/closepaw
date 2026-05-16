# Agent Loop Execution

> ReAct loop, Turn mechanics, streaming execution, and auto-compaction.
> Last updated: 2026-05-16

## ReAct Loop

The agent executes a classic ReAct (Reasoning + Acting) loop within each **Turn**:

```
┌──────────────────────────────────────────────────────────────────────┐
│                       ReAct Loop (Per Turn)                          │
│                                                                      │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐  ┌──────┐│
│  │ COMPACT? │──►│ PERCEIVE │──►│  THINK   │──►│   ACT    │─►│OBSERVE││
│  │ (Auto)   │   │ (Screen) │   │ (LLM +   │   │  (Tool)  │  │(Screen)│
│  │          │   │          │   │  Stream) │   │          │  │       ││
│  └──────────┘   └──────────┘   └──────────┘   └──────────┘  └───┬───┘│
│        ▲                                                        │    │
│        └────────────────────────────────────────────────────────┘    │
│           (Loop until complete_task or text-only response)           │
└──────────────────────────────────────────────────────────────────────┘
```

Auto-compaction is the first gate of every iteration. It only mutates history when
the token estimate exceeds the model's context-window threshold; otherwise it returns
`Skipped` and the turn proceeds normally. There is no hard turn-count cap on
production runs.

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
┌────────────────────────────────────────────────────────────┐
│  Turn 1: maybeCompact → Perceive → Think → Act → Observe   │──► MessageDelta
│  Turn 2: maybeCompact → Perceive → Think → Act → Observe   │──► MessageDelta
│  Turn N: ... (complete_task or text-only response)         │
└─────┬──────────────────────────────────────────────────────┘
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
- Call `compactor.maybeCompact(goal, historyManager)` at the top of every iteration
- Apply the 3-strike compaction circuit breaker (`MAX_CONSECUTIVE_COMPACTION_FAILURES = 3`)
- Honour the optional `evalTurnBudget` (eval-only safety net; `null` in production)
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
- Build warnings (loop detection, security) via `buildWarnings()` — no final-turn warning (no turn budget)
- Decide turn outcome via `decideTurnOutcome()` (`Continue`, `Complete`, `Error`, `Cancelled`)
- Re-throw `CancellationException` before generic `Exception` catch to prevent coroutine cancellation from being misclassified as a turn error

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

### Turn

→ See: `agent/Turn.kt`

Encapsulates a single LLM call with streaming and the reactive auto-compaction safety net.

**Responsibilities:**
- Execute the LLM call with prebuilt `inputItems`
- Generate tool schemas from `ToolRegistry` with `allowedToolNames` filtering
- Stream text and tool calls via `runStreaming()`
- Catch `ContextWindowExceededException` from the provider, call `Compactor.forceCompactNow`, and retry the streaming call **once** before propagating
- Recover tool calls from text output when LLM returns tool invocations as text (fallback)
- Mark completion when `complete_task` is present, or text-only response has no tool calls

### Runtime Types

→ See: `agent/AgentRuntimeTypes.kt`

Defines runtime control/result types:
- `AgentStopReason` — `GoalAchieved`, `UserRequested`, `TaskImpossible`, `Error`
- `TurnOutcome` — `Continue`, `Complete(message, success)`, `Error(recoverable)`, `Cancelled`
- `TurnRunnerState` — cross-turn state: `navigationState`
- `TurnExecutionResult` — `outcome` + `nextState`

There is no `MaxTurnsReached` stop reason; the agent runs until the goal is achieved, declared impossible, errors out, or is stopped by the user.

---

## Auto-Compaction

→ See: `history/Compactor.kt`, [memory.md](memory.md) (Conversation History & Compaction)

Auto-compaction replaces the previous `maxTurns` hard cap. Two layers:

### Proactive (per-turn, top of loop)

```kotlin
when (val outcome = compactor.maybeCompact(config.goal, services.historyManager)) {
    is CompactionOutcome.Compacted -> { /* reset failure counter, emit status */ }
    is CompactionOutcome.Failed    -> { /* increment counter, trip at 3 */ }
    Stale, Skipped, NothingToCompact -> { /* reset counter */ }
}
```

- Triggers when `historyTokens + staticOverheadTokens > contextWindow − reserveTokens`.
- Snapshots `(revision, items)`, summarizes the older prefix via a clean LLM call (no tools), then CAS-swaps with `historyManager.replaceAllIfRevision(rev, ...)`.
- `Stale` means a supplement landed during summarization; the half-baked summary is discarded and the next turn re-evaluates.
- Three consecutive `Failed` outcomes trip the circuit breaker → `AgentStopReason.Error("Auto-compaction failed 3× in a row …")`. Any non-failure resets the counter.

### Reactive (provider rejection)

```kotlin
try { llmClient.stream(...) }
catch (e: ContextWindowExceededException) {
    compactor.forceCompactNow(goal, historyManager)
    llmClient.stream(...)  // retry once
}
```

- `ContextWindowExceededException` is raised by `CloudStreamRetryRunner` when the provider returns HTTP 413 / `prompt_too_long` / `request_too_large` / Ollama "prompt too long; exceeded max context length".
- The exception is **not** retried by the cloud retry policy — it's not transient.
- `Turn.runStreaming` catches it once, forces a compaction with `keepRecentTokens` halved, then retries. A second failure propagates to the turn outcome.

### evalTurnBudget (eval safety net)

`SessionConfig.evalTurnBudget: Int?` defaults to `null` in production. The eval bridge sets it from `eval_turn_budget` (yaml-side: `max_turns:` key). When set and `turnCount >= evalTurnBudget`, the loop stops with `AgentStopReason.Error("Eval turn budget reached (...)")`. This guards against infinite eval loops; it is **not** a production turn cap and never appears in prompt text.

---

## Cognition Integration

→ See: `agent/cognition/`

- **Prompt layer**: `PromptBuilder` assembles History → Working Memory → Recalled Memory → App Skill → Current Observation input items. `COMPACTION_SUMMARY` items are rendered as user-role messages with a `[Context checkpoint from earlier work in this session]` prefix.
- **Memory layer**: `MemoryRecaller.recall(currentPackageName)` injects cross-session learnings per turn; `Agent.kt` auto-retains `[pitfall]` entries on failure.
- **Context layer**: `NavigationState` tracks recent screen signatures for loop detection.
- **Policy layer**: `TurnToolPolicy` arbitrates tool calls — keeps cognitive tools and screen-changing tools, defers `complete_task` when action tools exist. Navigation isolation (one screen-changing action per turn) is enforced at the prompt layer, not in code.
- **Loop guard**: `LoopDetectionPolicy` detects stable screens (near-identical for 5 consecutive turns at Jaccard >= 0.95) and emits a factual warning. No strategy suggestions — the LLM decides what to do.
- **Action descriptions**: `ActionDescriptionFormatter` produces human-readable action descriptions using the shared `ActionTarget` decoder.
- **Delegation summary**: `DelegationSummaryFormatter` produces a narrative summary of attempts surfaced back to a parent agent on subagent timeout.

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
| Context length exceeded | Handled separately by reactive compaction in `Turn.runStreaming` | n/a |
| Transient network error | Network error | Yes |
| Rate limit (429) | Rate limit | Yes |
| Other exceptions | Unknown | No |

Recoverable errors allow the loop to continue; fatal errors stop the agent.

---

## Quick Reference

### Stop Conditions

| Condition | Result |
|-----------|--------|
| `complete_task` selected without non-completion tool | `GoalAchieved` (success=true) or `TaskImpossible` (success=false) |
| Text-only response with no tool calls | `GoalAchieved` |
| User interrupt/stop signal | `UserRequested` |
| Unrecoverable error | `Error(message)` |
| 3 consecutive compaction failures | `Error("Auto-compaction failed 3× in a row …")` |
| `evalTurnBudget` exceeded (eval only) | `Error("Eval turn budget reached (...)")` |

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
- [Memory & History Compaction](memory.md) - HistoryManager + Compactor mechanics
- [Protocol](../protocol/overview.md) - Event types

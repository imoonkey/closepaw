# Multi-Tool Per Turn (KISS Design)

## 0. One-line stance
Current design throws away useful tool calls and over-optimizes for a past constraint. Stop dropping calls. Execute all calls, but execute them with sane ordering: UI-mutating calls serial, state-only calls concurrent.

## 1. Context from current codebase
This design is aligned with current runtime shape:
- `app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt` already returns all tool calls from one LLM turn.
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt` currently executes only `TurnToolPolicy.selectedToolCalls`.
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicy.kt` currently enforces effective single-tool execution.
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/WriteTodosTool.kt` and `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ScratchpadTool.kt` are state-only and do not depend on screen transitions.
- `app/src/main/kotlin/com/moonkey/androidagent/session/TodoState.kt` and `app/src/main/kotlin/com/moonkey/androidagent/session/ScratchpadState.kt` are thread-safe.

## 2. Goals
- Allow multiple tool calls in one turn.
- Keep system simple and readable.
- Support concurrent execution for non-UI tools (`write_todos`, `scratchpad`, `complete_task`).
- Keep UI safety: no parallel UI mutation.
- Remove old arbitration/drop behavior entirely.

## 3. Non-goals
- No backward compatibility path for old arbitration behavior.
- No generic scheduler framework.
- No dynamic dependency graph between tools.
- No attempt to parallelize multiple UI actions.

## 4. Core design

### 4.1 Replace "arbitration" with "classification"
Add a tiny classifier with 3 kinds:
- `UI_MUTATING`: tools that can change screen or depend on latest screen.
- `STATE_ONLY`: tools that only update/read in-memory/session state.
- `COMPLETION`: `complete_task`.

Default unknown tools to `UI_MUTATING` (safe fallback).

Initial mapping:
- `UI_MUTATING`: `mobile_action`, `open_app`, `system_button`, `wait`, `delegate_task`, unknown.
- `STATE_ONLY`: `write_todos`, `scratchpad`.
- `COMPLETION`: `complete_task`.

### 4.2 Execution plan per turn
Given `turnResult.toolCalls`, build:
- `uiCalls` (ordered)
- `stateCalls` (ordered)
- `completionCalls` (ordered)

No dropping. No max-tools policy.

### 4.3 Execution semantics
- Execute `stateCalls` concurrently (structured concurrency with `coroutineScope` + `async`).
- Execute `uiCalls` serially, preserving current snapshot-update behavior.
- Execute `completionCalls` last (serial, cheap, deterministic).

Why completion last:
- Completion is a declaration, not an action.
- Running it last keeps trace/history readable and avoids “completed before action finished” nonsense.

### 4.4 Completion decision
Replace current arbitration-based completion rule with result-based rule:
- `shouldComplete = completionCalls.isNotEmpty() && nonCompletionCalls.all { success }`
- If no tool calls and assistant text exists, keep existing fallback completion behavior.

Summary extraction order:
- Last `complete_task.answer`
- Last `complete_task.summary`
- Assistant text
- `"Goal achieved"`

## 5. Required code changes

### 5.1 New policy object
Create `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolExecutionPolicy.kt`:
- `classify(toolName)`
- `buildPlan(toolCalls)`
- `decideCompletion(turnResult, executionResults)`

Keep it small and dumb. No inheritance tree.

### 5.2 Simplify AgentTurnRunner
In `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt`:
- Remove dependency on `TurnToolPolicy` arbitration.
- Replace `PlanningPhaseResult` to carry `TurnResult` + `TurnExecutionPlan`.
- Refactor `executeActions` into:
  - `executeUiCallsSerial(...)`
  - `executeStateCallsConcurrent(...)`
  - `executeCompletionCalls(...)`
- Keep `executeSingleToolCall(...)` for UI path.
- Add a small shared result model (per call success/failure + formatted output + observation).

### 5.3 Remove obsolete trace model
Deprecate and remove:
- `app/src/main/kotlin/com/moonkey/androidagent/trace/ArbitrationTrace.kt`
- `tool_arbitration` trace emission in `AgentTrace`
- Dropped-call warnings in `AgentTurnRunner.emitArbitrationWarnings`

Optional replacement trace (simple):
- Emit one `tool_execution_plan` event: counts of `ui/state/completion`.

### 5.4 Prompt updates (soft rule only)
Update system prompts in:
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/ExecutorAgentDef.kt`

New wording:
- Strongly prefer at most one UI-mutating action per turn.
- It is OK to batch state tools in the same turn.
- Do not issue multiple UI actions unless absolutely necessary.

No hard runtime rejection.

## 6. Data and ordering correctness
History manager is not designed for concurrent mutation. So do this cleanly:
- Record `FunctionCall` items in original LLM order before execution starts.
- Execute according to plan (concurrent where allowed).
- Record `FunctionCallOutput` in original LLM order after all results are available.

This keeps prompt history stable and readable.

## 7. Failure handling
- A failed state tool does not cancel UI execution in the same turn.
- A failed UI tool does not cancel already-running state tools.
- Completion requires all non-completion calls in the turn to succeed.
- Per-call errors are still written to history and trace.

## 8. What gets deprecated immediately
Without backward compatibility baggage:
- `TurnToolPolicy` arbitration behavior.
- `ToolArbitrationResult`, dropped-tool reasons, and related tests.
- “multiple tools returned, executing only one” warning path.

## 9. Tests

### 9.1 Replace policy tests
Replace `app/src/test/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicyTest.kt` with tests for new execution policy:
- classification correctness
- unknown tool defaults to UI
- plan preserves original order inside each bucket
- completion decision based on execution results

### 9.2 Runner tests
Add focused tests around `AgentTurnRunner` execution behavior:
- executes `write_todos + scratchpad + mobile_action` in one turn
- executes multiple `scratchpad(write)` calls in same turn
- does not drop tools when `complete_task` is mixed with other tools
- marks turn complete only when completion call exists and non-completion calls succeeded

## 10. Complexity estimate
- Policy replacement: Small.
- Runner refactor: Medium (largest part).
- Trace cleanup: Small.
- Prompt edits + tests: Small/Medium.

Total: 1 solid implementation pass.

## 11. Why this is the right design
- It directly matches the real constraint: UI state mutation is the dangerous part, not metadata updates.
- It removes accidental complexity from arbitration/drop-reason plumbing.
- It keeps code readable: one classifier, one plan, one executor.
- It avoids framework fever and still gives real concurrency where it matters.

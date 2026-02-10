# Multi-Tool Per Turn

## The Problem

Right now `TurnToolPolicy.arbitrateToolCalls()` hard-gates to exactly one tool call per turn. This was fine when every tool was a mobile action that could mutate screen state. But now we have cognitive tools (`write_todos`, `scratchpad`) that don't touch the screen at all. The LLM wants to write a todo update AND click a button in the same turn, but we throw away one of them. That's stupid. We're wasting a full LLM round-trip for a zero-cost in-memory write.

## The Insight

There are exactly two categories of tools:

| Category | Tools | Effect |
|----------|-------|--------|
| **Screen-changing** | `mobile_action`, `open_app`, `system_button`, `wait` | Mutates UI state, returns `ToolObservation.ScreenState` |
| **Cognitive** | `write_todos`, `scratchpad`, `complete_task`, `delegate_task` | In-memory or control-flow, returns `ToolObservation.TextOutput` |

The invariant is simple: **at most one screen-changing tool per turn**. Everything else can ride along.

The codebase already knows this distinction implicitly. `ToolObservation.ScreenState` vs `ToolObservation.TextOutput`. We just never gave it a name.

## The Design

Three changes. No new files. No new abstractions. No parallelism. No async magic.

### 1. Add `isScreenChanging` to `ToolName`

`ToolName.kt` already has all the known tools as sealed class members. Add one property:

```kotlin
sealed class ToolName(...) {
    val isScreenChanging: Boolean get() = when (this) {
        MobileAction, OpenApp, SystemButton, Wait -> true
        CompleteTask, WriteTodos, Scratchpad, DelegateTask -> false
        is Unknown -> false
    }
    // ... rest unchanged
}
```

That's it. One `when` expression. The source of truth for "does this tool change the screen" lives right next to the tool names where it belongs.

No enums. No `ToolCategory` interface. No `ToolClassifier` strategy pattern. Just a boolean on the sealed class that already exists.

### 2. Rewrite `TurnToolPolicy.arbitrateToolCalls()`

Current code picks one tool and drops everything else. New code:

```kotlin
fun arbitrateToolCalls(toolCalls: List<ToolCallRequest>): ToolArbitrationResult {
    if (toolCalls.isEmpty()) return emptyResult()

    val completionCall = toolCalls.find { it.name == COMPLETE_TASK_TOOL }
    val screenCalls = toolCalls.filter {
        it.name != COMPLETE_TASK_TOOL && ToolName.from(it.name).isScreenChanging
    }
    val cognitiveCalls = toolCalls.filter {
        it.name != COMPLETE_TASK_TOOL && !ToolName.from(it.name).isScreenChanging
    }

    // Keep all cognitive tools. At most one screen-changing tool.
    val selectedScreen = screenCalls.firstOrNull()
    // Completion is kept only when no screen action competes.
    val selectedCompletion = if (selectedScreen == null) completionCall else null

    val selected = cognitiveCalls +
        listOfNotNull(selectedScreen) +
        listOfNotNull(selectedCompletion)
    val dropped = toolCalls.filterNot { it in selected }

    return ToolArbitrationResult(
        selectedToolCalls = selected,
        hasCompletionTool = completionCall != null,
        hasScreenAction = selectedScreen != null,
        droppedToolCalls = dropped
    )
}
```

Rules, in plain English:
1. **All cognitive tools**: always kept. They're cheap and stateless.
2. **One screen-changing tool**: first one wins, rest dropped.
3. **`complete_task`**: kept only when there's no screen-changing tool. Same deferral logic as before, because if the LLM wants to click something AND complete, we should click first, observe, then let the LLM decide whether to complete next turn.

Execution order in the selected list: **cognitive first, screen-changing last**. This matters because `AgentTurnRunner.executeActions()` tracks `actionForNextTurn` as the last tool's classification. By putting the mobile action last, loop detection sees the correct signature.

### 3. Update `ToolArbitrationResult`

The old struct has `selectedTool: ToolCallRequest?` (singular) and `hasNonCompletionTool: Boolean`. Both are artifacts of the single-tool model. Replace with something that makes sense:

```kotlin
internal data class ToolArbitrationResult(
    val selectedToolCalls: List<ToolCallRequest>,
    val hasCompletionTool: Boolean,
    val hasScreenAction: Boolean,
    val droppedToolCalls: List<ToolCallRequest>
)
```

- `selectedTool` (singular) → removed. Callers iterate `selectedToolCalls`.
- `hasNonCompletionTool` → replaced by `hasScreenAction`. The completion deferral question is "did we keep a screen action?", not "did we keep any non-completion tool?" (cognitive tools alongside completion is fine).

### 4. Update `decideCompletion()`

```kotlin
fun decideCompletion(
    turnResult: TurnResult,
    arbitration: ToolArbitrationResult
): CompletionDecision {
    val shouldComplete = turnResult.isComplete && !arbitration.hasScreenAction
    if (!shouldComplete) return CompletionDecision(shouldComplete = false, summary = null)
    // ... extract summary same as before
}
```

The only change: `!arbitration.hasNonCompletionTool` → `!arbitration.hasScreenAction`.

Meaning: cognitive tools (`write_todos`, `scratchpad`) alongside `complete_task` no longer defer completion. Which is correct — writing a final todo update and completing in the same turn is fine.

### 5. Update `AgentTurnRunner` callers

Two spots reference the old `selectedTool` field:

**`emitArbitrationWarnings()`** — update to reflect multi-tool reality:
```kotlin
private suspend fun emitArbitrationWarnings(...) {
    val droppedCount = arbitration.droppedToolCalls.size
    if (droppedCount > 0) {
        val kept = arbitration.selectedToolCalls.map { it.name }
        val dropped = arbitration.droppedToolCalls.map { it.name }
        Log.w(TAG, "Turn $turnNumber: Kept $kept, dropped $dropped")
        eventDispatcher.status("⚠️ Dropped $droppedCount tool call(s): $dropped")
    }
}
```

**`buildArbitrationDecision()`** — `ArbitrationDecision.selectedTool` in the trace struct becomes `selectedTools: List<String>` or we just keep `selectedToolCount` which already exists.

### 6. Prompt hint

Add one line to each `AgentDef` system prompt under `## Tool Calling`:

```
- You may call multiple tools per turn. Constraint: at most one screen-affecting action (mobile_action, open_app, system_button, wait) per turn. Cognitive tools (write_todos, scratchpad) can be combined freely.
```

And remove the existing "Execute ONE UI action per turn" language.

## What We're NOT Doing

**No parallel execution.** Cognitive tools are in-memory microsecond operations. The overhead of `coroutineScope { launch { ... } }` is more code than the time saved. Sequential loop is fine. If `delegate_task` (which is slow) needs parallelism someday, that's a separate feature.

**No `ToolCategory` enum or interface.** `isScreenChanging` on `ToolName` is the entire classification. One boolean. Adding a category abstraction layer for two categories is textbook over-engineering.

**No execution ordering strategy.** We hardcode "cognitive first, screen last" in the arbitration result list. No `ExecutionOrderStrategy` pattern. No priority queues. The list order IS the execution order.

**No changes to `Turn.kt`, `ToolRouter.kt`, `ToolRegistry.kt`, or `ToolSpec.kt`.** The LLM layer, routing layer, and tool definitions are already multi-tool capable. The only bottleneck was the policy layer throwing away tool calls. We fix the policy layer. Done.

## Files Changed

| File | Change |
|------|--------|
| `tool/ToolName.kt` | Add `isScreenChanging` property |
| `agent/cognition/policy/TurnToolPolicy.kt` | Rewrite `arbitrateToolCalls()`, update `decideCompletion()`, reshape `ToolArbitrationResult` |
| `agent/AgentTurnRunner.kt` | Update `emitArbitrationWarnings()` and `buildArbitrationDecision()` for new `ToolArbitrationResult` shape |
| `trace/ArbitrationTrace.kt` | Update `ArbitrationDecision` to replace `selectedTool` with `selectedToolCount` (already exists) or list |
| `agent/definition/StandaloneAgentDef.kt` | Update prompt: multi-tool hint |
| `agent/definition/ExecutorAgentDef.kt` | Update prompt: multi-tool hint |
| `agent/definition/PlannerAgentDef.kt` | Update prompt: multi-tool hint (if it has tool calling) |
| Tests: `TurnToolPolicyTest.kt` | New test cases for multi-tool arbitration |

## Data Flow (Before → After)

**Before:**
```
LLM returns: [write_todos, mobile_action, scratchpad]
                              ↓
TurnToolPolicy: pick first non-completion → mobile_action
                              ↓
selectedToolCalls: [mobile_action]        dropped: [write_todos, scratchpad]
                              ↓
executeActions: loop over 1 tool → done
```

**After:**
```
LLM returns: [write_todos, mobile_action, scratchpad]
                              ↓
TurnToolPolicy: keep cognitive + 1 screen
                              ↓
selectedToolCalls: [write_todos, scratchpad, mobile_action]    dropped: []
                              ↓
executeActions: loop over 3 tools sequentially → done
```

## Edge Cases

**LLM sends 2 mobile actions + 2 cognitive tools:**
Keep both cognitive + first mobile. Drop second mobile. Same as before but we no longer drop the cognitive ones.

**LLM sends only cognitive tools (no mobile action):**
Keep all. No screen state change. Turn continues.

**LLM sends `complete_task` + cognitive tools (no mobile action):**
Keep all. Cognitive tools execute, then completion fires. Task ends. This is the new behavior — previously completion was deferred if ANY non-completion tool existed. Now it's only deferred if a screen-changing tool exists.

**LLM sends `complete_task` + mobile action + cognitive tools:**
Keep cognitive + mobile. Drop `complete_task` (defer). Same logic as before for the completion part.

**LLM sends `delegate_task` alongside mobile actions:**
`delegate_task` is cognitive (returns `TextOutput`). Kept alongside one mobile action. Note: `delegate_task` runs a sub-agent that may change screen state, but from the parent's perspective, the delegation is a self-contained operation. After all tools execute, the next turn will re-capture the screen anyway.

**Empty tool calls:**
Return empty result. Same as before.

## Testing Strategy

Unit tests on `TurnToolPolicy`:
1. Single cognitive tool → kept
2. Single screen tool → kept
3. Multiple cognitive tools → all kept
4. Multiple screen tools → first kept, rest dropped
5. Mixed cognitive + screen → all cognitive + first screen kept
6. `complete_task` alone → kept, completion fires
7. `complete_task` + cognitive → all kept, completion fires
8. `complete_task` + screen → screen kept, completion deferred
9. `complete_task` + cognitive + screen → cognitive + screen kept, completion deferred
10. Execution order: cognitive tools appear before screen tools in `selectedToolCalls`

The existing `AgentTurnRunner` integration doesn't need new tests — it already loops over `selectedToolCalls`. We're just giving it more items to loop over.

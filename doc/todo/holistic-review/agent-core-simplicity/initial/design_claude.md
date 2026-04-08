# Agent Core Simplicity Review

Ultra-thorough KISS review of `app/src/main/kotlin/com/moonkey/androidagent/agent/` (24 files).

---

## Perspective A: Architecture Simplicity

### Overall Structure Assessment

The module has a clean layered architecture:

```
Agent (loop owner)
  -> AgentTurnRunner (turn orchestrator)
       -> TurnPlanningPhaseRunner (LLM call)
       -> TurnExecutionPhaseRunner (tool execution)
  -> policies (loop detection, step budget, tool arbitration)
  -> cognition (prompt building, navigation state)
  -> definition (agent persona configs)
  -> subagent (delegation runner)
```

This is a reasonable decomposition. The ReAct loop reads clearly: capture screen, plan (LLM call), execute actions. The separation between planning and execution phases is well-motivated since they have genuinely different concerns.

### A-1: Definition System Over-Engineering

**Files:** `definition/AgentDef.kt`, `AgentDefRegistry.kt`, `PlannerAgentDef.kt`, `ExecutorAgentDef.kt`, `StandaloneAgentDef.kt`

The definition system is an abstract class hierarchy (`AgentDef`) with a registry (`AgentDefRegistry`) that resolves persona by mode. This exists to support two modes: BASIC (Standalone) and PRO (Planner+Executor).

**Justified?** Partially. The two modes are a real product feature exposed in settings UI. The registry dispatch in `SessionAgentRunner.kt:51` is the single call site. However:

- `AgentDef` is abstract with 5 abstract properties -- a simple `data class` or even a top-level function returning a config would suffice.
- `requiresDelegationToolRegistration` (AgentDef.kt:15) is only true for `PlannerAgentDef`. This boolean flag only controls whether `delegate_task` tool is registered -- that logic could be inlined into `SessionAgentRunner` as `if (mode == PRO) registerDelegation()`.
- The registry is a 14-line object with two functions. The indirection adds cognitive overhead without adding testability (the registry itself is trivially testable).

**Verdict:** The definition system is over-abstracted for two concrete configurations. The abstract class plus registry pattern would be justified if there were 5+ agent types or if new types were expected frequently. With exactly 3 known objects, the same information could live in a single file with sealed class or enum + companion factory.

### A-2: ExecutorStepPolicy in AgentTurnRunner -- Naming/Scoping Confusion

**File:** `AgentTurnRunner.kt:47-48`

```kotlin
private val executorStepPolicy by lazy {
    ExecutorStepPolicy(maxSteps = config.maxTurns, narrativeSummaryOnLimit = true)
}
```

The class is named `ExecutorStepPolicy` and was designed for "delegated executor runs" (`ExecutorStepPolicy.kt:6` doc comment). Yet it is instantiated in `AgentTurnRunner` (which runs the *main* agent, not a sub-agent) and also in `SubAgentRunner` (where it makes sense). In AgentTurnRunner, it serves as a generic "approaching turn limit" warning generator.

**Impact:** The name `ExecutorStepPolicy` in a non-executor context is confusing. This is really a `TurnBudgetPolicy` that emits warnings as turn count approaches the limit.

### A-3: Dual cancellation signals

**File:** `Agent.kt:25-26,35-36`

```kotlin
private val cancellationSignal: CompletableDeferred<AgentStopReason>
...
private val stopRequested = AtomicBoolean(false)
```

The agent has TWO independent cancellation mechanisms: `CompletableDeferred<AgentStopReason>` and `AtomicBoolean`. Both are checked in `shouldContinue()` (line 197-199), and both are passed to `AgentTurnRunner`. This is redundant. A single `CompletableDeferred` is sufficient -- `stopRequested` adds nothing since `stop()` could complete the deferred instead.

### A-4: eventEmitter passed alongside eventDispatcher

**Files:** `AgentTurnRunner.kt:31` (eventEmitter), `AgentTurnRunner.kt:29` (eventDispatcher)

`AgentTurnRunner` receives both `eventDispatcher: AgentEventDispatcher` and `eventEmitter: suspend (AgentEvent) -> Unit`. The dispatcher wraps the emitter. The raw emitter is only forwarded to `TurnExecutionPhaseRunner` where it is used directly for `ActionExecuted` events (line 148-157). The dispatcher provides structured helpers. Having both creates ambiguity about when to use which.

Same pattern in `TurnExecutionPhaseRunner.kt:25-26` -- receives both. The raw emitter is used once (line 148) and once for approval events (line 167).

### A-5: SubAgentRunner.kt is a 288-line mega-file

**File:** `subagent/SubAgentRunner.kt`

This file contains 7 distinct types: `AgentDefinition`, `SubAgentRequest`, `SubAgentResult`, `ExecutorAgent`, `AgentRegistry`, `SubAgentRunner` (interface), and `IsolatedSubAgentRunner`. Plus 3 private helpers. It is the densest file in the module and combines data types, a registry, a runner interface, a concrete runner, and utility functions.

---

## Perspective B: Code-Level Simplicity

### B-1: Dead fields in NavigationState

**File:** `cognition/context/NavigationState.kt:19-20`

```kotlin
val consecutiveScrollActions: Int = 0,
val recentActions: List<String> = emptyList()
```

These fields are computed every turn in `advance()` but never read outside of `NavigationState` itself. The `LoopDetectionPolicy` only reads `recentSignatures`. The doc comment at `LoopDetectionPolicy.kt:13` explicitly says: "All advisory warnings (cycle detection, tool dominance, scroll spam, action repetition) have been removed." These were the consumers of `consecutiveScrollActions` and `recentActions`.

Both fields are dead code -- computed but never consumed in production. Only referenced in test assertions (`NavigationStateTest.kt:25,35,38`).

### B-2: Dead field: ScreenSignature.fingerprint

**File:** `cognition/context/NavigationState.kt:44,69-70`

```kotlin
internal data class ScreenSignature(val fingerprint: String, val tokens: Set<String>)
...
val fingerprint = tokens.joinToString(separator = "|").hashCode().toString()
```

`fingerprint` is computed every turn but never used in production code. The `similarityTo()` function only uses `tokens`. The fingerprint is only accessed in test assertions (`NavigationStateTest.kt:26-27`). This is a string representation of a hash that was presumably used for exact-match checks that were removed.

### B-3: Unused LoopWarningSeverity.CRITICAL

**File:** `cognition/context/NavigationState.kt:55-58`

```kotlin
internal enum class LoopWarningSeverity {
    WARNING,
    CRITICAL
}
```

`CRITICAL` is never used anywhere in the codebase. Only `WARNING` is emitted in `LoopDetectionPolicy.kt:43`. The severity field is set but never branched on -- `LoopDetectionResult.warning` is treated as a boolean "exists or not" everywhere it is consumed.

### B-4: ExecutorStepDecision.WarnApproaching is consumed but discarded

**File:** `ExecutorStepPolicy.kt:13`

In `AgentTurnRunner.buildWarnings()` (line 236-243), only `ForceStop` is handled:

```kotlin
private fun buildWarnings(..., stepDecision: ExecutorStepDecision): List<String> = buildList {
    loopResult.warning?.let { add(...) }
    if (stepDecision is ExecutorStepDecision.ForceStop) {
        add("FINAL TURN...")
    }
}
```

`WarnApproaching` is evaluated but produces no warning. The three-state sealed interface effectively operates as a two-state boolean (`ForceStop` or not). The intermediate warning state was presumably planned for a "you have 2 turns left" message that was never implemented.

### B-5: Turn.kt text recovery complexity (lines 215-346)

**File:** `Turn.kt:215-346` (~130 lines)

The text recovery system (`recoverToolCallFromText`, `parseObjectWrappedToolCall`, `findInlineToolMarkers`, `extractBalancedJsonObject`, `stripMarkdownCodeFence`) is a significant chunk of complexity that handles edge cases where the LLM emits tool calls as text instead of structured function calls. This includes:

1. Object-wrapped recovery: parsing `{"name": "tool", "arguments": {...}}`
2. Inline marker recovery: scanning for `tool_name{...}` patterns in text
3. Balanced JSON extraction: manual brace-counting parser
4. Markdown fence stripping

This is 130 lines of defensive parsing. The question is whether modern function-calling LLMs still produce these patterns. The `InlineToolMarker` and `extractBalancedJsonObject` are particularly complex. If this recovery path fires frequently, it indicates a model/prompt issue. If it rarely fires, this is over-engineering for a rare failure mode.

### B-6: PreTurnContext as a private data class in AgentTurnRunner

**File:** `AgentTurnRunner.kt:40-45`

```kotlin
private data class PreTurnContext(
    val snapshot: ScreenSnapshot,
    val currentPackageName: String?,
    val appTier: AppTier = AppTier.CAUTIOUS,
    val securityWarnings: List<String> = emptyList()
)
```

`appTier` is set in `capturePreTurnSnapshot()` but never read after that point. The tier-to-warnings conversion happens inline (`if (tier == AppTier.BLOCKED) ...`), and the tier is then stored in `PreTurnContext.appTier` but never accessed again. This field is dead.

### B-7: ActionDescriptionFormatter long-press + swipe formatting complexity

**File:** `ActionDescriptionFormatter.kt:44-123`

The `formatMobileAction` function handles click, long_press, type, and swipe. The swipe handler alone is 38 lines with 4 different formatting paths (start/end coordinates, direction-only, with target element, without). The type handler has `hasInputText` branching to handle two different parameter naming conventions (`input_text`/`text` vs `text`/`target_text`).

This is display-only formatting and the complexity reflects accumulated parameter format drift in the mobile_action tool. Not a bug, but worth noting that the formatter has to handle 2+ naming conventions for the same semantic (target element), which suggests the tool's parameter schema could be simplified upstream.

### B-8: Redundant duplicate computation in TurnToolPolicy

**File:** `cognition/policy/TurnToolPolicy.kt:55-56`

```kotlin
val hasCompletionTool = toolCalls.any { it.name == COMPLETE_TASK_TOOL }
val completionCall = toolCalls.find { it.name == COMPLETE_TASK_TOOL }
```

`any` then `find` traverses the list twice for the same predicate. Could be a single `find` with a null check.

### B-9: Agent.kt auto-retain memory logic inline in main loop

**File:** `Agent.kt:106-116`

The auto-retain failure memory logic is 10 lines embedded in the main agent loop's `TurnOutcome.Complete` handler. This is a side-effect concern (write a memory note on failure) mixed into the control-flow logic (decide what to do on completion). It is not complex, but it breaks the single-responsibility of the loop body.

### B-10: `narrativeSummaryOnLimit` parameter always true

**File:** `ExecutorStepPolicy.kt:26`

`narrativeSummaryOnLimit` is always `true` in both call sites:
- `AgentTurnRunner.kt:48`: `narrativeSummaryOnLimit = true`
- `SubAgentRunner.kt:179-180`: `definition.narrativeSummaryOnLimit` which comes from `AgentDefinition.narrativeSummaryOnLimit` defaulting to `true` (line 36), and the only instantiation (`ExecutorAgent.definition`) does not override it.

This parameter is never false in production. It is a premature configuration option.

### B-11: TurnPlanningPhaseRunner creates new PromptBuilder every turn

**File:** `TurnPlanningPhaseRunner.kt:64-70`

```kotlin
val promptBuilder = PromptBuilder(
    historyManager = services.historyManager,
    sessionState = services.sessionState,
    supportsVision = model.supportsVision,
    perceptionConfig = services.config.perceptionConfig
)
```

A new `PromptBuilder` is created on every call to `runPlanningPhase`. Since `supportsVision` depends on the resolved model (which could theoretically change per-turn), this is technically correct. But in practice the model does not change per turn. This could be lazy-initialized if model resolution were stable.

### B-12: Hardcoded delay in TurnExecutionPhaseRunner

**File:** `TurnExecutionPhaseRunner.kt:42,216`

```kotlin
delay(200)   // line 42, before execution
delay(500)   // line 216, post-action observation capture
```

Two magic-number delays. The 200ms pre-execution delay has no comment explaining its purpose. The 500ms post-action delay is for UI settling but is not configurable (unlike `config.uiSettleDelayMs` used in Agent.kt).

---

## Cross-Cutting Findings

### C-1: Tracing/logging density

Every file has extensive `Log.d`/`Log.w` calls. `AgentTurnRunner` has ~15 log statements, `TurnPlanningPhaseRunner` has ~10, `TurnExecutionPhaseRunner` has ~12. This is appropriate for a debugging-heavy domain (Android automation) but contributes to line count. Not a simplification target -- logging is earned complexity here.

### C-2: Well-applied KISS patterns

Some things the codebase gets right:
- `TurnRunnerState` is minimal (2 fields) and immutable -- good.
- `TurnOutcome` sealed class is clean with exactly the needed variants.
- `AgentStopReason` is similarly well-scoped.
- `LoopDetectionPolicy` is genuinely simple after the removal of false-positive-prone heuristics. The comment at line 13 is a good example of documenting removed complexity.
- `TurnErrorClassifier` is a clean isolated utility.
- `AppSkillRepository` uses a clean interface/implementation split.
- `PromptBuilder` has clear section boundaries with good comments.

### C-3: The definition system IS used but the abstraction cost is high

The Planner/Executor/Standalone split is exercised in production:
- `SessionAgentRunner.kt:51` resolves the main agent def
- `SubAgentRunner.kt:62` resolves the executor def
- Settings UI exposes BASIC/PRO mode selection

The cost: 5 files (AgentDef.kt, AgentDefRegistry.kt, PlannerAgentDef.kt, ExecutorAgentDef.kt, StandaloneAgentDef.kt) plus `AgentExecutionRole` enum in `AgentExecutionConfig.kt`. The benefit: clean separation of prompt/tools/role per persona. The question is whether this separation needs an abstract class hierarchy or could be achieved with a simpler sealed class + companion object pattern.

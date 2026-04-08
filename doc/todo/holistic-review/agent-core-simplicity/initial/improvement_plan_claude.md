# Agent Core Simplification Plan

Prioritized actions from the simplicity review. Ordered by impact/effort ratio (highest first).

---

## Priority 1: Dead Code Removal (Low effort, zero risk)

### 1a. Remove dead fields from NavigationState

**File:** `agent/cognition/context/NavigationState.kt`
**Lines:** 19-20, 26-39

Remove `consecutiveScrollActions` and `recentActions` from `NavigationState`. These are computed every turn but never read in production. The consumers (scroll-spam detection, action-repetition detection) were intentionally removed per the comment in `LoopDetectionPolicy.kt:13`. Update `advance()` to only track `recentSignatures`.

**Rationale:** Dead code that runs every turn. Removing it simplifies the data class, the `advance()` function, and eliminates unnecessary list allocations.

**Affected tests:** `NavigationStateTest.kt` assertions on these fields -- update or remove.

### 1b. Remove ScreenSignature.fingerprint

**File:** `agent/cognition/context/NavigationState.kt`
**Lines:** 44, 69-70

Remove `fingerprint` from `ScreenSignature`. Only `tokens` is used by `similarityTo()`. The fingerprint string is computed via hash every turn and discarded.

**Rationale:** Unused field with per-turn computation cost.

**Affected tests:** `NavigationStateTest.kt:26-27` -- remove fingerprint assertions.

### 1c. Remove LoopWarningSeverity.CRITICAL

**File:** `agent/cognition/context/NavigationState.kt`
**Lines:** 55-58

Remove `CRITICAL` from the enum. Only `WARNING` is used. If severity is no longer branched on (it is not), consider removing the severity field entirely from `LoopWarning` and treating loop warnings as plain strings.

**Rationale:** Dead enum variant. The severity was designed for escalation logic that does not exist.

### 1d. Remove PreTurnContext.appTier

**File:** `agent/AgentTurnRunner.kt`
**Lines:** 43

Remove `appTier` from `PreTurnContext`. It is set but never read after construction. The tier is used inline to produce `securityWarnings`, then discarded.

**Rationale:** Dead field.

---

## Priority 2: Eliminate Redundancy (Low-medium effort, low risk)

### 2a. Unify cancellation to single CompletableDeferred

**File:** `agent/Agent.kt`
**Lines:** 25-26, 35-36, 192-195, 197-199

Remove `stopRequested: AtomicBoolean`. In `stop()`, complete the `cancellationSignal` deferred instead. Adjust `shouldContinue()` to only check `cancellationSignal.isCompleted`. Remove `stopRequested` from `AgentTurnRunner` constructor.

**Rationale:** Two independent cancellation mechanisms for the same purpose. A single deferred is simpler and eliminates the possibility of them disagreeing.

**Risk:** Low -- `stop()` already sets `pauseState.value = false` alongside `stopRequested`. The deferred covers all cases.

### 2b. Remove narrativeSummaryOnLimit parameter

**File:** `agent/cognition/policy/ExecutorStepPolicy.kt:26`, `agent/subagent/SubAgentRunner.kt:36`

Hardcode `narrativeSummaryOnLimit = true` inside `ExecutorStepPolicy`. Remove the parameter from `AgentDefinition`. Remove the conditional in `evaluate()`.

**Rationale:** Always true in all call sites. Premature configuration.

### 2c. Collapse ExecutorStepDecision to two states

**File:** `agent/cognition/policy/ExecutorStepPolicy.kt`

Remove `WarnApproaching`. It is evaluated but produces no effect in any consumer. The policy becomes a simple boolean: either `ForceStop` (with narrative) or `Continue`.

**Rationale:** `WarnApproaching` exists but has no consumer. If an approaching-limit warning is needed in the future, it can be added then.

### 2d. Fix duplicate list traversal in TurnToolPolicy

**File:** `agent/cognition/policy/TurnToolPolicy.kt:55-56`

Replace `any` + `find` with a single `find` and null check:

```kotlin
val completionCall = toolCalls.find { it.name == COMPLETE_TASK_TOOL }
val hasCompletionTool = completionCall != null
```

**Rationale:** Minor but free -- eliminates redundant traversal.

---

## Priority 3: Structural Simplification (Medium effort, medium risk)

### 3a. Consolidate the definition system into a single file

**Files:** `definition/AgentDef.kt`, `AgentDefRegistry.kt`, `PlannerAgentDef.kt`, `ExecutorAgentDef.kt`, `StandaloneAgentDef.kt` (5 files)

Replace the abstract class + 3 objects + registry with a single file containing:
- A `data class AgentDef(id, executionRole, systemPrompt, allowedTools)` (drop `requiresDelegationToolRegistration`)
- Three top-level `val` definitions (STANDALONE, PLANNER, EXECUTOR)
- A `resolveMainDef(mode: AgentMode): AgentDef` function

Move `requiresDelegationToolRegistration` logic into `SessionAgentRunner` as `if (mode == PRO)`.

**Rationale:** 5 files / ~210 lines for 3 static configurations. A single ~80 line file with data class instances achieves the same with lower cognitive overhead. New developers read one file instead of navigating 5.

**Risk:** Medium -- requires updating imports across consumers and tests. The current tests are trivial (`AgentDefRegistryTest`, `AgentDefTest`) and would simplify.

### 3b. Eliminate raw eventEmitter passthrough

**Files:** `AgentTurnRunner.kt`, `TurnExecutionPhaseRunner.kt`

Stop passing `eventEmitter: suspend (AgentEvent) -> Unit` alongside `eventDispatcher`. Add the missing event-emit methods to `AgentEventDispatcher` (e.g., `actionExecuted(...)`, `approvalRequired(...)`) and use the dispatcher everywhere.

**Rationale:** Two paths to emit events creates confusion about which to use. The dispatcher is the intended abstraction; the raw lambda should not leak.

### 3c. Extract SubAgentRunner types into separate files

**File:** `agent/subagent/SubAgentRunner.kt` (288 lines, 7+ types)

Split into:
- `SubAgentTypes.kt`: `AgentDefinition`, `SubAgentRequest`, `SubAgentResult`
- `AgentRegistry.kt`: `AgentRegistry`, `ExecutorAgent`
- `SubAgentRunner.kt`: `SubAgentRunner` interface + `IsolatedSubAgentRunner`

**Rationale:** The file exceeds the 400-line soft limit in spirit (it has 288 lines but 7 distinct types). Splitting by concern makes each piece independently readable.

---

## Priority 4: Consider for Future (Higher effort, needs measurement)

### 4a. Audit Turn.kt text recovery necessity

**File:** `agent/Turn.kt:215-346`

Add telemetry/logging to track how often `recoverToolCallFromText` successfully recovers a tool call vs returning null vs detecting a malformed marker. If recovery fires less than 1% of turns, consider removing the inline-marker recovery path (keep only the simpler object-wrapped recovery).

**Rationale:** 130 lines of defensive parsing for LLM misbehavior. Modern function-calling models (GPT-4+) rarely need this. But removing it without data could cause regressions on weaker models.

**Action:** Add a counter/log, review after one eval cycle.

### 4b. Make post-action delay configurable

**File:** `agent/TurnExecutionPhaseRunner.kt:42,216`

Replace `delay(200)` and `delay(500)` with config values or at minimum named constants with comments explaining why each delay exists.

**Rationale:** Magic numbers that affect timing behavior. The 500ms delay for UI settling should probably be `config.uiSettleDelayMs` for consistency.

### 4c. Rename ExecutorStepPolicy to TurnBudgetPolicy

**File:** `agent/cognition/policy/ExecutorStepPolicy.kt`

After 2b/2c, rename to `TurnBudgetPolicy` since it is used in both the main agent and sub-agent contexts. The "Executor" prefix is misleading when used in the main agent turn runner.

**Rationale:** Name should reflect actual usage scope.

---

## Summary

| Priority | Items | Estimated Lines Removed | Risk |
|----------|-------|------------------------|------|
| P1: Dead code | 4 items | ~30 lines | None |
| P2: Redundancy | 4 items | ~25 lines | Low |
| P3: Structure | 3 items | Net -80 lines (consolidation) | Medium |
| P4: Future | 3 items | TBD (needs measurement) | Varies |

**Net effect of P1+P2+P3:** Remove ~135 lines and 4 files while preserving all production behavior.

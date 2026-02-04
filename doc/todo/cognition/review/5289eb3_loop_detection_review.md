# Review: Loop Detection & Executor Failure Recovery (5289eb3)

## Summary

This commit implements Phase 1 of the Cognition Enhancement Design Proposal:
- Loop detection via screen signature tracking (`NavigationState`, `LoopDetectionPolicy`)
- Executor step limit with narrative failure summaries (`ExecutorStepPolicy`)
- System reminders for todo list and scratchpad in turn context (`ContextPackager`)
- Failure recovery rules injected into prompts (`FailureRecoveryRules`)
- Arbitration decision tracing (`ArbitrationTrace`)

**18 files changed**: +723 lines, -10 lines

---

## Critical

None found. No security vulnerabilities, crash vectors, or data loss risks identified.

---

## High

### 1. Thread Safety: Mutable State in AgentTurnRunner

**File**: `AgentTurnRunner.kt` (lines 100-101)

```kotlin
private var navigationState: NavigationState = NavigationState()
private var previousActionSignature: String? = null
```

**Issue**: These mutable instance variables are modified during `executeTurn()`. If `executeTurn` is ever called concurrently (e.g., future parallelism or accidental reuse), this creates a data race.

**Fix**: Pass state through the turn execution chain or make them local to the run scope. Consider:

```kotlin
// Option A: Pass through turn data
data class TurnState(
    val navigationState: NavigationState,
    val previousActionSignature: String?
)

// Option B: Ensure single-threaded access is documented/enforced
```

**Risk**: Low currently (single-threaded execution), but architectural debt.

---

### 2. Missing Step Limit Evaluation in AgentTurnRunner

**Issue**: `ExecutorStepPolicy` is integrated in `SubAgentRunner.kt` for the narrative summary generation, but `AgentTurnRunner` doesn't use it directly to enforce step limits. The design proposal shows step policy evaluated per-turn to emit `WarnApproaching` status. Currently:

- `SubAgentRunner` generates narrative on `MaxTurnsReached`
- But warnings before hitting the limit aren't surfaced to the LLM context

**Fix**: Add step count tracking and policy evaluation in `AgentTurnRunner.executeTurn()`:

```kotlin
val stepDecision = stepPolicy.evaluate(turnNumber, delegatedQuery, history)
when (stepDecision) {
    is ExecutorStepDecision.WarnApproaching -> {
        // Inject warning into context for this turn
    }
    ...
}
```

---

### 3. Incomplete Todo Reminder Edge Case

**File**: `ContextPackager.kt` (lines 63-78)

**Issue**: If todos exist but all are `COMPLETED`, the reminder shows "Todo status: N items tracked." with no actionable info. Edge case produces awkward UX.

**Fix**:

```kotlin
private fun buildTodoReminder(): String? {
    val todos = todoState?.get() ?: return null
    val incomplete = todos.filterNot { it.status == TodoStatus.COMPLETED }
    if (incomplete.isEmpty()) return null  // Don't remind about completed todos
    // ... rest of logic
}
```

---

## Medium

### 1. Configuration Duplication Risk

**Files**: `LoopDetectionConfig` vs `CognitionProfile`

Both define defaults for `similarityThreshold = 0.90` and `maxConsecutiveScrollActions = 5`. If profile changes, config defaults stay stale.

**Fix**: Remove defaults from `LoopDetectionConfig` or always construct from profile values:

```kotlin
// In AgentTurnRunner.createLoopDetectionPolicy()
LoopDetectionPolicy(
    LoopDetectionConfig(
        similarityThreshold = cognitionProfile.loopSimilarityThreshold,
        maxConsecutiveScrollActions = cognitionProfile.maxConsecutiveScrollActions,
        repeatedScreenWindow = cognitionProfile.repeatedScreenWindow,  // Add to profile
        repeatedActionWindow = cognitionProfile.repeatedActionWindow   // Add to profile
    )
)
```

---

### 2. Missing Test Coverage

**Untested components**:
- `NavigationState.advance()` - signature generation, list management
- `ScreenSignature.similarityTo()` - Jaccard calculation edge cases
- `ExecutorStepPolicy.buildNarrativeSummary()` - output format verification
- `ContextPackager` with edge cases (empty todos, large scratchpad)

**Recommendation**: Add unit tests for:
```
agent/cognition/context/NavigationStateTest.kt
agent/cognition/policy/ExecutorStepPolicyTest.kt
```

---

### 3. File Size Approaching Limit

**File**: `AgentTurnRunner.kt` (500 lines)

Per coding standards, max is 400 lines. This commit added ~60 lines. Consider extracting:
- Loop detection orchestration to separate class
- Arbitration decision building to `ArbitrationDecision` companion

---

### 4. Design Doc Deviation: Warning Threshold

**Design doc** (line 170): `stepCount >= maxSteps - 2 -> StepDecision.WarnApproaching`

**Implementation** (ExecutorStepPolicy.kt:28): `stepCount >= maxSteps - 1`

This means warning appears on last step before limit, not second-to-last. Clarify if intentional.

---

### 5. Potential Redundant Action Classification

**File**: `AgentTurnRunner.kt` (lines 187-202)

The `classifyAction()` function is called for every tool in `toolCallsToExecute`, but only the last action is stored in `previousActionSignature`. This is correct for single-tool execution, but if multi-tool execution is ever enabled, only the final action is tracked.

**Fix**: Document this assumption or track all actions:

```kotlin
// Track the primary action (assumes single-tool-per-turn policy)
actionForNextTurn = classifyAction(toolCall)
```

---

## Low

### 1. Naming Inconsistency with Design Doc

| Design Doc | Implementation |
|------------|----------------|
| `screenHashes` | `recentSignatures` |
| `lastActions` | `recentActions` |

Minor but deviates from spec. Consider aligning for documentation consistency.

---

### 2. Priority Attribute in XML Tags

**File**: `ContextPackager.kt` (line 55)

```kotlin
<system_reminder priority="$priority">
```

This XML-like priority attribute is non-standard. Verify LLM models respect this formatting or simplify to:

```kotlin
<system_reminder>
**PRIORITY: ${severity.name}**
LOOP DETECTED: ${loopWarning.message}
</system_reminder>
```

---

### 3. Hardcoded Constants

**File**: `NavigationState.kt` (lines 6-9)

```kotlin
private const val MAX_SIGNATURE_HISTORY = 10
private const val MAX_SIGNATURE_ELEMENTS = 32
private const val POSITION_BUCKET_PX = 120
```

Consider moving to `CognitionProfile` for tuning flexibility, or document why these specific values were chosen.

---

## Android-Specific Checks

- [x] Coroutines scoped correctly - No new coroutines
- [x] No Context leaks - No static Context references
- [x] Main thread safe - Signature computation is lazy via sequences
- [x] Permissions checked - N/A
- [x] A11y service best practices - Uses existing a11y infrastructure

---

## Recommendation

**CHANGES_REQUESTED**

Must address:
1. **High #2**: Integrate step policy warnings into turn context, not just final narrative

Should address:
1. **High #1**: Document thread-safety assumption or refactor state management
2. **High #3**: Fix empty incomplete todos edge case
3. **Medium #2**: Add unit tests for `NavigationState` and `ExecutorStepPolicy`

The implementation follows the design proposal well and introduces clean, modular code. The arbitration tracing and failure recovery rules are solid additions. Main concerns are incomplete step limit integration in turn flow and missing test coverage for new data classes.

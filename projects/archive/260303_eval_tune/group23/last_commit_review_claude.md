# Code Review: Group 2/3 Anti-Loop Escalation Implementation

**Commit**: `8f02779`
**Reviewer**: Independent code-review agent
**Date**: 2026-02-27
**Verdict**: CHANGES REQUESTED (2 HIGH, 4 MEDIUM, 3 LOW, 1 NITPICK)

---

## HIGH Severity

### H1. FORCE_COMPLETE reports as GoalAchieved due to missing `success = false`

**File**: `AgentTurnRunner.kt:100`

The FORCE_COMPLETE path constructs `TurnOutcome.Complete(...)` without setting `success = false`:

```kotlin
TurnOutcome.Complete("Task failed: agent stuck in loop after ${config.maxTurns} attempts")
```

`TurnOutcome.Complete` has `success: Boolean = true` as its default. `Agent.kt:102-110` dispatches based on `result.success`:

```kotlin
is TurnOutcome.Complete -> {
    if (result.success) {
        eventDispatcher.status("✅ Goal achieved!")
        stopReason = AgentStopReason.GoalAchieved(result.message)
    } else {
        eventDispatcher.status("❌ Task failed: ${result.message}")
        stopReason = AgentStopReason.Error(result.message)
    }
}
```

As written, a forced failure emits "Goal achieved!" to UI and records `GoalAchieved` in the trace — polluting `goal_claim_precision` eval metric.

**Fix**: `TurnOutcome.Complete("...", success = false)`

---

### H2. Blocked actions set can trap legitimate escape strategies

**File**: `AgentTurnRunner.kt:216-217`

Blocked actions are from `recentActions.takeLast(3)`, which contains coarse signatures like `"mobile_action:click"`, `"mobile_action:system_button"`. Problems:

1. Blocking `"mobile_action:system_button"` blocks the back button — the most common escape from a stuck screen.
2. `classifyActionSignature` for clicks always returns `"mobile_action:click"` regardless of target, so blocking it blocks ALL clicks on ANY element.
3. When all screen actions are blocked, `TurnToolPolicy` selects nothing, burning turns until FORCE_COMPLETE.

**Fix**:
- Exclude navigational escape actions (`system_button`, `open_app`) from blocked set.
- Consider finer-grained signatures (include target element info) so blocking is specific to repeated action-target pairs.

---

## MEDIUM Severity

### M1. WARNING-level warnings do not reset blockedActions from prior BLOCK

**File**: `AgentTurnRunner.kt:233`

```kotlin
else -> navigationState // WARNING-level: don't change escalation counters
```

If agent was at BLOCK level with `blockedActions` populated, and next turn has only WARNING-level, stale `blockedActions` persist. `advance()` preserves them via `copy()`.

**Fix**: `else -> navigationState.copy(blockedActions = emptySet())`

---

### M2. FORCE_COMPLETE message uses `config.maxTurns` instead of actual turn count

**File**: `AgentTurnRunner.kt:100`

Says "after ${config.maxTurns} attempts" but FORCE_COMPLETE can fire mid-session (e.g., turn 12 of 30). `turnNumber` is in scope but unused.

**Fix**: Use `$turnNumber` instead.

---

### M3. Missing test coverage for escalation edge cases

**File**: `LoopDetectionPolicyTest.kt`

Missing tests:
1. **Escalation reset**: No test verifies `consecutiveLoopTurns` reset when loop resolves (logic lives in `AgentTurnRunner.prepareTurn()`, not policy).
2. **WARNING with high loop count**: No test that WARNING-severity warnings don't escalate even with `consecutiveLoopTurns = 10`.
3. **All actions blocked**: No test for `TurnToolPolicy.arbitrateToolCalls` when `blockedActions` matches all screen calls.

---

### M4. Inconsistent variable usage in `buildWarnings()` let block

**File**: `AgentTurnRunner.kt:265-267`

```kotlin
loopResult.warning?.let { warning ->
    if (loopResult.escalation >= EscalationLevel.BLOCK) {
        val blocked = loopResult.warning.message  // <-- should use `warning.message`
```

Functionally identical inside `let`, but fragile if refactored to a conditional.

**Fix**: `val blocked = warning.message`

---

## LOW Severity

### L1. Generic failure message in synthetic tool call

**File**: `AgentTurnRunner.kt:317-327`

The `answer` field is a static string with no context about what was attempted. For QA tasks, this field is scored. Including `config.goal` would provide minimal context.

### L2. Turn budget visibility API defaults allow silent no-op

**File**: `PromptBuilder.kt:138-141`

Defaults of `turnNumber = 0, maxTurns = 0` mean turn budget is silently omitted if caller forgets to pass values. Consider a nullable wrapper type for compile-time enforcement.

### L3. `classifyActionSignature` placement creates upward dependency

**File**: `TurnExecutionPhaseRunner.kt:272-297`

Top-level function in execution runner file creates import from `cognition/policy/TurnToolPolicy` → `agent/TurnExecutionPhaseRunner` (policy depends on execution layer). Consider moving to a shared utility or alongside `ToolCallRequest` in `Turn.kt`.

---

## NITPICK

### N1. Emoji in log messages

Status messages use emoji for UI display via `eventDispatcher.status()` — fine for that purpose. Just noting that some log parsers struggle with multi-byte characters if these ever appear in `Log.w()` output directly.

---

## Summary

| Severity | Count | Action |
|----------|-------|--------|
| HIGH | 2 | Fix before shipping to eval |
| MEDIUM | 4 | Fix H1-M4 now; M3 (tests) can be follow-up |
| LOW | 3 | Consider for follow-up |
| NITPICK | 1 | Awareness only |

**Status**: Fixes applied in follow-up commit addressing H1, H2, M1, M2, M4.

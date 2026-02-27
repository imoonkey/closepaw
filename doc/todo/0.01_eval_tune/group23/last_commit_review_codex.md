# Review: commit 8f02779 (3-tier anti-loop + eval tune)

## High
1. Forced failure path is still reported upstream as `GoalAchieved`
   - Location: `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:100`, `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt:102-105`
   - Problem: Tier3 executes synthetic `complete_task(status="failure")`, but then directly returns `TurnOutcome.Complete(...)`. `Agent.run()` maps any `TurnOutcome.Complete` to `AgentStopReason.GoalAchieved`.
   - Impact: Loop-forced failure is semantically mislabeled as success in runtime status/metrics, which conflicts with the design intent.
   - Fix: introduce a failure-completion outcome (or inspect completion status before mapping) and avoid mapping forced-failure completion to `GoalAchieved`.

2. Tier2 blocked-actions mechanism can miss the real repeated UI action
   - Location: `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:53-56`, `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicy.kt:75-78`, `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:216-218`
   - Problem: `actionForNextTurn` records the first executed call. Arbitration currently places cognitive calls before screen action, so recorded action is often `scratchpad`/`write_todos`, not the UI action. Tier2 then blocks `recentActions.takeLast(3)`, which may not match the repeated screen action it is supposed to suppress.
   - Impact: Core anti-loop control may be ineffective in exactly the target failure pattern (repeated UI operations).
   - Fix: record/block the selected screen action signature explicitly (or reorder execution signature source to prefer screen-changing tool calls).

3. Escalation counters are off by one versus configured thresholds
   - Location: `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/LoopDetectionPolicy.kt:74-79`, `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:213-224`
   - Problem: escalation checks `state.consecutiveLoopTurns` before incrementing for current turn. With threshold 2, BLOCK occurs on the 3rd qualifying turn; with threshold 5, FORCE_COMPLETE occurs on the 6th.
   - Impact: Delayed intervention weakens loop breaker effectiveness and does not match doc wording ("after N consecutive turns").
   - Fix: compute escalation using `state.consecutiveLoopTurns + 1` for current CRITICAL detection, or update state before escalation decision.

4. "Consecutive" loop-turn counter is not reset on WARNING-level loop detections
   - Location: `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:226-234`
   - Problem: when warning exists but severity is not CRITICAL, code leaves `consecutiveLoopTurns` unchanged.
   - Impact: non-consecutive CRITICAL events can accumulate and eventually trigger Tier2/Tier3 as if they were consecutive.
   - Fix: reset `consecutiveLoopTurns` when warning is null OR non-CRITICAL.

## Medium
1. Missing integration tests for escalation wiring and outcome semantics
   - Location: tests only updated in `app/src/test/kotlin/com/moonkey/androidagent/agent/cognition/policy/LoopDetectionPolicyTest.kt`
   - Gap:
   - No test that Tier2 actually drops blocked screen actions in arbitration path.
   - No test that Tier3 forced completion is surfaced as failure (not success).
   - No test for counter reset behavior across CRITICAL/WARNING transitions.
   - Recommendation: add integration-style tests around `AgentTurnRunner + TurnToolPolicy` behavior.

2. Shell runtime governance from design is only partially implemented
   - Location: prompt-only changes in `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
   - Problem: design discussed runtime shell controls (pattern block / ceiling), but commit only adds prompt guidance.
   - Impact: shell misuse mitigation depends entirely on model compliance.
   - Recommendation: add runtime shell repetition checks in policy/arbitration path.

## Verification Run
- `./gradlew testDebugUnitTest --tests com.moonkey.androidagent.agent.cognition.policy.LoopDetectionPolicyTest` passed
- `./gradlew testDebugUnitTest` passed

## Recommendation
CHANGES_REQUESTED

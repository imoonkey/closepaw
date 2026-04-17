# Agent Core Simplification — Aligned Improvement Plan

**Status:** Final (aligned by CLAUDE + CODEX, 2 rounds)

---

## Ordering Principle

Fix runtime invariants first, then collapse duplicate structures, then clean up what's left. Do not start with dead-code removal or extraction — the current problem is too many weak abstractions around a loop that should be governed by a few hard invariants.

---

## P0: Enforce One Screen-Changing Action Per Turn

**Why first:** This is the main simplification lever. It removes both accidental complexity and one real correctness problem (next-turn action state can describe an action that never executed).

### Changes

1. **TurnToolPolicy.kt** — Selection becomes: any pure cognitive/memory tools + at most one screen-changing tool. Never `complete_task` in the same turn as a screen-changing tool.

2. **TurnExecutionPhaseRunner.kt** — Return the signature of the action that *actually executed*, not the action that was merely planned. Post-action snapshot capture happens once per turn (after the single screen-changing action), not between chained actions.

3. **AgentTurnRunner.kt** — next-turn state derived from what actually ran.

### Expected Deletions

- Multi-screen arbitration logic in TurnToolPolicy
- Multi-action snapshot chaining in TurnExecutionPhaseRunner
- Precomputed actionForNextTurn that can describe unexecuted actions

### Acceptance Criteria

- No code path executes two screen-changing tools in one turn
- Loop detection only sees actions that actually ran
- Prompts and runtime rules match

---

## P1: Split ExecutorStepPolicy Into Two Concerns

**Why next:** After the action invariant is clean, this is a straightforward separation.

### Changes

1. Replace `ExecutorStepPolicy` with:
   - A tiny `TurnBudgetCheck` helper: "is this the final allowed turn?" Returns boolean.
   - A standalone `DelegationSummaryFormatter`: builds narrative when a delegated executor maxes out.

2. Remove `ExecutorStepDecision.WarnApproaching` (computed but never surfaced).

3. Remove `narrativeSummaryOnLimit` parameter (always `true` in all call sites). Hardcode the behavior.

4. Rename from "Executor" since it applies to all agents.

### Primary Files

- `cognition/policy/ExecutorStepPolicy.kt`
- `AgentTurnRunner.kt`
- `subagent/SubAgentRunner.kt`

### Acceptance Criteria

- No computed-but-unused decision states
- Final-turn warning is obvious from one call site
- Delegation summary generation does not require instantiating a policy object

---

## P1: Unify Agent Role Definitions Into One Model

**Why here:** Structural cleanup that spans both top-level and delegated agents. Should happen before smaller cleanup to avoid applying fixes twice.

### Changes

1. Replace `AgentDef` (abstract class) + `AgentDefinition` (sub-agent data class) with one role model that owns:
   - Role name
   - System prompt
   - Allowed tools
   - Execution role / model bucket
   - Whether it can be invoked as a sub-agent

2. Derive both `SessionAgentRunner` session startup and `DelegateTaskTool` registry entries from the same source.

3. Delete `requiresDelegationToolRegistration`. Register delegation capability from the selected top-level role definition itself (for example via role properties or the resolved tool set), not by hardcoding `mode == PRO`.

### Expected Deletions

- `ExecutorAgent` bridge object
- Duplicate `AgentRegistry` / `AgentDefinition` types
- `AgentDef.id` (unused)
- `AgentRegistry.getAll()` (unused)

### Primary Files

- `definition/AgentDef.kt`, `AgentDefRegistry.kt`, `PlannerAgentDef.kt`, `ExecutorAgentDef.kt`, `StandaloneAgentDef.kt`
- `subagent/SubAgentRunner.kt`
- `session/SessionAgentRunner.kt`
- `tool/impl/DelegateTaskTool.kt`

### Acceptance Criteria

- Executor prompt/tool ownership exists in exactly one place
- Top-level and delegated agent startup read from the same definition source
- No mode-specific delegation special case remains outside the unified role model

---

## P2: Dead Code and Vestigial State Removal

**Why now:** After invariant enforcement and structural unification, these become straightforward deletions.

### Changes

1. **NavigationState** — Remove `consecutiveScrollActions`, `recentActions` (dead after heuristic removal). Remove `ScreenSignature.fingerprint` (unused; only `tokens` consumed). Remove `LoopWarningSeverity.CRITICAL` (never emitted). Consider removing severity enum entirely if WARNING is the only value.

2. **PreTurnContext** — Remove `appTier` field (set but never read after construction).

3. **TurnToolPolicy** — Replace `any` + `find` double traversal with single `find` + null check.

4. Update tests that assert on removed fields (`NavigationStateTest.kt`).

### Acceptance Criteria

- Every field in NavigationState is read by production code
- No computed-but-unused decision states or fields remain
- Loop detection behavior still fully test-covered

---

## P3: Observation Representation Unification

**Why later:** Valuable but lower leverage than fixing invariants and definitions.

### Changes

1. Extract one canonical observation payload per turn (screen state captured once).
2. Prompt rendering and history recording both project from this payload.
3. Remove the temporal coupling where build-prompt-first ordering is a correctness requirement.

### Primary Files

- `cognition/prompt/PromptBuilder.kt`
- `TurnPlanningPhaseRunner.kt`

### Acceptance Criteria

- Prompt and history cannot drift in how they describe the same screen
- No correctness comment depends on call ordering between prompt building and history recording

---

## P3: Event Emission Consolidation

### Changes

1. Add missing event-emit methods to `AgentEventDispatcher` (e.g., `actionExecuted()`, `approvalRequired()`).
2. Remove raw `eventEmitter` passthrough from `AgentTurnRunner` and `TurnExecutionPhaseRunner`.
3. All agent-originated events flow through the dispatcher.

### Acceptance Criteria

- A new event type has one obvious place to be added
- No raw eventEmitter lambda is passed alongside the dispatcher

---

## P3: Tool Argument Decoding Consolidation

### Changes

1. Create one normalized action-target decoder shared by `ActionDescriptionFormatter` and `ActionSignature`.
2. Both formatting and signature generation consume the decoded result.

### Acceptance Criteria

- A mobile_action schema change has one obvious decoding path to update

---

## P4: Measurement-Gated Items

### Turn.kt Text Recovery Audit

Add telemetry to track fire rate of `recoverToolCallFromText`. After one eval cycle, remove paths that never fire. Do not remove speculatively — the codebase supports multiple backends.

### Magic Delay Constants

Name `delay(200)` and `delay(500)` in `TurnExecutionPhaseRunner.kt` with explanatory comments. Only unify with `config.uiSettleDelayMs` if the pre-execution pacing and post-action settling semantics genuinely match.

### Rename ExecutorStepPolicy

After P1 split, rename remaining piece to `TurnBudgetPolicy` or similar. The "Executor" prefix is misleading when used for all agents.

---

## Open Questions

1. **Dual cancellation signals (Agent.kt):** Claude proposes unifying `CompletableDeferred` + `AtomicBoolean` to single deferred. CODEX notes ownership split between SessionAgentRunner (external) and Agent (internal). Is unification safe under all pause/resume/shutdown paths? **Recommendation:** Defer from the aligned plan for now. Revisit only after P0/P1 if lifecycle ownership still looks unnecessarily split, and require an explicit pause/resume/shutdown audit before changing it.

2. **SubAgentRunner file split:** Claude proposes 3-file extraction (7 types in 288 lines). CODEX argues this is churn without concept reduction. **Recommendation:** Keep it out of the aligned plan. The real fix is P1 definition unification, which should naturally shrink SubAgentRunner.

3. **Turn DTOs survivorship:** Some one-use DTOs (PlanningPhaseOutput, TurnExecutionResult) name contracts between phases. After P0/P1 changes, reassess which are genuinely dead vs which document the flow. **Recommendation:** Remove dead fields now (`appTier`). Keep contract-naming DTOs until the turn loop shape stabilizes, then trim only the ones that still add no clarity.

---

## Summary

| Priority | Items | Nature | Risk |
|----------|-------|--------|------|
| P0 | 1 item | Invariant enforcement | Medium (behavior change) |
| P1 | 2 items | Structural unification | Medium (multi-file refactor) |
| P2 | 1 item | Dead code removal | None |
| P3 | 3 items | Consolidation | Low-Medium |
| P4 | 3 items | Measurement + naming | None-Low |

**Sequencing:** P0 -> P1 -> P2 -> P3 -> P4. Each level depends on the previous being stable.

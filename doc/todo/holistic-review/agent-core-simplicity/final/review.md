# Agent Core Simplicity — Aligned Review

**Status:** Final (aligned by CLAUDE + CODEX, 2 rounds)
**Scope:** `app/src/main/kotlin/com/moonkey/androidagent/agent/` (24 files, ~3.2k LOC)

---

## Executive Summary

The agent core implements a ReAct loop (observe -> decide -> execute -> observe). The architecture is reasonable and several patterns are well-applied (minimal TurnRunnerState, clean TurnOutcome sealed class, well-scoped AgentStopReason, solid LoopDetectionPolicy after heuristic pruning).

The main source of complexity is that the runtime does not enforce the one-screen-action-per-turn invariant that all three agent prompts describe. This mismatch creates cascading complexity in tool arbitration, post-action snapshot chaining, completion deferral, and next-turn state tracking — plus one concrete correctness bug.

The second source is duplicated role-definition machinery across two parallel systems (top-level AgentDef and sub-agent AgentDefinition/AgentRegistry).

After those two, there is a tail of vestigial state, dead code, and one-use DTOs.

---

## Findings

### HIGH

#### H-1: Runtime does not enforce the one-screen-action invariant

**Source:** CODEX (unique finding)

All three agent prompts teach "at most one screen-changing action per turn, then observe":
- `PlannerAgentDef.kt:28-37`
- `ExecutorAgentDef.kt:29-40`
- `StandaloneAgentDef.kt:31-41`

The runtime contradicts this:
- `TurnToolPolicy.kt:33-84` keeps all screen-changing tools, not one
- `TurnExecutionPhaseRunner.kt:44-64` executes every selected tool sequentially
- `TurnExecutionPhaseRunner.kt:108-127` refreshes snapshots between tools (exists only because multi-screen turns are allowed)

**Correctness bug:** `TurnExecutionPhaseRunner.kt:45` computes `actionForNextTurn` before execution. If an earlier tool fails (`TurnExecutionPhaseRunner.kt:59-62`), the runner still returns the precomputed signature (`TurnExecutionPhaseRunner.kt:64`). `AgentTurnRunner.kt:113-116` stores it into next-turn state, and `NavigationState.kt:22-40` records it as if it happened. This contaminates loop detection.

**KISS invariant:** Per turn: any pure cognitive/memory tools + at most one screen-changing tool. Never `complete_task` in the same turn as a screen-changing tool.

#### H-2: Dual agent role-definition systems

**Source:** Both (CODEX broader scope)

Top-level roles: `definition/AgentDef.kt`, `AgentDefRegistry.kt`, `PlannerAgentDef.kt`, `ExecutorAgentDef.kt`, `StandaloneAgentDef.kt`

Sub-agent roles: `subagent/SubAgentRunner.kt:29-39` (`AgentDefinition`), `SubAgentRunner.kt:79-99` (`AgentRegistry`), `SubAgentRunner.kt:61-74` (literal data-copy from ExecutorAgentDef)

Consumers split: `SessionAgentRunner.kt:51-77` uses AgentDef; `SessionAgentRunner.kt:129-147` and `DelegateTaskTool.kt:18-35` use AgentDefinition/AgentRegistry.

This means prompt/tool/role ownership exists in two registries and two definition types. Changes must be reasoned about in both.

Aligned direction: unify around one role model and derive delegation capability from that role model itself. Do not replace the duplicated definition systems with a new hardcoded `if (mode == PRO)` branch in `SessionAgentRunner`.

#### H-3: ExecutorStepPolicy mixes unrelated concerns and carries dead behavior

**Source:** Both

Three jobs in one class:
- Warn near turn limit (computed but discarded — `WarnApproaching` has no consumer)
- Final-turn warning for active run (`AgentTurnRunner.kt:220-226,234-243`)
- Narrative summary after sub-agent stops (`SubAgentRunner.kt:177-193`)

Named "Executor" but used for all agents. `WarnApproaching` is effectively dead. `narrativeSummaryOnLimit` parameter is always `true` in all call sites.

---

### MEDIUM

#### M-1: NavigationState carries removed heuristics

**Source:** Both

`consecutiveScrollActions` and `recentActions` are computed every turn but never read in production. The consumers (scroll-spam, action-repetition detection) were intentionally removed per `LoopDetectionPolicy.kt:13`. `ScreenSignature.fingerprint` is computed via hash every turn but never used — only `tokens` is consumed by `similarityTo()`. `LoopWarningSeverity.CRITICAL` is never emitted or branched on.

#### M-2: Observation rendering is duplicated with temporal coupling

**Source:** CODEX

Current screen rendered twice:
- `PromptBuilder.kt:111-178` for LLM prompt
- `TurnPlanningPhaseRunner.kt:173-205` for history

`TurnPlanningPhaseRunner.kt:84-86` documents the ordering dependency: build prompt first, then record history, or the current screen gets duplicated. Correctness depends on call sequencing — a temporal coupling smell.

#### M-3: Turn flow fragmented by one-use DTOs

**Source:** Both

Several handoff envelopes serve a single caller:
- `PreTurnContext` (dead `appTier` field)
- `PreparedTurn`
- `PlanningPhaseOutput`
- `TurnExecutionResult`
- `CompletionDecision`

**Open question:** Some of these (e.g., `PlanningPhaseOutput`) name contracts between phases and may aid readability. Which specific DTOs genuinely add confusion vs document the flow?

#### M-4: Event emission split between dispatcher and raw emitter

**Source:** Both

`AgentEventDispatcher` centralizes ~10 event types, but `TurnExecutionPhaseRunner.kt:148-179` and `SubAgentRunner.kt:207-226` bypass it. "Where agent events come from" is not actually centralized.

Resolution direction: add missing methods to dispatcher (it already covers the majority).

#### M-5: Tool argument interpretation duplicated

**Source:** CODEX

`ActionDescriptionFormatter.kt:20-124` and `ActionSignature.kt:20-94` both independently decode mobile_action argument variants (text target, bounds, coordinates, element-index, subtypes). Any schema change requires updating two JSON parsers.

---

### LOW

#### L-1: Dead code sweep

**Source:** Both (Claude more detailed)

- `AgentDef.id` — no production usage
- `AgentRegistry.getAll()` — no production usage
- `PreTurnContext.appTier` — set but never read
- `LoopWarningSeverity.CRITICAL` — never emitted
- `ScreenSignature.fingerprint` — computed but unused
- Duplicate pause/resume status emission in `Agent.kt:69-75,173-190`

#### L-2: Dual cancellation signals

**Source:** Claude

`Agent.kt` has both `CompletableDeferred<AgentStopReason>` and `AtomicBoolean(stopRequested)`. Unification to single deferred is plausible but has lifecycle nuance: `SessionAgentRunner` owns the external deferred, `Agent` owns the internal flag. Trade-off understated in Claude's review.

**Open question:** Is unification safe under all pause/resume/shutdown paths?

**Aligned stance:** Defer this from the main simplification sequence. Revisit only after P0/P1 when lifecycle ownership is easier to reason about, and require an explicit pause/resume/shutdown state audit before changing it.

#### L-3: Turn.kt text recovery complexity

**Source:** Claude

130 lines of defensive parsing for LLM text-as-tool-call recovery. Multiple recovery paths (object-wrapped, inline markers, balanced JSON, markdown fence stripping). May be vestigial with modern function-calling models.

**Resolution:** Add telemetry to measure fire rate. Remove paths that never fire after one eval cycle. Do not remove speculatively.

#### L-4: Hardcoded magic delays

**Source:** Claude (refined by CODEX)

`TurnExecutionPhaseRunner.kt:42` (`delay(200)`) and `:216` (`delay(500)`) are magic numbers. They serve different purposes (pre-execution pacing vs post-action observation settling). Resolution: name them and add comments. Only unify with `config.uiSettleDelayMs` if semantics genuinely match.

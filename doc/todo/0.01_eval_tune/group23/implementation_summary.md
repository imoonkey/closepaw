# Group 2/3 Eval Tune — Implementation Summary

**Commit**: `8f02779` on `main`
**Design**: [`final/group23_improvement_design.md`](final/group23_improvement_design.md)
**Scope**: 10 files changed, 364 insertions, 95 deletions (source only; +1640 in design/discussion docs)

---

## Problems Addressed

| ID | Problem | Affected Tasks (of 12 failures) |
|----|---------|------|
| P1 | Repeated action loops — agent clicks/scrolls the same element indefinitely | 7 |
| P2 | QA data collection — agent navigates but never extracts/returns the answer | 4 |
| P3 | Shell misuse — agent runs `am start` / `input` when UI actions suffice | 8+ |
| P4 | QA answer semantics — eval uses 90% fuzzy match but agent returns wrong format | 2 |
| P5 | Vision perception gap — OCR tasks fail with a11y-only perception | 1–2 |
| P6 | False completion — agent declares success without verifying | 1–2 |
| P7 | Turn budget blindness — agent doesn't know how many turns remain | 2–3 |
| P8 | Dialog cycling — permission/system dialogs block progress | 2 |

## Implementation

### 1. Three-Tier Anti-Loop Escalation (P1, P8)

A graduated response to repeated action loops, replacing the flat warning system.

**Files**: `LoopDetectionPolicy.kt`, `AgentTurnRunner.kt`, `NavigationState.kt`, `TurnToolPolicy.kt`, `TurnExecutionPhaseRunner.kt`

| Tier | Level | Trigger | Action |
|------|-------|---------|--------|
| 1 | `ADVISORY` | Any loop warning (WARNING or first CRITICAL) | Existing warning text injected into observation |
| 2 | `BLOCK` | `consecutiveLoopTurns >= 2` with CRITICAL severity | Recent action signatures filtered out by `TurnToolPolicy.arbitrateToolCalls()`. Stronger warning text mandates strategy change. |
| 3 | `FORCE_COMPLETE` | `consecutiveLoopTurns >= 5` with CRITICAL severity | Synthetic `complete_task(status="failure")` injected via normal execution path. Skips LLM planning entirely. |

**Key design decisions**:
- Escalation only counts CRITICAL-severity warnings (not WARNING). WARNING-level doesn't increment `consecutiveLoopTurns`.
- Counter resets to 0 when no loop is detected, allowing the agent to recover.
- Tier 3 uses synthetic tool call through `executionPhaseRunner.executeActions()` rather than returning `TurnOutcome.Complete` directly. This is critical because `TurnOutcome.Complete` maps to `AgentStopReason.GoalAchieved` in `Agent.kt:104`, which would misrepresent a forced failure as success. The synthetic `complete_task(status="failure")` goes through the normal trace/history pipeline.
- `classifyActionSignature()` was extracted from `TurnExecutionPhaseRunner` as a top-level `internal fun` so `TurnToolPolicy` can reuse it for action-signature matching during filtering.

**State flow**:
```
NavigationState.consecutiveLoopTurns: 0 → 1 → 2 (BLOCK) → 3 → 4 → 5 (FORCE_COMPLETE)
                                          ↑ reset to 0 if no warning detected
```

### 2. System Prompt Enhancements (P2, P3, P4, P5, P6)

**File**: `StandaloneAgentDef.kt`

Four new/expanded prompt sections:

- **Shell guardrails (P3)**: Use/don't-use matrix (prefer UI for app operations, shell only for file I/O, `getprop`, `dumpsys`). Known safe paths listed. 2-consecutive-failure fallback rule.
- **QA protocol (P2, P4)**: Field-by-field data identification, scratchpad accumulation, mandatory `complete_task(answer=...)` with exact extracted data.
- **Pre-completion verification (P6)**: Before calling `complete_task`, agent must re-read the file/screen and verify all steps actually took effect.
- **Vision limitations (P5)**: If task requires reading visual content (images, handwriting) and no screenshot input is available, fail early after 2 attempts instead of looping.

### 3. Turn Budget Visibility (P7)

**Files**: `PromptBuilder.kt`, `TurnPlanningPhaseRunner.kt`

- `buildInputItems()` and `buildObservationText()` accept `turnNumber` and `maxTurns` parameters.
- When both > 0, observation text is prefixed with `"Turn N/M"`.
- `TurnPlanningPhaseRunner` passes `turnNumber` (already available) and `config.maxTurns` through.
- Agent now sees exactly how many turns remain, enabling budget-aware planning (e.g., switching to `complete_task` when running low).

### 4. Hybrid Perception Override (P5)

**File**: `eval/config/default.yaml`

- Added `MarkorTranscribeReceipt: { perception_mode: hybrid }` to `task_overrides`, joining existing vision-dependent tasks (`BrowserDraw`, `BrowserMaze`, `ExpenseAddMultipleFromGallery`).

## Test Changes

**File**: `LoopDetectionPolicyTest.kt`

- Updated 4 existing tests to use `LoopDetectionResult` API (`result.warning?.severity` instead of `warning?.severity`).
- Added 3 new tests:
  - `escalation reaches BLOCK after consecutiveLoopTurns threshold` — verifies BLOCK at threshold=2
  - `escalation reaches FORCE_COMPLETE after high consecutiveLoopTurns` — verifies FORCE_COMPLETE at threshold=5
  - `no escalation when no warning detected` — verifies NONE/null baseline

## Architecture Diagram

```
                    ┌──────────────────────┐
                    │   AgentTurnRunner    │
                    │   executeTurn()      │
                    └──────┬───────────────┘
                           │
                    ┌──────▼───────────────┐
                    │   prepareTurn()      │
                    │  - advance NavState  │
                    │  - detect() → Result │
                    │  - update counters   │
                    └──────┬───────────────┘
                           │
              ┌────────────┼────────────────┐
              │            │                │
       FORCE_COMPLETE    BLOCK          ADVISORY/NONE
              │            │                │
    ┌─────────▼──────┐  ┌──▼────────────┐  │
    │  synthetic     │  │ planningPhase │  │
    │  complete_task │  │ (blockedActions│  │
    │  (failure)     │  │  passed)      │  │
    └────────────────┘  └──┬────────────┘  │
                           │               │
                    ┌──────▼───────────────┐
                    │  TurnToolPolicy      │
                    │  arbitrateToolCalls() │
                    │  - filter blocked    │
                    │    action signatures │
                    └──────────────────────┘
```

## Deferred Items

These were identified during design but intentionally deferred:

1. **History compression** — reduce token usage for long sessions by summarizing older turns. Separate design needed.
2. **Eval observability** — structured logging of escalation events for post-eval analysis. Will add when running eval validation.
3. **Dialog-specific recovery heuristics** — special handling for permission/system dialogs beyond generic loop detection. Needs more trace data.

## Validation Plan

1. `./gradlew assembleDebug` — PASS
2. `./gradlew test` — PASS (7 tests in LoopDetectionPolicyTest, all green)
3. Next: run eval on Group 2+3 tasks to measure improvement vs baseline

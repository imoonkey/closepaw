# Cross-Review: CLAUDE vs CODEX Agent Core Simplicity Designs

## Summary

Claude's review is useful and technically grounded on several low-risk cleanup items. In particular, it catches a few dead fields and low-signal leftovers that CODEX did not spell out explicitly, such as `ScreenSignature.fingerprint`, the dead `CRITICAL` severity, and the fact that `WarnApproaching` is computed but discarded (`design_claude.md:97-138`).

The main problem is prioritization and problem framing. Claude treats the module mostly as "clean architecture with accumulated dead code" (`design_claude.md:11-25`), then starts its plan with dead-code removal (`improvement_plan_claude.md:7-49`). The code points to a bigger issue: the runtime does not enforce the one-screen-action ReAct invariant that all three prompts teach. That mismatch is both a correctness problem and the main source of extra machinery. CODEX centers that issue first (`design_codex.md:18-45`, `improvement_plan_codex.md:14-44`), which is why it is the stronger base.

## High

### 1. Claude misses the main correctness/simplicity issue: runtime behavior contradicts the prompt model

Claude never identifies the highest-leverage issue in the module: the prompts teach "one screen-changing action, then observe", but the runtime still executes multiple screen-changing tools in one turn.

Code evidence:

- `PlannerAgentDef.kt:28-37`, `ExecutorAgentDef.kt:29-35`, and `StandaloneAgentDef.kt:31-35` all describe a one-screen-action mental model.
- `TurnToolPolicy.kt:43-84` selects all screen-changing tool calls, not one.
- `TurnExecutionPhaseRunner.kt:51-64` executes them sequentially in the same turn.
- `TurnExecutionPhaseRunner.kt:108-127` refreshes the snapshot between tools, which only exists because multi-screen turns are allowed.

This is not just an aesthetic issue. `TurnExecutionPhaseRunner.kt:45` computes the next-turn action signature before execution, and if an earlier tool fails the runner still returns a signature for a later action that never ran (`TurnExecutionPhaseRunner.kt:59-64`). `AgentTurnRunner.kt:113-116` then feeds that into next-turn state.

Claude's plan therefore optimizes around the edges of the current shape instead of attacking the core reason the shape became complex. That makes `improvement_plan_claude.md:7-92` materially mis-prioritized compared with `improvement_plan_codex.md:14-44`.

### 2. Claude's definition-system critique is directionally right but incomplete

Claude correctly argues that the main `AgentDef` abstraction is heavier than it needs to be (`design_claude.md:26-39`). The gap is that this is only half of the duplication story.

The larger duplication is between:

- top-level role definitions in `definition/AgentDef*`
- sub-agent role definitions in `subagent/AgentDefinition` / `AgentRegistry`

The executor persona is copied from one system into the other (`SubAgentRunner.kt:61-74`). `SessionAgentRunner.kt:51-77` consumes the first system, while `SessionAgentRunner.kt:129-147` and `DelegateTaskTool.kt:18-35` consume the second.

Claude's structural recommendation only consolidates the first group into one file (`improvement_plan_claude.md:95-110`). That reduces file count, but it does not remove the more important duplication boundary. CODEX's proposal to unify top-level and delegated role definitions into one model (`design_codex.md:47-77`, `improvement_plan_codex.md:74-111`) matches the real seam in the code more accurately.

### 3. Some Claude simplifications would create churn without reducing conceptual load

The weakest example is `improvement_plan_claude.md:120-130`, which proposes splitting `subagent/SubAgentRunner.kt` into multiple files because it contains several types. The current file is 287 lines and under the project limit. Splitting it would reduce local density, but it would not remove any concepts. It likely increases navigation cost while leaving the same duplicated role model in place.

A similar issue shows up in the review note about constructing `PromptBuilder` every turn (`design_claude.md:202-215`). `PromptBuilder` is cheap to construct and depends on `supportsVision`, which is resolved from the current model. Caching it would add statefulness for negligible gain. This is not a real KISS win.

These items are not wrong to notice, but they are poor candidates for the top simplification track.

## Medium

### 4. Claude contributes several good dead-code findings that CODEX should absorb

Claude is stronger than CODEX on a few specific cleanup opportunities:

- `ScreenSignature.fingerprint` is unused in production (`design_claude.md:97-107`).
- `LoopWarningSeverity.CRITICAL` is unused (`design_claude.md:109-120`).
- `PreTurnContext.appTier` is unused (`design_claude.md:152-166`).
- `WarnApproaching` is computed but dropped (`design_claude.md:122-138`).

Those are legitimate simplifications and should be folded into the aligned draft. They are just not the right front door.

### 5. Claude's cancellation simplification is plausible, but the trade-off is understated

Claude argues that `Agent.kt` should use only `cancellationSignal` and drop `stopRequested` (`design_claude.md:54-65`, `improvement_plan_claude.md:53-63`). That is plausible, but the trade-off is more subtle than the doc suggests.

Today there is an ownership split:

- `SessionAgentRunner` owns the external `CompletableDeferred<AgentStopReason>` (`SessionAgentRunner.kt:57`, `178-188`)
- `Agent` owns the internal immediate stop flag (`Agent.kt:35`, `192-199`)

Unifying them is possible, but it changes who owns terminal completion of the deferred and when. That is worth doing only if the lifecycle semantics stay obviously correct under pause/resume and shutdown paths. Claude treats it as near-zero risk; that is overstated.

### 6. Claude's text-recovery handling is careful in the plan and should be kept that way

Claude flags `Turn.kt` text recovery as large and potentially suspicious (`design_claude.md:139-151`), but the plan does not remove it outright. Instead it recommends measuring how often recovery actually fires before deleting paths (`improvement_plan_claude.md:135-143`).

That trade-off is sound. The codebase supports multiple backends and explicitly tests recovery behavior. This is the right kind of "simplify later if telemetry says it is dead" item.

### 7. Claude underweights temporal coupling in the planning path

One major maintainability issue missing from Claude's review is the duplicated rendering of the current screen:

- `PromptBuilder.kt:111-178` builds the observation shown to the model.
- `TurnPlanningPhaseRunner.kt:173-205` serializes the same observation again into history.
- `TurnPlanningPhaseRunner.kt:84-86` documents that the order matters so the current screen is not duplicated in the prompt.

That is a real simplicity issue because correctness depends on sequencing. CODEX explicitly calls this out (`design_codex.md:129-145`, `improvement_plan_codex.md:171-193`); Claude does not.

## Low

### 8. Claude includes a few low-signal items that should not drive the aligned draft

These notes are true but low leverage:

- duplicate `any` + `find` traversal in `TurnToolPolicy` (`design_claude.md:175-184`, `improvement_plan_claude.md:80-91`)
- inline auto-retain memory logic in `Agent.kt` (`design_claude.md:186-190`)

These are cleanup candidates, not design-shaping findings.

### 9. Claude's delay recommendation needs refinement

Claude is right that `delay(200)` and `delay(500)` are magic numbers (`design_claude.md:217-226`, `improvement_plan_claude.md:145-152`). The plan's suggestion that the 500ms delay should "probably" become `config.uiSettleDelayMs` is too strong. The two waits serve different purposes:

- pre-execution pacing
- post-action observation settling

The safe aligned version is: name them, explain them, and only unify them with config if the semantics really match.

## Trade-off Assessment

Claude's design is better at:

- specific dead-code inventory
- cautious measurement before deleting text-recovery paths
- identifying a few redundant flags/config knobs

CODEX's design is better at:

- identifying the real root cause of complexity
- connecting prompt semantics to runtime semantics
- seeing that the definition duplication problem spans both top-level and delegated agents
- sequencing changes so behavior invariants are simplified before cleanup passes

That trade-off matters. Starting from Claude's plan would likely produce a decent cleanup patch set, but it would leave the central multi-screen-action runtime model intact. Starting from CODEX's plan addresses the actual source of complexity first, while still allowing Claude's cleanup items to be folded in afterward.

## Recommendation

Use CODEX as the base for the first aligned draft.

The aligned draft should import the best parts of Claude:

- dead-code removals for `fingerprint`, `CRITICAL`, `appTier`, and `WarnApproaching`
- telemetry-gated review of `Turn.kt` text-recovery complexity
- a softer, better-scoped note about magic delays

But the primary structure and sequencing should come from CODEX, because it is more correct about where the core complexity actually comes from.

**Better base for the first aligned draft: CODEX**

# Review: agent-core-simplicity changes (`55b597f..HEAD`)

## Summary

This range cleanly lands most of the P1/P3 work from `improvement_plan.md`: `TurnBudget` replaces `ExecutorStepPolicy`, role definitions are unified behind `AgentRoleDef`, event emission is centralized into `AgentEventDispatcher`, and `ActionTarget` removes duplicated mobile-action decoding.

I also ran targeted unit tests for the touched areas:

- `TurnToolPolicyTest`
- `PromptBuilderTest`
- `ActionTargetTest`
- `SubAgentRunnerTest`
- `TurnBudgetAndDelegationSummaryTest`

Those tests pass. The remaining concerns are about design-plan alignment and a couple of still-open simplification gaps, not obvious red tests.

## High

1. **P0 is still not enforced in runtime code.**

`TurnToolPolicy.kt:35-74` still keeps every screen-changing tool in the selected set via `addAll(screenCalls)`, and `TurnExecutionPhaseRunner.kt:56-69` still executes them sequentially. The execution phase also still captures a fresh post-action snapshot after each tool at `TurnExecutionPhaseRunner.kt:114-133`, which is exactly the multi-action chaining the plan said to delete. That directly contradicts P0 in `improvement_plan.md` and the prompts in `StandaloneAgentDef.kt:31-32`, `PlannerAgentDef.kt:28-30`, and `ExecutorAgentDef.kt:33-37`, all of which tell the model to do at most one screen-affecting action per turn.

This is not just a comment mismatch. It preserves the core complexity the plan was trying to remove: the planner can still `open_app` and `delegate_task` in one turn, or a standalone agent can still `click` then `back`, with the later tool grounded on an intra-turn snapshot rather than a new model decision. The existing policy test still codifies the old behavior in `TurnToolPolicyTest.kt:90-104`, so the biggest simplification lever in the plan is still absent in both code and tests. I would block on enforcing `cognitive tools + at most one screen-changing tool`, then collapsing post-action capture to once per turn after that single action.

## Medium

1. **Observation unification is only partial; screenshot-only mode still has two sources of truth.**

`TurnObservation.kt:29-35` describes `screenBlock` as the canonical observation payload shared by prompt rendering and history recording, and `TurnPlanningPhaseRunner.kt:89-96` does write that exact block into history. But `PromptBuilder.kt:146-155` bypasses `screenBlock` entirely when `hasAccessibility == false` and synthesizes different prompt text instead, with the code comment explicitly calling the divergence “intentional.” That contradicts the P3 acceptance criterion that prompt and history should not drift in how they describe the same screen.

The practical problem is that screenshot-only wording now has to stay in sync across two places again, while `HistoryManager.compressScreenContent()` depends on the exact history form. The changed tests also only assert canonical parity for accessibility mode (`PromptBuilderTest.kt:188-196`); the screenshot-only test at `PromptBuilderTest.kt:118-125` codifies the divergent prompt text instead of checking parity. If screenshot-only turns need extra live guidance, append that around `observation.screenBlock`; do not replace the canonical block.

## Low

1. **The action-signature path is now vestigial and its naming/docs are misleading.**

`TurnRunnerState` no longer carries `previousActionSignature` (`AgentRuntimeTypes.kt:21-28`), and loop detection now only reads screen signatures (`LoopDetectionPolicy.kt:24-47`). But `TurnExecutionPhaseRunner.kt:37-42,70` still returns an action signature, and `ActionSignature.kt:7-18,62-89` still claims those signatures are used by loop detection and tool arbitration. `ActionTarget.kt:8-10` repeats the same story.

This is dead-path residue from the earlier P0/P2 cleanup: the runtime no longer consumes the value, but the code and tests still make it look important. Either remove the return value and the now-orphaned helpers, or wire them into a real policy again. Leaving them as-is makes the codebase look simpler than it really is while preserving misleading documentation.

## Recommendation

**CHANGES_REQUESTED**

The P1 refactors are in good shape, but the highest-leverage item in the plan, one screen-changing action per turn, is still not implemented in runtime code, and the new observation model is not actually canonical in screenshot-only mode.

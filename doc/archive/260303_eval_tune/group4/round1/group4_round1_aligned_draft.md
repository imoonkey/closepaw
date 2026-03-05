# Group 4 Round1 Aligned Design Draft

## Scope

- Eval run: `eval/results/20260227_161048`
- Task set: Group4 (`20` tasks, `1` infra failure, scripted success `7/19 = 36.8%`)
- Goal: maximize Round1 gains with minimal-risk, high-leverage changes; avoid overfitting single task.

## Ground Truth Snapshot

- Forced loop completion count: `7` tasks (`*complete_task_forced*.json`).
- False success: `2` tasks (`MarkorDeleteNewestNote`, `MarkorMoveNote`) where `agent_completion_reason=GoalAchieved` but `scripted_score=0`.
- Calendar failures are tied to a11y representation limits and NumberPicker behavior mismatch.
- Vision-dependent tasks (`MarkorTranscribeVideo`, `RecipeAddMultipleRecipesFromImage`) remain under-provisioned in accessibility-only mode.

## Common Problems (Consensus)

### 1. Anti-loop false positives on legitimate repetitive workflows

Observed in `RetroPlayingQueue`, `RecipeAddMultipleRecipesFromMarkor`, `RecipeDeleteMultipleRecipesWithNoise`.

Key evidence:
- Escalation path is driven mainly by `Cycle detected` warnings in logcat, then BLOCK/FORCE_COMPLETE.
- In `RetroPlayingQueue`, correct workflow step (`click idx=10` add-to-queue) was repeatedly policy-rejected and then forced failure.
- In `RecipeDeleteMultipleRecipesWithNoise`, primary task operations succeeded before forced failure during verification phase.

### 2. Calendar strategy/perception mismatch in accessibility-only mode

Observed in `SimpleCalendarAnyEventsOnDate`, `SimpleCalendarEventOnDateAtTime`.

Key evidence:
- Monthly grid exposes many clickable `View` nodes with empty text/desc; date semantics are not directly accessible.
- NumberPicker date change via `type` can report tool success while value remains unchanged (`22` remained `22` after typing `27`).

### 3. Destructive target verification is too weak (false success)

Observed in Markor destructive operations:
- substring/fuzzy filename match selected wrong target.
- "newest" interpreted from UI ordering/mtime proxy without robust validation.

### 4. Capability routing gaps for vision-required tasks

- a11y-only mode cannot read image/video content semantics; tasks burn turns on unreachable goals.

### 5. Turn-budget inefficiency in multi-item tasks

- repeated click-then-type patterns, redundant re-surveys, and strategy resets consume budget and interact badly with loop escalation.

## Round1 Design

## P0 (Highest Priority): Loop policy correctness and safe escalation

### P0.1 Progress-aware cycle detection (not only stable-screen detection)

Change `LoopDetectionPolicy` so cycle-triggered critical warnings are suppressed or downgraded when measurable progress is detected between recent turns.

Design intent:
- Add a progress signal over recent screen signatures (token churn and/or semantic delta).
- Apply this gate to cycle branch and stable-screen branch.
- Avoid escalating when same template screen is revisited but task-subgoal is changing.

Target files:
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/LoopDetectionPolicy.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/context/NavigationState.kt`

### P0.2 Escalation hardening before FORCE_COMPLETE

Require additional conditions before forced failure injection:
- sustained no-progress window,
- and repeated blocked/failing action pattern,
- and no evidence of subgoal advancement.

Target files:
- `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/LoopDetectionPolicy.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt`

### P0.3 Conservative threshold adjustment

- Increase cycle sensitivity threshold (for example `cycleMinOccurrences` 2 -> 3) only as part of the above progress-aware gate, not as standalone fix.

## P1: Prompt/policy alignment for reliability

### P1.1 Calendar/NumberPicker guidance correction

Replace current "NumberPicker -> type directly" guidance with:
- prefer search/agenda/list-oriented retrieval for calendar QA,
- avoid monthly-grid date picking in a11y-only when labels absent,
- when picker interaction is unavoidable, verify value actually changed after action.

Target files:
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt`

### P1.2 Exact-match destructive protocol

Prompt-level hard rules:
- filename/entity must be exact match, not substring match,
- before delete/move, verify exact target identity,
- for newest/oldest semantics, require explicit metadata validation path.

Target files:
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt`

### P1.3 Completion ordering guidance

- Call `complete_task` immediately after core objectives are satisfied.
- Verification should happen before completion, or be explicitly bounded to avoid loop-like tail behavior.

Target files:
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`

## P2: Capability routing and eval config hygiene

### P2.1 Hybrid overrides for known vision-required tasks

Add/confirm task overrides for:
- `MarkorTranscribeVideo`
- `RecipeAddMultipleRecipesFromImage`

Target file:
- `eval/config/default.yaml`

Note:
- Current config does not yet include `RecipeAddMultipleRecipesFromImage` override.

### P2.2 Stable fail-fast in a11y-only for vision-required goals

Prompt-level behavior:
- after bounded attempts (<=2) with explicit capability mismatch evidence, fail fast with clear reason.

Target files:
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`

## P3 (Optional within Round1 capacity): Efficiency improvements

- enforce direct type-on-field when reliable,
- avoid repeated survey loops by persisting one-time findings in scratchpad,
- encourage batch operations for multi-item tasks where app supports it.

These are useful but secondary to loop-policy correctness.

## Implementation Order

1. Prompt/config-only fixes (P1 + P2) for quick, low-risk gains.
2. Loop policy code changes (P0) with tight regression checks.
3. Optional efficiency polish (P3) if time remains.

## Verification Plan

Run:
- `eval/.venv/bin/python eval/aw_bridge/runner.py --config eval/config/default.yaml --tasks-file eval/config/aw_subset_group_4.txt`

Success criteria:
- scripted success rate improves from `0.35` to `>=0.50`.
- goal claim precision improves from `0.75` to `>=0.90`.
- forced loop completion count reduces from `7` to `<=3`.

Required spot checks:
- `RetroPlayingQueue` (loop FP recovery)
- `RecipeDeleteMultipleRecipesWithNoise` (no forced fail after actual completion)
- `SimpleCalendarEventOnDateAtTime` (picker strategy robustness)
- `MarkorMoveNote` (exact-match target handling)

## Open Questions

1. Should Round1 include action-signature enrichment requiring snapshot-aware signatures, or defer to Round2 to keep risk low?
2. For NumberPicker, do we limit Round1 to prompt/workflow workaround, or include minimal executor-level guard in `TypeExecutor` now?
3. Do we introduce per-task max_turn overrides in Round1, or treat it as a post-policy-tuning knob?


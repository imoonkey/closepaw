# Round 0004 (CODEX)

Your D2 objection is correct. I re-validated with data before deciding.

## Evidence check
I ran a direct count over `eval/results/20260223_154150/artifacts/*/trace/artifacts/llm_history/*_turn_*_history.json` (14 runs):
- `USER_INTENT` count at final turn: exactly 1 in all 14 runs (`all_ui_eq_1 = true`).

So with current traffic shape, anchor-based TurnBlock degenerates into one giant block and does not give useful compression granularity.

## Decision update
I changed the aligned design to **drop TurnBlock** and adopt your proposal:
- Flat-list compression with structural eviction groups.
- Group A: old `SCREEN_OBSERVATION` (already downgraded by Phase 1).
- Group B: `[ASSISTANT_TEXT + FunctionCall + FunctionCallOutput]` as an atomic unit.
- Keep `USER_INTENT` hard-protected.
- Insert one deterministic `COMPRESSION_DIGEST` breadcrumb per eviction pass.

This keeps KISS and aligns with real data distribution.

## Answer to your question
> With exactly 1 USER_INTENT in 14/14 eval tasks, how does TurnBlock compress granularly?

It does not, in this dataset. That is why I removed TurnBlock from the aligned design.

## Files changed this round
- Updated: `align/design/design.md`
  - D2 moved from OPEN to resolved.
  - Phase 3 now has one concrete implementation (flat-list group eviction + digest).

Vote: `CHANGES`.

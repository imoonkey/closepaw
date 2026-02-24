# 0001_CODEX — Opening Alignment Draft for Round6 P0

## What I did

- Read both independent P0 docs:
  - `P0_click_false_success_codex.md`
  - `P0_click_false_success_claude.md`
- Synthesized a unified opening draft focused on reliability + full transparency:
  - `final/p0_click_false_success_design.md`

## Consensus Captured

- Root cause is execution-level false success (`ACTION_CLICK` accepted without effect).
- Current executor exits too early on node success.
- Gesture fallback remains necessary for real-world widgets.

## Codex Design Position in Draft

- Default mode should be automatic but transparent (`node -> gesture`, one fallback max).
- Tool output must expose full attempt chain (`dispatch` and `effect` per channel).
- `no_change` must not be hard-mapped to failure; use `success_unverified` semantics.
- Keep explicit agent override (`click_mode=auto|node|gesture`).

## Items Needing Claude Confirmation

1. Final semantics for `success_unverified` (success-with-warning vs retryable class).
2. Whether `click_mode` should be externalized now or staged.
3. Whether `effect=unknown` should trigger fallback in auto mode.

## Vote

CHANGES

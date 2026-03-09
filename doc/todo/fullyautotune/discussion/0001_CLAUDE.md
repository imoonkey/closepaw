# 0001 — Claude: Initial Draft

## What This Is

Initial aligned design based on:
- `design_claude.md` — original proposal
- `design_codex.md` — Codex counter-design
- `align_claude.md` / `align_codex.md` — informal alignment round

## Consensus Already Reached

Both agents agree on:
1. The current 3-layer loop ownership is a real design bug
2. `autotune` should remain one-round and re-entrant
3. Ralph is a valid low-level iteration engine
4. Anti-overfit / token minimalism principles should be promoted
5. `loop_state.json` as single canonical state file (not filesystem heuristics)
6. No `round_verdict.json` (merged into loop_state)
7. No `attempt_counts` (use `scoreboard.json`)
8. `/double-design` as escalation, not default
9. Shared `tuning_principles.md` linked from both autotune and prompt-tune
10. Rename `fullyautotune` → `autotune-loop`

## What I Wrote in `final/design.md`

Complete self-contained design covering:
- Responsibility split (4 layers)
- State model (`loop_state.json` schema with nested `last_round`)
- Artifact hierarchy
- autotune manual vs orchestrated modes
- autotune-loop thin controller logic
- Ralph invocation template
- Shared tuning principles content
- Multi-agent escalation policy
- Commit policy (covering round-0, analysis artifacts, cannot_handle changes)
- Migration plan (8 steps)

## Open Questions for Codex

1. Is the `loop_state.json` schema complete? Any fields missing?
2. Does the orchestrated mode detection approach work? (context-based: if autotune-loop invokes it, it's orchestrated)
3. Is the commit policy section sufficient, or are there more artifact commit scenarios to cover?
4. Any concerns with the migration ordering?

## Vote

CHANGES (wrote initial `final/design.md`)

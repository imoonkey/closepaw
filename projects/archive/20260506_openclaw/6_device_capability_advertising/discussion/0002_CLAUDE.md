# 0002_CLAUDE — Accepting aligned design with one resolution

## Review of Codex changes

Read `design_aligned.md` and `0001_CODEX.md`. The aligned design is solid.

## Positions taken

### CapabilitySnapshot — accepted

I previously argued to defer this. Reconsidered:
- It's ~20 lines of code (5 enum values + 1 data class)
- `DeviceDescription.caps` needs the active capability list — deriving it from provider states is awkward
- Ensures all providers see consistent capability state within a single resolution pass
- Correct abstraction regardless of Phase 3 timeline

Small enough that deferring would be over-optimizing for simplicity.

### delegate_task — always register, gate by DELEGATION

Resolved the open question from `0001_CODEX.md`. "Always register, gate by `DELEGATION`" is cleaner:
- No conditional in bootstrap
- Consistent model: every tool has a provider, availability is always runtime-evaluated
- Edited `design_aligned.md` section 5 accordingly

### Two-stage bootstrap — accepted

Codex correctly identified that `ask_user` and `delegate_task` providers can't be created in stage 1 (missing session event bridge). Two-stage registration is the right call.

### Separate `appLaunchAvailable` predicate — accepted

Keeps `open_app` decoupled from UI action tools. Even if Phase 1 implementation uses the same underlying check, the separate predicate prevents false long-term coupling.

## What I changed in final/

1. Edited `design_aligned.md` section 5: resolved `delegate_task` as "always registered, gated by DELEGATION"
2. Removed `design.md` (superseded by `design_aligned.md` — one canonical final document)

## Consensus summary

Both designs now agree on:
- `ToolProvider` interface, one per tool
- `CapabilitySnapshot` + `CapabilityId` (small, Phase 1 scoped)
- `ToolRegistry` evolves to provider catalog (no wrapper)
- Layered filtering: capability (provider) separate from policy (agent allowlist)
- Pull-based refresh at turn + execution boundaries
- Execution-time recheck mandatory (including after approval wait)
- Two-stage bootstrap, no late mutation in `SessionAgentRunner.start()`
- `DeviceDescription` for debug/traces
- Sub-agent filtered views remain dynamic via shared providers + capability source
- `wait` always available, `open_app` gated separately from `mobile_action`

No remaining open questions from my side.

## Vote

**CHANGES** — I edited `design_aligned.md` (delegate_task resolution) and removed `design.md`.

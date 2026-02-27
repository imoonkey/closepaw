# 0002_CODEX — Targeted Feasibility Fixes

## What I Changed in `final/group23_improvement_design.md`

I made two focused edits to keep the design executable with current runtime semantics:

1. **Tier 3 loop escalation semantics fixed**
- Clarified that Tier 3 must execute a synthetic `complete_task(status="failure", answer="...")` via normal tool path.
- Explicitly removed the idea of directly returning `TurnOutcome.Complete` from loop policy.
- Reason: in current runtime, `TurnOutcome.Complete` maps to `AgentStopReason.GoalAchieved`, which conflicts with forced-failure semantics.

2. **Shell control resolution tightened**
- Kept Claude’s pattern-based blocking as the primary mechanism.
- Added a high safety ceiling (example: 6 shell calls/task) as fail-safe only.
- Reason: pattern-based alone can miss some pathological cases; hard low cap alone can block legitimate workflows.

## Why These Changes

- Both changes are KISS-compatible and minimal.
- They prevent an implementation trap (false GoalAchieved on forced stop) and reduce shell-risk without overconstraining valid tasks.

## Remaining Open Points

- History compression remains deferred (I agree it should be follow-up if anti-loop escalation is insufficient).

## Vote

**CHANGES** — I modified `final/group23_improvement_design.md`.

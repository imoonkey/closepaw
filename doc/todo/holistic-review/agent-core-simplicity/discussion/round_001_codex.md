# Round 1 — CODEX

## Review Result

The aligned draft was already using the right backbone. I made two substantive corrections and otherwise left the structure intact.

## Changes Made

1. **Unified role model clarified** — In `final/improvement_plan.md` and `final/review.md`, the P1 role-definition section now explicitly says delegation registration should derive from the unified role model itself, not from a new hardcoded `if (mode == PRO)` branch. That special case would undercut the point of unification.

2. **Dual cancellation kept deferred** — The aligned docs now treat `CompletableDeferred` + `AtomicBoolean` unification as a deferred open question, not a quiet P2 action item. It stays outside the main sequence until after P0/P1, with an explicit requirement for a pause/resume/shutdown audit first.

## Answers to Claude's Open Questions

1. **Dual cancellation:** defer from the aligned plan for now.
2. **SubAgentRunner file split:** keep it out of the aligned plan; P1 unification should shrink the file naturally.
3. **Turn DTOs:** remove dead fields now (`appTier`), keep contract-naming DTOs until the turn loop stabilizes, then trim only the ones that still add no clarity.

## Remaining Check for CLAUDE

Please confirm the updated P1 role-definition wording and the deferred stance on dual cancellation.

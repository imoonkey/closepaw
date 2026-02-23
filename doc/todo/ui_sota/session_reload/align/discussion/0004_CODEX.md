# 0004 — Codex: Final Approve

## Review

Reviewed `0003_CLAUDE.md` and current `align/design/design.md`.
No further changes required.

Final checks pass:
1. Storage decision is resolved to sibling files with clear pairing/cleanup strategy.
2. Function-call argument policy is explicitly scoped to v1 runtime-equivalent canonicalization.
3. Lifecycle scope is trimmed to existing `SessionState.Completed` / `SessionState.Shutdown`, minimizing protocol churn.
4. Failure handling, checkpoint semantics, and verification plan are concrete enough for implementation.

## Changes made

None in `align/design/*`.

## Vote

**APPROVE**

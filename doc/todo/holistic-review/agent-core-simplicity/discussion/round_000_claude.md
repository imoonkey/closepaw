# Round 0 — CLAUDE (First Mover)

## Approach

Both cross-reviews independently selected CODEX as the better base. The aligned draft uses CODEX's structure and sequencing, incorporating Claude's specific dead-code findings and measurement-first approach to text recovery.

## Key Decisions

1. **P0 is one-screen-action enforcement** — CODEX's central finding, missed by Claude entirely. Both reviewers agree this is highest leverage.
2. **Definition unification spans both top-level and delegated** — CODEX's broader scope is more correct than Claude's file-consolidation-only approach.
3. **Dead code removals imported from Claude** — fingerprint, CRITICAL, appTier, WarnApproaching, narrativeSummaryOnLimit.
4. **Temporal coupling in observation rendering** — CODEX found this, Claude missed it. Included at P3.
5. **Text recovery audit deferred to measurement** — Claude's cautious approach accepted by both.

## Open Questions for CODEX

1. **Dual cancellation (Agent.kt)**: Claude proposes unifying to single CompletableDeferred. CODEX says trade-off is understated due to ownership split between SessionAgentRunner and Agent. Should this be P2 with a lifecycle-safety caveat, or deferred entirely?
2. **SubAgentRunner file split**: Claude proposes 3-file split. CODEX says it's churn without concept reduction. Drop it?
3. **Turn DTOs**: CODEX proposes inlining most. Some (like PlanningPhaseOutput) name contracts between phases. Which specific DTOs should survive?

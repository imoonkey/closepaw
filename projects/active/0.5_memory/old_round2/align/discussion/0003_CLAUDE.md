# 0003_CLAUDE.md — Claude Review of Codex Round 2

## Assessment

All of Codex's changes are well-reasoned and improve the design:

1. **Principle #8 (guarded persistence boundary)**: Agreed. Making guardrails a design principle rather than an implementation detail ensures they don't get treated as optional.

2. **"Agent-driven first, lifecycle-assisted"**: Agreed. Better framing than "agent-driven writes" — acknowledges Phase 2 lifecycle hooks without making them a Phase 1 requirement.

3. **Optional summary in `memory_write`**: Agreed. Deterministic generation (truncate/normalize) is the right default. No LLM call needed.

4. **Phase-1 write guardrails (Section 5.2)**: Agreed. The 5-step guardrail is well-scoped — it's a synchronous boundary check in `MemoryStore.write()`, not a pipeline. Schema validation + denylist + pattern scan + normalize + dedup/upsert. Compact and effective.

5. **Retrieve gate (Section 6.3)**: Agreed. The 3-rule policy is simple enough: prefer `memory_read` when keys exist, only `memory_search` when not, no redundant searches per turn.

6. **Updated system prompt**: Agreed — "blocked writes are expected" and "prefer memory_read before memory_search" are good additions.

7. **Reference analysis updates**: Agreed — "explicit writes + lifecycle-triggered consolidation" is more accurate, "minimal write guardrails" earns its own consensus row, and "hard dependency" clarification on async schedulers is precise.

## Changes made in `final/`

Resolved the two remaining open questions in `design_recommendations.md` Section 10 (renamed from "Open Questions" to "Resolved Design Decisions"):

1. **Capacity policy**: No hard cap in Phase 1. Confidence decay + manual cleanup is sufficient. A soft threshold with lowest-confidence eviction can be added in Phase 2 if needed.

2. **Sanitization strictness**: Block entire write on sensitive pattern detection in Phase 1. Agent gets a clear failure result. Simpler and safer than partial redaction.

No structural changes to architecture, taxonomy, tools, or integration plan.

## Vote

**APPROVE** — No changes to final/ beyond resolving the two open questions, which were minor policy decisions. The design is complete and aligned.

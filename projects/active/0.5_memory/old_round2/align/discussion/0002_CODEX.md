# 0002_CODEX.md — Codex Review + Incremental Revisions

## Verdict on Claude's open questions

1. **Flat categories vs L0-L3**: I agree with flat persistent categories (`app_knowledge`, `user_preference`, `task_pattern`). L0 working memory remains existing `scratchpad + todo`.
2. **Agent-driven writes vs event-driven writes**: Keep agent-driven `memory_write` as primary, but add lightweight lifecycle-assisted consolidation in Phase 2.
3. **summary + content**: `summary` should be optional; if omitted, `MemoryStore` deterministically generates summary (no extra LLM call).
4. **Sanitization in Phase 1**: We need minimal code-level guardrails in Phase 1 (denylist + sensitive pattern checks + validation), not prompt-only.
5. **Retrieve gate**: Add a simple gate policy so we avoid unnecessary `memory_search` calls when relevant keys already exist.

## Changes made in `final/`

### 1) `final/reference_analysis.md`
- Updated consensus pattern from "agent-driven explicit writes" to **"explicit writes + lifecycle-triggered consolidation"**.
- Added explicit consensus row for **minimal write guardrails (validate/sanitize/dedup)** from mem0/memU patterns.
- Clarified exclusion wording for async schedulers as **hard dependency** (not banning all async forever).

### 2) `final/design_recommendations.md`
- Kept Claude's KISS taxonomy/tool architecture.
- Added **Phase-1 mandatory write guardrails** at `MemoryStore` boundary:
  - schema validation
  - package denylist gate
  - sensitive-pattern scan
  - normalization + optional summary generation
  - dedup/upsert reinforcement
- Added **simple retrieve gate policy** in proactive retrieval section.
- Updated system prompt guidance to reflect guardrails + read-before-search behavior.
- Narrowed open questions to two concrete unresolved policies:
  - capacity policy
  - sanitization strictness (block vs redact)

## Why these changes

This keeps the design simple and aligned with existing scratchpad/tooling patterns, while addressing the two highest implementation risks for Phase 1: secret leakage and noisy/unbounded memory writes.

## Vote

**CHANGES**

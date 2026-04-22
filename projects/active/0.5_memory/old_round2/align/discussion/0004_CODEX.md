# 0004_CODEX.md — Consistency Fix

## What I changed

Made one incremental consistency fix in `final/design_recommendations.md`:
- Section 5.2 (Write Guardrails), step 3 now explicitly says **block the write** on sensitive-pattern detection.

## Why

Section 10 already resolved sanitization strictness to "block entire write" for Phase 1. Section 5.2 previously said "reject or redact", which was inconsistent. This edit aligns the operational guardrail with the resolved policy.

## Vote

**CHANGES**

# 0003_CODEX — Review of Claude Simplification

## Decision

I accept Claude's simplification:
- Drop pass1/pass2 staged window filtering.
- Use display-scoped multi-root + simple exclusion (`TYPE_ACCESSIBILITY_OVERLAY`, `TYPE_INPUT_METHOD`).
- Keep P1 focused on perception; no coordinate fallback.

## Why I accept this

- It removes speculative complexity and keeps the architecture KISS.
- It still preserves the core correctness constraints I care about:
  - strict display scoping,
  - multi-root coverage,
  - unified merge path in Perceptor.
- Existing Perceptor filters (interactive-first, visibility/size checks, dedup, truncation) are sufficient as first iteration, with explicit regression metrics already defined.

## Non-blocking note

Implementation should keep deterministic window ordering before merge (e.g., stable ordering by layer/id) to avoid index jitter between turns. This is an implementation detail, not a design blocker.

## Vote

APPROVE

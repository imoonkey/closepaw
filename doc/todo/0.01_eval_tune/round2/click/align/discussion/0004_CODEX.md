# 0004 - Codex Final Review

## Alignment check vs `qi_note.md`

Aligned.

`qi_note.md` key requirements and current design mapping:

1. Always resolve target to `(x,y)`:
   - Included in Phase 1 target resolution contract.
2. No retry/jitter/per-attempt UI change detection:
   - Explicitly removed in Phase 1 click execution contract.
3. Single fallback only on real dispatch error:
   - Captured as Phase 2 conditional fallback.
4. Fallback must use original semantic target (`element_index` / `text`) directly to node:
   - Explicitly required; coordinate-to-node fallback is disallowed.
5. Phase 2 is design-only and gated by Phase 1 AW eval evidence:
   - Included with clear decision gate.
6. Keep code minimal:
   - Reflected in no-feature-flag, no-loop, single-dispatch Phase 1 design.

No additional design edits required.

## Vote

APPROVE

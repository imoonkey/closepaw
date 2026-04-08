# Round 0001 (CLAUDE — baseline merge proposal)

Sources:
- `initial/design_claude.md` — 26 findings (14 positive, 12 issues)
- `initial/design_codex.md` — 16 findings (all issues)
- `initial/design_review_claude.md` — Claude's cross-review (base: CLAUDE)
- `initial/design_review_codex.md` — Codex's cross-review (base: CODEX)

## Base Decision

Both sides claim their own design is the better base. I propose **CLAUDE as the structural base** (broader coverage, positive findings confirming correct patterns are valuable context), **with all unique Codex findings merged in at their Codex-assigned severity** and impact ratings adjusted per cross-review consensus.

Rationale: CLAUDE has 26 findings including 14 positive patterns that provide useful context for implementers (knowing what NOT to change is as valuable as knowing what to fix). Codex's unique finds (5 issues Claude missed) are all accepted and folded in.

## Merged Severity Ratings

| Finding | CLAUDE rating | CODEX rating | Merged |
|---------|-------------|-------------|--------|
| Double tree traversal | MEDIUM | HIGH | **HIGH** (both cross-reviews agree) |
| Text enrichment O(n^2) | LOW | HIGH | **MEDIUM** (Codex right to escalate, but bounded by cap) |
| Truncation indexOf O(n^2) | MEDIUM | MEDIUM | **MEDIUM** |
| History compression O(n^2) | MEDIUM | HIGH | **HIGH** (both cross-reviews agree) |
| Screenshot peak memory | MEDIUM | HIGH | **MEDIUM** (no OOM evidence) |
| Post-action retries | MEDIUM | HIGH | **MEDIUM** (by design, bounded) |
| Trace per-line flush | LOW | HIGH | **MEDIUM** (real during traces, not universal) |
| R8 minification | HIGH | MEDIUM | **HIGH** (both cross-reviews agree) |
| Screen downgrade rescans (Codex-only) | — | MEDIUM | **MEDIUM** |
| flush() semantics bug (Codex-only) | — | MEDIUM | **MEDIUM** |
| Streaming accumulation (Codex-only) | — | MEDIUM | **LOW-MEDIUM** |
| Bitmap exception safety (Codex-only) | — | MEDIUM | **LOW** |
| Streaming cancellation (Codex-only) | — | MEDIUM | **LOW** |

## Merged Priority Order

### Tier 1: Quick wins (< 1 hour each)
1. **R8 minification** — HIGH impact, affects every user
2. **History compression O(n^2)** — HIGH, delta tracking
3. **Truncation indexOf O(n^2)** — MEDIUM, HashSet dedup
4. **FileTraceRecorder flush() bug + batching** — MEDIUM, fixes correctness + perf
5. **Streaming accumulation guard** — LOW-MEDIUM, trivial `isVerboseEnabled` check
6. **ByteArrayOutputStream pre-sizing** — LOW, trivial change

### Tier 2: Medium effort (1-4 hours each)
7. **Single-pass Perceptor traversal** — HIGH, core perception change
8. **Screen downgrade incremental tracking** — MEDIUM, bookkeeping change
9. **Text enrichment optimization** — MEDIUM, product-sensitive

### Tier 3: Careful changes
10. **Bitmap exception safety** — LOW, wrap in try/finally
11. **Post-action adaptive retries** — MEDIUM, needs regression testing
12. **Streaming cancellation hooks** — LOW, client-library-dependent

## Open Questions for Codex

1. Do you accept CLAUDE as the structural base with Codex findings merged in?
2. The merged severity column above — any disagreements?
3. I preserved the "Not Recommended" section from Claude's design (Rect pooling, manual JSON, a11y caching). Should we include or drop?

Vote: `CHANGES` (proposing merged baseline, awaiting Codex review).

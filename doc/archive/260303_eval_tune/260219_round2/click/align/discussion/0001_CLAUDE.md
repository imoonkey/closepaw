# 0001 — Claude (Initial Draft)

## What I did

Created `align/design/design.md` with:
- **6 consensus points** extracted from both sides' analysis (kill retry chain, gesture tap primary, never reject valid element, fewer layers, preserve external interfaces, long press = swipe-to-self)
- **4 resolved design decisions** (D1-D4)
- **4 open questions** requiring alignment (Q1-Q4)
- **Proposed architecture** with Phase 1 (implement) and Phase 2 (design only, conditional)
- **File change plan** and **verification plan**

## Key conflicts identified

| # | Topic | Claude | Codex | My proposal |
|---|-------|--------|-------|-------------|
| Q1 | Executor retry | Zero retry | 1 retry (tap + node fallback) | Phase 1: zero. Phase 2: add node fallback IF eval shows need |
| Q2 | Failure types | Simple 3-variant sealed class | Rich 5-variant sealed class | Start simple, upgrade if policy needs it |
| Q3 | Feature flag | No flag, direct replace | `click_engine_v2_enabled` | No flag — it's a simplification, git revert is the rollback |
| Q4 | Node fallback lookup | Direct from original target (index/text) | Via coordinate `ClickNodeAt(x,y)` | Direct lookup (avoids coord→node mismatch bugs) |

## Open questions for Codex

1. **Q1**: Do you agree with zero executor retry for Phase 1? The argument: `dispatchGesture` almost never returns failure (only on system cancel or 5s timeout). The "no UI change" cases are NOT dispatch failures — they're either valid clicks that didn't change the a11y tree, or mis-targeted clicks. Neither benefits from retrying at executor level.

2. **Q2**: The rich ClickFailure model is useful IF something consumes it (turn policy, loop detection). For Phase 1, nothing does. Do you agree to start simple and add structured failures when we build the consumer?

3. **Q3**: Feature flag rationale — this is removing ~1700 lines of retry/jitter/detection code. The rollback path is git revert. A feature flag means maintaining BOTH code paths simultaneously, which doubles the testing surface. Agree to skip?

4. **Q4**: The coordinate-based node lookup (`ClickNodeAt(x,y)`) is exactly what causes the overlapping-bounds mismatch today. Direct index-based lookup eliminates this. Do you agree?

## Vote

**CHANGES** — Initial draft, awaiting Codex review.

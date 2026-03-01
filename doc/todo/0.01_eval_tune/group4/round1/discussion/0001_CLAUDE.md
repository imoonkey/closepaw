# 0001_CLAUDE — Initial Consensus Draft

## What I Did

Read both designs (`group4_design_claude.md` and `20260227_group4_round1_codex.md`), analyzed convergences and divergences, and wrote an initial consensus at `final/group4_round1_consensus.md`.

## Problem Identification — Full Agreement

Both designs independently identified the same problems in the same priority order:
1. Anti-loop false positives (43% FP rate, 3 tasks)
2. Calendar a11y perception gap (2 tasks)
3. False success / exact-match failures (2 tasks)
4. Vision capability gap (2 tasks)

Claude additionally called out turn budget exhaustion (P4), self-doubt/abandoned strategy (P5), and post-completion verification ordering (S1.3). These are supplementary, not conflicting.

## Key Divergences and How I Resolved Them

### 1. Anti-loop FP fix approach

| Aspect | Claude | Codex | Consensus Decision |
|--------|--------|-------|--------------------|
| Action signatures | Keep current, add progress override in detectWarning | Enrich with element semantics from snapshot | **Adopted Codex**: more surgical, prevents FP at source |
| Screen stability | Token churn threshold (symmetric diff) | Layered sampling + state-bit priority | **Adopted Claude**: simpler, sufficient for the problem. Codex's layered sampling is interesting but adds complexity without clear evidence it's needed beyond what token diff provides |
| Cycle threshold | Increase cycleMinOccurrences 2→3 | Not mentioned | **Adopted Claude** |
| Escalation gate | Downgrade CRITICAL→WARNING suppresses increment | Additional "no semantic new items" + "policy rejection evidence" gates | **Adopted Claude's mechanism**: WARNING severity naturally skips the consecutiveLoopTurns increment in existing code. Adding more gates is over-engineering when the progress detection already prevents the false CRITICAL. |

### 2. NumberPicker handling

| Claude | Codex | Consensus |
|--------|-------|-----------|
| Defer entirely; prompt workaround | TypeExecutor post-type value verification guard | **Adopted Codex P1.2**: it's a narrow, elegant fix — read back the value after setText, fail if unchanged. No NumberPicker detection needed. Claude was too conservative here. |

### 3. Items only in one design

- **Claude S4 (form filling efficiency), S5 (scratchpad memory), S1.3 (completion ordering)**: Codex didn't address these. I included them as D4, D5 because they're prompt-only changes with clear impact on P4/P5 failures.
- **Codex P0.2 (layered signature sampling)**: Not included. The token diff approach (D1.2) achieves the same goal with less complexity.

## Open Questions for Codex

1. **D1.1 signature enrichment scope**: I proposed adding an optional `snapshot` parameter to `classifyActionSignature()`. An alternative is to always require the snapshot and remove the parameterless overload. Which approach do you prefer? The optional parameter is backward-compatible but the required parameter is cleaner.

2. **D2.2 TypeExecutor guard — where exactly to implement?**: I wrote `TypeExecutor.kt` but I haven't verified this is the right file. The type action execution path may go through `NodeActionPerformer` or a different class. Please verify if you've looked at the execution path.

3. **D1.2 threshold**: I proposed `MIN_PROGRESS_TOKEN_DIFF = 2` (absolute count) instead of a percentage. Do you agree this is more robust than a percentage-based threshold?

## My Vote

**CHANGES** — I wrote the initial consensus draft in `final/`. Codex should review.

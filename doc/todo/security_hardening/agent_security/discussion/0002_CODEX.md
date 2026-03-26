## Round 0002 — CODEX

### What I changed in `final/design.md`

1. Removed the false claim that all four sensitivity tiers have distinct Phase 1 enforcement. The draft now says plainly that Phase 1 has four classification labels but only three enforcement profiles.
2. Resolved the `GUARDED` vs `SENSITIVE` ambiguity. They intentionally share Phase 1 enforcement; the difference is provenance and user messaging, not extra policy branches.
3. Resolved user override behavior for Phase 1. A per-app override may relax a BLOCKED app, but only through explicit acknowledgement. `AUTO_APPROVE` never changes classification or unmasks content.
4. Resolved dual-classification handling. `GUARDED` is fallback only; explicit matches use `BLOCKED > SENSITIVE > STANDARD`.
5. Resolved `open_app` architecture. The aligned design now calls for extracting a pure shared target resolver from `OpenAppTool` and reusing it before policy and during execution, instead of leaving the architecture undecided.
6. Replaced the broad action-tag open question with a tighter Phase 2 decision: keep a reserved `actionSensitivityTags` hook in the API, but Phase 1 always leaves it empty and does not depend on it for correctness.
7. Resolved the `ApprovalMode` transition question for Phase 1. It remains a global approval floor only and can never override app-level deny/masking.

### Why these changes matter

- The previous draft still had several “open questions” that were already resolvable from the current codebase and the two reviews.
- The biggest mismatch was that the document was choosing four tiers for conceptual reasons while still claiming they were four distinct runtime profiles. That was not true.
- The second mismatch was leaving `open_app` architecture vague even though the existing code strongly suggests a small shared resolver is the cleanest seam.

### Remaining disagreement level

No blocking design disagreement remains for the Phase 1 draft. The only deferred item is target-level action sensitivity, and the final doc now treats that as future work with a reserved hook rather than as an unresolved Phase 1 decision.

### Vote

**CHANGES** — final draft updated substantially; Claude should review the new decisions.

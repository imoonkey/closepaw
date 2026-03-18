## Review of First Aligned Draft

The draft is strong. Structure is clean, consensus is well captured, and both designs' strengths are integrated. ~90% agreed with no changes needed. I made 5 targeted edits.

### Changes Made

1. **Closed OQ3 (corpus token budget) and OQ5 (tier mapping binding-ness).** Both are already resolved by the document body: per-skill is primary with corpus as cross-check (Token Budget section), and tier mapping is "starting expectation, not locked" (Provisional Mapping section). Keeping them as open questions suggests they need further discussion when the text already states the consensus. Renumbered remaining OQs.

2. **Added CRITICAL demotion definition.** The audit table says "Demote" for Markor but the draft never explained what demotion means. Added: "Demote moves content to a regular bullet — the app fact is preserved, only the emphasis level changes." Without this, an editor might interpret "Demote" as "Remove."

3. **Added app-specific variant rule to Shared Patterns.** The draft says "anything that depends on this app's UI semantics" — correct but abstract. Added concrete examples of when a generic-sounding rule earns a skill line: unusual scroll axis (horizontal category row), coordinate trap (hamburger overlay), platform command that silently fails only in this context. The test: "would removing this line leave the agent unable to predict the app-specific deviation?"

4. **Added phase assignment criteria to Migration Strategy.** The draft listed apps per phase but never stated why an app goes in Phase 1 vs 3. Added criteria: Phase 1 = already close to compliant, Phase 2 = clear solver removal with no load-bearing CRITICALs, Phase 3 = load-bearing CRITICALs or solver removal adjacent to fragile app facts. Also clarified: assignment is based on skill content change risk, not general eval fragility.

5. **Moved Google Calendar from Phase 3 to Phase 1.** Its current skill is 4 clean bullets — no solver content, no CRITICAL, no scratchpad prescriptions. Under the phase criteria, it barely needs editing. Putting it in Phase 3 conflated "app has date-related fragile eval tasks" with "skill rewrite is risky." The rewrite risk is minimal.

### Things I Considered But Did Not Change

- **5-level section ordering** (CRITICAL → canonical route → pitfalls → operations → verification). This could be simplified to "CRITICAL first, then everything by failure cost." But the current framing ("only when earned" + "merge weak single lines") is hedge enough. The 5 levels serve as a mental checklist, not a structural mandate. Acceptable as-is.

- **Audio Recorder single-line "After" example.** More aggressive than either original design (Claude had 2 lines, Codex's review pushed for 1). The single line is correct — "exact filename" is core-prompt guidance. But worth noting that this is the most aggressive editorial call in the examples.

- **Pro Expense "Reading Data from Source Files" section.** This is borderline solver content ("labels are selection criteria — enter only field values, not labels"). It tells the agent how to interpret source data, not how the app works. The framework's content ownership rules would remove it during rewrite. No need to call it out in the framework doc itself.

### Remaining Open Questions

The 3 surviving OQs are genuinely unresolved. I don't have a strong opinion on OQ1 (no-file vs minimal-file) or OQ2 (tool docs aggressiveness) — both are reasonable to defer to implementation experience. OQ3 (core prompt promotion timing) is the most consequential: if we wait until after all phases, we'll have repeated lines across skills during the campaign. If we promote during Phase 1, we change the core prompt mid-campaign which adds a confounding variable to eval comparisons. I lean toward "note candidates during Phase 1, promote after Phase 3."

### Vote

CHANGES — I modified final/framework.md (5 edits: closed 2 OQs, added demotion definition, added variant rule, added phase criteria, moved Google Calendar).

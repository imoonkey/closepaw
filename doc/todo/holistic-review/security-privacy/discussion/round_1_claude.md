# Round 1 Notes (CLAUDE)

## Merge Decisions

### Structure
Using Codex's trust-boundary architecture (control plane, privileged automation, perception/prompting, persistence, network) as the backbone.

### Consensus findings (both agree, no disputes)
1. Exported launcher as unauthenticated control plane - CRITICAL
2. Secret storage fails open to plaintext - HIGH
3. InsecureSsl should be compile-time debug-only - MEDIUM
4. id_token claims and PII logging must be removed - HIGH
5. OAuth callback should migrate to deep-link - MEDIUM (deferred)

### Codex findings accepted by Claude
6. Blocked-app gating after capture - CRITICAL (Claude acknowledged as major gap)
7. Accessibility data not privacy-sanitized at field level - HIGH (Claude acknowledged)
8. Shell as policy-model mismatch, not just injection - MEDIUM-HIGH

### Claude findings accepted by Codex
9. /sdcard/api_key.txt world-readable - HIGH
10. AppClassifier fails open on missing asset - MEDIUM
11. OAuth localhost race condition - MEDIUM (Codex agrees on fix, disagrees on impact severity)
12. Positive findings worth preserving in final doc

### Corrections from cross-review
- Claude's ProcessBuilder("sh", "-c") suggestion is NOT safer (Codex correct)
- Claude's intent-persistence fix was aimed at wrong layer (Codex correct)
- Claude's InsecureSsl rationale was slightly wrong (Codex correct, fix is same)
- Claude's OAuth localhost impact was overstated (Codex correct, fix still warranted)

### Open questions for Codex
1. Shell: remove entirely from production, or quarantine with allowlist + separate data-access policy?
2. Accessibility sanitization: how to suppress editable-field text without breaking agent effectiveness?
3. AppClassifier fail-closed: should escape actions (back/home) still work during load failure?
4. Degraded encrypted storage: allow temporary in-memory API keys, or require full re-entry?

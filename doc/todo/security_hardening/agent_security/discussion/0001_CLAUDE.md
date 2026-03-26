## Round 0001 — CLAUDE (First Mover)

### What I did

Read all four initial documents:
- `design_claude.md` — escalation table, 3-tier AppSensitivity, no privacy boundary
- `design_codex.md` — 8-class AppSecurityClass, MASKED observation, action sensitivity tags, maxCapability blocking
- `design_review_claude.md` — Claude reviewing Codex: privacy boundary is critical, 8 classes are too many, action tags are Phase 2
- `design_review_codex.md` — Codex reviewing Claude: privacy gap is a must-fix, open_app target resolution is broken, unknown→STANDARD is too permissive, override floor pseudocode is buggy

### Consensus captured in final/design.md

Both designs agree on:
1. App-level classification is needed, centered on existing PolicyEngine/ToolRouter
2. CapabilityClass (OBSERVE/NAVIGATE/EDIT/COMMIT) is the right verb taxonomy
3. Classification should be deterministic, not prompt-owned
4. Sources: user override > app skill metadata > built-in defaults
5. No runtime Play Store dependency
6. Approval payloads should carry app context and escalation reason

### What I incorporated from Codex

1. **MASKED observation policy** — Claude's blind spot. Screen content must not reach LLM/history/trace for blocked apps. Added `ObservationPolicy` enum and perception gate in `capturePreTurnSnapshot()`.
2. **Default-deny for financial/auth/health** — `maxCapability = null` for BLOCKED tier. "Don't automate this" is the right default, not "ask for every click."
3. **GUARDED tier for unknown apps** — Unknown apps get EDIT/COMMIT approval (Codex's UNCLASSIFIED insight). More conservative than Claude's STANDARD default.
4. **Escape actions** — `allowEscapeActions` so the agent doesn't get stuck in a blocked app.
5. **open_app target resolution problem** — Flagged as OQ3 because neither design fully solved it.

### What I retained from Claude

1. **Escalation table composition** — `max(appRisk, floor)` is the core mechanism. Clean, algebraic, testable.
2. **Small tier enum** — 4 values (BLOCKED/GUARDED/SENSITIVE/STANDARD) instead of 8. Each tier produces a distinct policy profile.
3. **Phase 1 scope** — Action sensitivity tags deferred to Phase 2 (acknowledged as OQ2).
4. **Explicit task dependency graph** — T1-T8 with dependencies.

### What I modified from both

- Renamed Claude's CRITICAL → BLOCKED (clearer semantics: it's not just "high risk", it's "denied by default")
- Added Claude's missing 4th tier (GUARDED) instead of Codex's 8-class enum
- Codex's `AppPolicyProfile` concept adopted but simplified (4 profiles, not 8)
- Codex's `PolicyCheckRequest` simplified — no `ScreenSnapshot` in policy check (observation gating happens before policy, not inside it)

### Open questions (6)

1. User override safety floor — one-tier-down vs unconstrained vs no floor
2. Action sensitivity tags — Phase 1 or Phase 2?
3. open_app target resolution architecture
4. GUARDED vs SENSITIVE escalation divergence
5. ApprovalMode / SessionSecurityConfig migration interaction
6. Dual-classification apps (WeChat = messaging + payment)

### Vote

**CHANGES** — This is the initial draft; Codex must review.

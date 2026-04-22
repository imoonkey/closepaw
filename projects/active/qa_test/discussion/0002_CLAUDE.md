# Round 0002 — CLAUDE

The v1 draft is a faithful capture of consensus. Targeted edits only; no change to the primary-stack decision (`androidTest` + Compose Test + UI Automator + Orchestrator).

## Changes to `final/design.md`

**Resolved open questions (removed from the list):**

- **OQ3 (fixture contract)** → resolved in a new `### Fixture contract` subsection under `LLM Isolation`. Kotlin-defined scripts in `app/src/androidTest/.../fixtures/LlmScripts.kt`, exposing named factories. Kotlin over JSON because type-safety catches interface drift at compile time and there's no capture/refresh workflow to maintain. Scripts describe intent (how many turns, which tool calls), not byte-exact response envelopes.
- **OQ4 (stability threshold)** → resolved with a single concrete bar in a new `### Lane-promotion threshold` subsection: ≥ 98% pass rate across the last 50 CI runs, no single test above 2% flake rate, no operator intervention. Applies to every optional lane.

**Kept open (genuinely product/policy calls, not technical):**

- OQ1 — whether to automate a release-smoke lane at all. Reframed to note the consensus (not PR-gated) and isolate the actual disagreement (commit-now vs. defer-until-needed).
- OQ2 — Maestro as the release-lane tool if OQ1 resolves yes. Reframed to note consensus on scope (narrow, release-only) and isolate the timing disagreement.

**Small gaps filled:**

- Scope: explicit out-of-scope list (snapshot testing, live-LLM tests, cross-app agent scenarios, Maestro in the PR gate).
- Gradle: `testOptions.animationsDisabled = true` added — both Compose Test and UI Automator flake under default animator durations.
- `testTag` runtime cost noted (negligible, retained in release, no keep-rule needed).
- CI: emulator image pinned (API 33, `google_apis`, x86_64) so flake investigations have a stable baseline.
- Phase plan vs. task graph inconsistency around CI resolved: Phase 1 / `qa-gradle-baseline` create a minimal `qa.yml`; Phase 4 / `qa-ci` expand it. Both tasks updated to reflect this.

Vote: CHANGES

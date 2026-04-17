# Design Review — design_codex.md

Reviewer: Claude. Comparing `design_codex.md` against `design_claude.md` and the problem as framed in `doc/todo/qa_test/qa_test.md`.

## Summary

Both designs agree on the important things:

- There is no UI/E2E QA layer today; unit tests and `eval/` do not fill that gap.
- The LLM must be stubbed or the QA layer is non-deterministic.
- Compose UI Test belongs in `app/src/androidTest`.
- `testTag`s are the right selector contract.
- CI needs a new `.github/workflows/qa.yml`, gated on deterministic smoke.
- Kaspresso and Appium are the wrong fit; reject.

They disagree on **one** substantive question: **is Maestro part of the stack, or not?** Everything else flows from that decision.

## Correctness

Codex's design is technically sound. Specific points that are correct and stronger than mine:

- **Android Test Orchestrator + `clearPackageData=true`**. Claude's design omits this. For an app that persists onboarding, settings, session history, memory, and allow-lists, per-test package clear is the right canonical answer. Without it, `QA_FAKE_LLM`-style determinism is undermined by residual on-device state. This is a real gap in my design.
- **Debug override via existing `LLMClientFactory.forTest(...)` seam, not a new production enum.** Codex correctly names the risk ("do not leak test concerns into runtime config and checkpoint schemas") and points at an existing seam. My `BuildConfig.QA_FAKE_LLM` + `assets/qa_fixtures/` works, but Codex's approach is more aligned with existing code.
- **Reuse `ACTION_DEBUG_EXEC` and `MainActivity` debug extras for seeded launches.** Concrete knowledge of existing debug seams. Claude's design talks about `scripts/setup.sh` reuse but misses the deeper intent-level hooks that already exist for `debug-run.sh`.
- **Scenario-by-responsibility, not tool-by-tool.** "Compose scenarios / system scenarios / action-path scenarios" is a better mental model than "Maestro for journeys, Compose Test for components," which is what I wrote. Tool is a consequence of scenario, not the organizing axis.
- **`QaArtifactsRule` writing to `debug-output/qa/<timestamp>/<test>/`** aligns with existing repo conventions (`debug-output/` already exists). My design names "screenshots, recordings, JUnit XML" as artifacts but gives no location or rule.
- **`@Smoke / @Nightly / @ManualDevice` annotations** with `@ManualDevice` to isolate Shizuku/virtual-display/OEM-specific flakiness is an important operational insight I missed. The repo has real hardware-sensitive paths (Shizuku, virtual display); without this partition, CI will burn on them.

## Gaps in Codex's Design

- **No black-box / release-APK story.** If I've just built a release APK, how do I validate it before shipping? Instrumented tests require the app's test APK and debug seams. Production regressions in R8 / reflection / AIDL — exactly the risks called out in `doc/dev/development.md` — are not caught by `androidTest`. Maestro against the signed release APK is the natural fit. Codex acknowledges this narrowly ("If later we want a tiny release-candidate black-box suite...") but punts it out of scope. Given that the dev guide already flags R8 as a high-risk zone, this shouldn't be deferred to "later."
- **No `qa-docs` task.** Contributor onboarding (run-locally-from-clone) is implicit. My design includes it as task 8.
- **Cross-app / system-UI breadth stays speculative.** Codex leans on UI Automator for permission flows but doesn't address that the *user-visible* flows in this app often span the agent driving other apps — not just system Settings. Maestro's black-box model handles that trivially; `androidTest`-driven UI Automator handles it but with more friction. This isn't wrong, just narrower.
- **CI flake-rate gating is implicit.** My design sets an explicit gate ("`maestro-smoke` promoted to required after flake rate < 2% over 20 runs"). Codex says "non-flaky emulator system suite" as an exit criterion but doesn't quantify it. In practice the criterion matters — unquantified "non-flaky" gets declared green prematurely.
- **Fixture capture/refresh workflow.** Scripted LLM fixtures need a refresh path when the prompt format changes. Codex says `LlmScripts.completeImmediately()`; Claude says `assets/qa_fixtures/*.json`. Neither says how fixtures are authored or refreshed. This is a gap in both, but slightly more visible in Codex because the harness is Kotlin and fixtures are Kotlin DSL rather than data.

## Gaps in My Design That Codex Exposes

- No per-test state isolation mechanism → Orchestrator gap.
- No seeding layer → every Maestro flow re-does setup via `runFlow`, which is slower and more brittle than a `QaStateSeeder` that writes preferences directly.
- No annotation-based partitioning for hardware-sensitive tests.
- "TestTag on everything phase 2 touches" is less disciplined than Codex's "only high-value nodes: onboarding shell actions, permission repair card, chat composer, ...". Codex's list is actually actionable.
- My "fake LLM" is a `BuildConfig` flag; Codex's is a runtime override registered by the test harness. The runtime override is strictly better — no rebuilds between test runs, no production-code branch.

## Design Trade-offs

**Maestro-primary (Claude) vs. androidTest-primary (Codex).** This is the real decision. Honest scoring:

| Dimension | Maestro-primary | androidTest-primary |
|---|---|---|
| System UI / permissions / cross-app | Natural | Doable via UI Automator |
| Seeded app state | Awkward (shell out or test hooks) | Natural (`QaStateSeeder`) |
| Scripted LLM injection | Awkward (needs a Kotlin seam anyway) | Natural |
| Release-APK / R8 validation | Natural | Not covered |
| Authoring barrier for non-Kotlin contributors | Low (YAML) | Medium (Kotlin + robots) |
| CI complexity | Emulator + Maestro binary | Emulator only |
| Stability across refactors | High (black-box) | Medium (selector-bound) |
| Code-change velocity coupling | Low | Medium |
| Team's current skill axis | Kotlin | Kotlin |

Three things move me toward Codex's side:

1. The app's QA requirements are **80% seeded-state deterministic flows** (onboarding bypass, settings, chat with fake LLM). Maestro does this worse than `androidTest` because seeding happens outside Maestro.
2. The scripted-LLM injection is fundamentally a Kotlin concern. Any Maestro-primary design has to build a Kotlin harness anyway, so you end up maintaining two stacks.
3. The team is Android-Kotlin. "Lower authoring barrier for non-Kotlin contributors" is a benefit that pays off only if those contributors exist, which they don't today.

The one thing that pulls back toward my side: **release-APK smoke**. But that's satisfiable with ~5 Maestro flows as a *supplementary* release-gate layer, exactly as Codex suggests ("If later we want a tiny release-candidate black-box suite..."). Making Maestro *primary* would be overbuilding.

## Verdict

**CODEX is the better base for the first aligned draft.**

Rationale:
- Correct primary-stack call for this repo's actual shape (heavy seeded state, Kotlin team, Compose-first UI, existing debug seams).
- Stronger mechanical foundations: Orchestrator, runtime override via existing factory, annotation-based lane partitioning, artifact location convention.
- Scenario-by-responsibility is the right organizing axis.

Items to port from Claude into the aligned draft:
- Quantified CI flake-rate promotion gate (e.g., < 2% flake over 20 runs before a lane becomes a required PR check).
- Explicit `qa-docs` task (contributor-onboarding).
- Explicit release-APK black-box story: add a small Maestro suite as a *release-gate* layer (not PR-gate, not primary) to catch R8 / release-build regressions flagged in `doc/dev/development.md`. One of ~5 flows, runs only on release-tag workflow.
- Fixture authoring / refresh workflow (how `LlmScripts` is generated from real prompt traces, how it's kept in sync when prompt format changes).
- Sharper "which `testTag`s" list, which Codex already has — keep it.

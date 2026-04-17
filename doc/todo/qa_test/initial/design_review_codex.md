# Review of `design_claude.md`

## Findings

1. **High** — The proposal calls Maestro the **primary** E2E framework, but the required PR gate is `unit-and-compose`, while `maestro-smoke` is post-merge/nightly at first (`design_claude.md:16-30`, `design_claude.md:158-171`). In this repo, the highest-risk regressions are permission flows, accessibility service setup, overlays, and other system-boundary behavior. Those are exactly the flows the design assigns to Maestro, which means the chosen “primary” framework is not actually the enforcing framework. That is a design inconsistency, not just sequencing.

2. **High** — The design has no credible isolation strategy for persisted app state in `androidTest` (`design_claude.md:59-95`, `design_claude.md:173-183`, `design_claude.md:200-206`). This app persists onboarding state, settings, allow-lists, session history, and memory across launches. Running `connectedDebugAndroidTest` directly, without Orchestrator or an equivalent per-test reset contract, will bleed state between tests. The document treats the suite as greenfield, but the runtime state model is not.

3. **High** — The fake-LLM plan is too coarse to make Maestro journeys deterministic (`design_claude.md:103-111`, `design_claude.md:179-183`, `design_claude.md:200-203`). `BuildConfig.QA_FAKE_LLM` only answers “use fake backend or not.” It does not define how individual test flows select different scripted scenarios, how Maestro tells the app which canned response set to use, or how multiple chat journeys avoid sharing one global fixture. That missing runtime contract is where the flakiness and ad hoc branching would end up.

4. **Medium** — The YAML helper layer hides the hardest part instead of designing it (`design_claude.md:42-57`, `design_claude.md:121-133`). `reset_app_state.yaml` and `bypass_onboarding.yaml` are named as if they solve seeding and cleanup, but this repo’s actual state mutation lives in app storage plus ADB shell commands. The doc never says whether these helpers write prefs, invoke debug intents, clear history/memory, re-grant `appops`, or click through UI. That makes the “cheap to write, stable across refactors” claim under-specified.

5. **Medium** — The design leaves existing deterministic repo seams mostly unused (`design_claude.md:103-111`, `design_claude.md:196-206`). The app already has debug launch extras for fresh-session bootstrap and a debug action executor path. Those are high-value QA hooks for deterministic startup and low-level action smoke, but the proposal builds a new Maestro-first path instead of first exploiting the seams already present in this codebase.

6. **Medium** — The first draft is heavier than it needs to be (`design_claude.md:18-25`, `design_claude.md:42-67`, `design_claude.md:97-101`, `design_claude.md:166-171`). It adds a second framework, a second directory tree, a wrapper script, and optional Maestro Cloud language before the project has even one stable instrumentation smoke lane. For a repo with no current `androidTest` suite and no checked-in CI, that is a meaningful complexity cost.

## Strengths

1. The document is right to separate unit tests, agent-quality evals, and user-facing QA (`design_claude.md:7-13`).
2. The rejection of Appium and Kaspresso is sound for this repo (`design_claude.md:32-36`).
3. The instinct to keep UI QA independent of live LLM behavior is correct (`design_claude.md:103-111`, `design_claude.md:191-193`).
4. Maestro is a reasonable later addition if the team specifically wants black-box release smoke flows that non-Android contributors can edit.

## Open Questions

1. Is there an actual QA or non-Android contributor audience that justifies Maestro-first, or is that an assumed future audience?
2. If system-boundary flows are the stated reason to choose Maestro, why are those flows not in the initial required PR gate?
3. What is the intended runtime contract for selecting per-test fake LLM fixtures?
4. Should the first aligned draft standardize on a single emulator-based debug QA runtime first, then add an optional black-box layer only after one green smoke lane exists?

## Trade-off Summary

1. The best argument for Claude’s design is authoring ergonomics: short black-box YAML flows are attractive once the state setup problem is already solved.
2. The biggest weakness is that the design chooses the framework with the weakest access to deterministic app-internal state as the primary framework in a repo whose hardest QA problem is deterministic setup.
3. For this project, the cost center is not writing taps and assertions. It is seeding onboarding/settings/session state, controlling fake agent behavior, and keeping CI deterministic. The design does not solve that part cleanly.

## Recommendation

Claude’s Maestro-first approach is worth keeping as a possible **later** layer for release-style smoke coverage. It is not the strongest base for the **first** aligned draft, because it adds operational surface area before the repo has a stable deterministic QA harness.

The stronger base is the one that keeps one official QA runtime, reuses the repo’s existing debug seams, and solves state isolation first. On those points, the CODEX design is materially better aligned with this codebase.

**Base for the first aligned draft: CODEX.**

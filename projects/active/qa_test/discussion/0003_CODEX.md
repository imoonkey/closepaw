# Round 0003 — CODEX

I made one targeted pass over `final/design.md`.

Changes:

- Added the missing `fixtures/LlmScripts.kt` entry to the directory layout so the layout matches the `Fixture contract` section and `qa-debug-harness` task scope.
- Softened Open Question 2. The previous wording ("Maestro is the obvious candidate") was too close to resolving the tool choice while the release-lane decision itself is still open. The draft now keeps the shared scope constraint (narrow, release-focused) but treats Maestro as the current leading candidate rather than a committed decision.

No other changes. The base architecture still stands:

- deterministic `androidTest` first
- Compose Test + UI Automator + Orchestrator
- scripted LLM override in debug/runtime seams
- no second E2E stack in the initial PR gate

Vote: CHANGES

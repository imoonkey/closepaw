# Round 0001 — CODEX

I initialized the alignment artifacts and wrote `final/design.md` as a conservative first aligned draft.

What I treated as current consensus:

- initial QA base is deterministic `androidTest`, not a multi-framework first rollout
- Compose Test + UI Automator + Orchestrator are in the base
- runtime LLM stubbing is required and should stay out of production enums/config
- seeded state, debug intent reuse, `ACTION_DEBUG_EXEC`, stable `testTag`s, artifact capture, and CI lane partitioning belong in the design
- `qa-docs` is included as a core task

What I intentionally left unresolved:

- whether we add a later automated black-box release lane
- whether Maestro is the right tool for that lane
- exact scripted fixture format/refresh workflow
- exact flake/stability threshold for promoting optional lanes to required

Vote: CHANGES

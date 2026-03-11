# Round 2

I updated `final/design.md`.

What I changed:

- fixed the provider-registration lifecycle so it matches the real construction order
- split registration into:
  - stage 1 during `SessionServices.create()` for providers that only need session state / platform predicates
  - stage 2 during session construction for `ask_user` and `delegate_task`, because those need session event wiring that `SessionToolingBootstrapper` does not have
- made `open_app` use a separate `appLaunchAvailable` predicate in the design surface instead of piggybacking on `uiActionAvailable`
- updated the files-changed section to reflect that session-construction wiring, not `SessionToolingBootstrapper` alone, handles the session-wired providers

Why this mattered:

- the previous draft still implied all providers could be registered in `SessionToolingBootstrapper`, which is not true with the current `AgentSession` / `SessionServices` construction order
- I want the final doc to be implementable against the real codebase, not just conceptually clean

Remaining points I’m still fine debating:

- whether we should keep deferring `CapabilitySnapshot` in phase 1
- whether evolving `ToolRegistry` directly is still the right simplification after the two-stage registration note

Vote: `CHANGES`

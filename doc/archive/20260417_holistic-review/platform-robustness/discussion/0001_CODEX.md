# 0001 CODEX

Initial aligned draft is in `final/review.md` and `final/improvement_plan.md`.

What I carried forward from Claude:

- keep `ShizukuClient` as the facade boundary
- keep the VD lambda-provider structure
- remove dead private helpers
- document `DISPLAY_FLAGS`
- keep the cursor-state concern, but only as a test-gated follow-up

What I rejected or downgraded:

- I did not accept the cursor `node.refresh()` change as a confirmed bug. It is plausible, but the current logic may be intentionally based on pre-action selection state. I want a regression test before changing semantics.
- I did not elevate the startup exception cleanup and bitmap exceptional-path leak into primary plan items. They are real enough to clean up when touching the code, but they are not the main robustness problem.
- I did not collapse the design into a small-fix pass. The aligned docs keep the lifecycle owner, bounded waits, and cancellation safety as the backbone.

Open points for Claude to challenge:

- whether the lifecycle state machine is scoped tightly enough
- whether shell-fallback blocking should stay medium or be lowered
- whether there is concrete evidence that append-mode cursor placement is currently wrong

Vote: CHANGES

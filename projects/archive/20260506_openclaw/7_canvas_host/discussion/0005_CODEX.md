# 0005 CODEX — Accept reopen, keep one tool

I re-read `0004_CLAUDE.md` against the current `final/design.md`.

The reopened concern is valid, but the final document already reflects the corrected direction:
- one typed interaction model
- extend `ask_user` instead of introducing `show_canvas`
- no second Op, response channel, or event family
- a single capsule waiting mode

I agree with keeping `ask_user` for Step 1. The name is slightly imperfect for `Summary`, but the implementation and prompt surface stay much simpler with one tool than with a rename plus parallel migration. We can revisit naming later if the product grows a broader interaction surface.

I added one clarification to the design doc: `displayText` and `responseSummary` remain separate because transcript copy and card copy are related but not identical responsibilities.

Vote: APPROVE

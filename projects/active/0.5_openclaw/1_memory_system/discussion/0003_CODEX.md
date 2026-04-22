I made one targeted update to `final/design.md`.

New evidence from code:

- `Turn.kt` still allows task completion from plain assistant text with zero tool calls.
- That means tool-only retention is not fully reliable unless completion semantics are tightened to require `complete_task`.

I did not reopen the broader simplification debate. I accepted:

- tool-first retain
- no kind field
- no turn-1 goal-based recall in V1

What I changed:

- added an explicit runtime constraint/open question to the final doc so the current design does not overclaim correctness

If Claude is comfortable treating that as a documented limitation for V1, I can approve next round.

Vote: CHANGES

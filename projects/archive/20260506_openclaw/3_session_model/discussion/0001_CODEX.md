# CODEX Round 0001

I initialized the alignment workspace and added a full aligned draft in `final/session_model_aligned.md`.

Main synthesis decisions:

- keep one durable session identity and separate lifecycle, residency, and execution state
- keep `routeKey` but make v1 conservative; direct `sessionId` lookup remains valid
- make `events.jsonl` canonical, `manifest.json` derived, `checkpoint.json` recovery-only
- add an explicit write-order contract and an operation matrix for `UserInput`, `Supplement`, `Takeover`, `Resume`, `Interrupt`, `UserResponse`, and `Approve`
- keep UI selection out of repository truth

Open questions left in the final doc:

- exact archive rule
- whether cold `UserResponse` / `Approve` may revive runtime
- whether runtime ownership should appear in manifest or stay in-memory

Vote: CHANGES

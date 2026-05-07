# CODEX

Final pass complete.

The aligned document is self-contained and resolves the major differences between the two initial designs without adding extra abstraction:

- canonical durable session identity
- derived manifest plus canonical event log and checkpoint
- hot/cold runtime residency split
- single-lane scheduler with per-session inboxes
- explicit migration away from filename-paired storage and reload glue

Vote: `APPROVE`

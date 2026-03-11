# CLAUDE

Started the alignment draft from the two independent designs and both reviews.

Merged decisions:

- session identity must be durable and separate from runtime residency
- per-session directory is the right storage shape
- single global lane with per-session collect semantics is enough for v1

Open adjustments for Codex:

- whether the summary file should be treated as derived state
- how much structure `routeKey` needs in v1
- how explicit the migration mapping should be

Vote: `CHANGES`

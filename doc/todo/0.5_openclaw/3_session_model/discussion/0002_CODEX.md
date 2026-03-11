# CODEX

Updated the aligned design.

Changes made:

- made `manifest.json` explicitly derived/index state
- kept `events.jsonl` as canonical durable timeline
- simplified routing to a stable string `routeKey`
- kept direct `sessionId` lookup for internal history selection
- made migration mapping from `SessionRecord` and `SessionRuntimeSnapshot` concrete

Remaining concern:

- none significant; the design is now simple enough and still covers future multi-entry routing.

Vote: `CHANGES`

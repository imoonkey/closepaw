# Review Of `design_codex.md`

## Overall Assessment

This is a strong design. It fixes the real problem in the current codebase: runtime session, persisted history, and reload snapshot are treated as separate identities today, and the resulting glue has leaked into UI and coordination code.

The proposal gets the big decisions right:

- durable session identity becomes canonical
- runtime residency is separated from product lifecycle
- single-lane execution is kept for now
- `selectedSessionForReload`, filename pairing, and `externalActiveSessionId` are correctly identified as symptoms to remove, not preserve

I agree with the direction. The main remaining work is tightening a few boundaries so the implementation stays simple instead of becoming an over-layered repository/scheduler/event system.

## Main Findings

### 1. `routeKey` Is Useful, But The First Version Should Stay Conservative

The examples borrowed from OpenClaw are directionally right, but the Android app does not yet have those entry points. If the implementation starts by making route resolution too clever, we risk building a routing abstraction the product cannot exercise yet.

Recommendation:

- keep the concept
- define it as a plain stable string key in v1
- start with `main` and `session:<id>`
- add channel-specific key builders only when those entry points actually land

The design should preserve the future shape without forcing today’s code to understand Telegram- or agent-specific key grammars.

### 2. `manifest.json` Versus `events.jsonl` Needs A Clear Ownership Rule

The design proposes both a manifest summary and an append-only event log. That is correct, but it creates a classic dual-write risk unless ownership is explicit.

Recommendation:

- `events.jsonl` is the canonical timeline
- `manifest.json` is a denormalized index/header derived from event appends and checkpoint updates
- `manifest.json` must never carry facts that cannot be reconstructed or reconciled from the session directory

Without that rule, the system can drift into two competing sources of truth again.

### 3. Event Scope Should Stay Semantic, Not Exhaustive

The proposal correctly rejects token-level persistence. I would push that simplification further: only persist events that are meaningful to browsing, recovery, or audit.

Good examples:

- user input accepted
- agent message finalized
- tool action recorded
- task completed
- artifact recorded

Avoid turning the durable log into a replay of every internal state edge. The more granular the log becomes, the more migration and compaction pain we buy later.

### 4. Session Selection Should Not Require `routeKey = session:<id>` Internally

For external routing, `routeKey` makes sense. For selecting an existing session from history, the user already has a durable `sessionId`.

Recommendation:

- repository supports both `get(sessionId)` and `resolve(routeKey)`
- UI history selection should use `sessionId` directly
- `session:<id>` can exist as an external routing form, but it should not become the only way internal code finds a known session

This keeps the design simpler and avoids introducing a second identifier just to rediscover the first one.

### 5. Migration Should Explicitly Map Current Durable Data To New Durable Data

The migration section has the right direction, but implementation will go smoother if it names the mapping explicitly:

- `SessionRecord.messages` -> finalized message and action events
- `SessionRecord.screenStates` -> artifact records under the session directory
- `SessionRuntimeSnapshot` -> `checkpoint.json`
- `SessionInfo` -> derived summary, not migrated as a durable model

That keeps the importer deterministic and prevents v1 from keeping old and new models alive longer than necessary.

## Trade-Off View

The most important trade-off in the design is correct: be aggressive about canonical identity and conservative about concurrency.

That said, I would keep the runtime architecture wording a little lighter. `SessionRepository`, `SessionRuntimeManager`, and `SessionScheduler` are reasonable boundaries, but they should stay thin and concrete. If each becomes its own mini-framework, we will recreate the current complexity in a cleaner vocabulary.

## Suggested Alignment Direction

I would approve this design after the following clarifications are folded in:

1. Treat `routeKey` as a simple stable key in v1, with minimal built-in variants.
2. State explicitly that `events.jsonl` is the canonical timeline and `manifest.json` is derived summary state.
3. Keep event persistence semantic and compact.
4. Allow direct `sessionId` lookup for internal history selection.
5. Make the migration mapping concrete so old durable files can be removed cleanly.

With those changes, the design is both correct and implementable.

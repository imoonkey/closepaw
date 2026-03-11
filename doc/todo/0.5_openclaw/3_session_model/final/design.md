# Final Design: Session As The Canonical Product Object

## Goal

Make `Session` the durable product object that owns conversation continuity across task completion, idle shutdown, process death, and future entry points.

The current code splits that concept across three models:

- `session/AgentSession.kt` for hot runtime
- `history/model/SessionRecord.kt` for browsable transcript
- `history/model/SessionRuntimeSnapshot.kt` for reload state

That split causes the current glue:

- `SessionCoordinator.selectedSessionForReload`
- `SessionCoordinator.lastDeadSessionFileName`
- `SessionHistoryManager.externalActiveSessionId`
- filename pairing between `session-*.json` and `context-*.json`

Those are symptoms of the same problem: the product does not have one canonical session identity.

## Core Decisions

### 1. One durable session identity

Introduce a canonical persisted session aggregate keyed by immutable `SessionId`.

`SessionId` is the durable identity.

`routeKey` is a stable string used to resolve or create the correct open session for an entry point.

Examples:

- `main`
- `direct:telegram:12345`
- `group:telegram:67890`

Internal history selection should use `sessionId` directly. `routeKey` is for entry-point routing, not for rediscovering a known session.

### 2. Separate product lifecycle from runtime state

Session state must be split into three independent axes:

- lifecycle: `OPEN | ARCHIVED`
- residency: `HOT | COLD`
- execution: `IDLE | QUEUED | RUNNING | PAUSED | RECOVERING`

This is the key fix.

When the runtime shuts down, the session becomes `COLD`, not dead. A follow-up message targets the same durable session and hydrates runtime on demand.

### 3. Keep concurrency simple

The device only executes one automation task at a time today.

V1 uses:

- one global execution lane
- one logical inbox per session
- collect semantics for repeated input to the same running or queued session

We do not need true multi-lane execution yet.

### 4. Use append-only durable history

Persist a semantic event stream for each session:

- append-only `events.jsonl`
- finalized events only
- no token-by-token deltas

This keeps writes crash-friendly and preserves a durable timeline without exploding storage or complexity.

## Canonical Model

```kotlin
data class SessionManifest(
    val id: SessionId,
    val routeKey: String?,
    val title: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lifecycle: SessionLifecycle,
    val lastKnownExecution: SessionExecutionState,
    val preview: SessionPreview,
    val metadata: SessionMetadata,
)

enum class SessionLifecycle {
    OPEN,
    ARCHIVED,
}

enum class SessionResidency {
    HOT,
    COLD,
}

enum class SessionExecutionState {
    IDLE,
    QUEUED,
    RUNNING,
    PAUSED,
    RECOVERING,
}

data class SessionPreview(
    val lastUserText: String?,
    val lastAgentText: String?,
    val lastCompletionReason: String?,
)
```

Important boundary:

- `manifest.json` is persisted summary/index state for fast listing and routing
- `events.jsonl` is the canonical durable timeline
- `checkpoint.json` is the canonical runtime hydration blob

`manifest.json` is not allowed to become an independent second truth. Anything semantically important must be recoverable from the session directory.

## Storage Layout

Each session gets one directory:

```text
files/sessions/<sessionId>/
  manifest.json
  events.jsonl
  checkpoint.json
  artifacts/
```

### `manifest.json`

Purpose:

- fast session list rendering
- route lookup
- preview metadata
- lifecycle state

It replaces the role of `SessionInfo`, but it is derived summary, not the authoritative conversation log.

### `events.jsonl`

Canonical durable timeline. Example event types:

- `SessionCreated`
- `UserInputAccepted`
- `TaskQueued`
- `TaskStarted`
- `MessageFinalized`
- `ActionFinalized`
- `ArtifactRecorded`
- `TaskCompleted`
- `SessionArchived`

Rules:

- append only
- persist semantic events only
- write finalized agent messages, not streaming deltas
- store artifact paths in events, not raw blobs

### `checkpoint.json`

Fast reload state for hot runtime hydration:

- prompt history
- todos
- scratchpad
- config snapshot
- last safe checkpoint metadata

This is the current `SessionRuntimeSnapshot` concept, moved under the canonical session directory and stripped of identity ownership.

### `artifacts/`

Session-scoped durable files such as:

- screen captures
- trace-linked screen state exports
- future action evidence files

Artifacts belong to the session, not to an unrelated parallel storage namespace.

## Runtime Architecture

### `SessionRepository`

Single source of truth for durable session data.

Responsibilities:

- get by `sessionId`
- resolve or create by `routeKey`
- list sessions
- archive/delete sessions
- read/write `manifest.json`
- append `events.jsonl`
- read/write `checkpoint.json`

This absorbs the durable-data responsibilities currently split across `SessionHistoryManager` and `SessionStorage`.

### `SessionRuntimePool`

Owns live `AgentSession` instances keyed by `SessionId`.

Responsibilities:

- create hot runtime for an open session
- hydrate cold runtime from `checkpoint.json`
- expose hot/cold residency
- release idle runtimes back to cold state

`AgentSession` stays as the execution engine, but it is no longer the product session itself.

### `SessionScheduler`

Replaces `SessionCoordinator`.

Responsibilities:

- manage the single global execution lane
- keep per-session pending inboxes
- apply collect semantics
- transition session execution state

Collect semantics:

- if a session is already `RUNNING` or `QUEUED`, additional input is appended to that same session inbox
- when runnable, the scheduler coalesces pending input in arrival order into one follow-up turn

That turns “three quick user messages” into one canonical continuation instead of three agent launches.

## State Machine

```text
Lifecycle:  OPEN ---------------------------------------> ARCHIVED

Residency:  HOT <--------------hydrate/release---------> COLD

Execution:  IDLE -> QUEUED -> RUNNING -> IDLE
                       ^          |
                       |          v
                    collect     PAUSED
```

Rules:

- only `OPEN` sessions accept new input
- `ARCHIVED` sessions remain visible but are not runnable
- `OPEN + COLD` is a normal steady state
- `RUNNING + new input` stays inside the same session
- runtime death never changes session identity

## Entry-Point Flow

All entry points use the same resolution path:

1. derive `routeKey`
2. `SessionRepository.resolveOrCreate(routeKey)`
3. append `UserInputAccepted`
4. if runtime is cold, hydrate from `checkpoint.json`
5. enqueue session in `SessionScheduler`
6. execute in the single global lane

Examples:

- app default composer -> `main`
- future direct message source -> `direct:<source>:<id>`
- future group source -> `group:<source>:<id>`

History selection is different:

- UI already has `sessionId`
- UI loads by `sessionId` directly
- no `session:<id>` indirection is required internally

## UI Model

The UI becomes a view over durable sessions:

- sidebar lists `manifest.json` summaries
- current selection is a durable `sessionId`
- chat screen can render persisted timeline even when runtime is cold
- active/running badges come from canonical session state, not bridge fields

This removes reload intent from the activity layer.

## Mapping From Current Code

Keep:

- `AgentSession` as hot execution runtime
- `SessionRuntimeSnapshot` content model, rehomed as `checkpoint.json`
- `AgentMessageBuffer` finalized message/action grouping

Replace:

- `SessionHistoryManager` -> folded into `SessionRepository`
- `SessionStorage` filename-pair model -> per-session directory store
- `SessionCoordinator` -> `SessionScheduler`
- `SessionInfo` -> derived manifest summary
- `selectedSessionForReload` -> deleted
- `lastDeadSessionFileName` -> deleted
- `externalActiveSessionId` -> deleted

## Migration

No long-term backward-compatibility layer.

Migration path:

1. Introduce the new per-session directory layout.
2. Add a one-time importer from old files into the new layout:
   - `SessionRecord.messages` -> `MessageFinalized` and `ActionFinalized` events
   - `SessionRecord.screenStates` -> files under `artifacts/` plus `ArtifactRecorded` events
   - `SessionRuntimeSnapshot` -> `checkpoint.json`
3. Switch all new writes to the new layout only.
4. Move session resolution and active-session ownership out of `MainActivity`.
5. Replace `SessionCoordinator` with `SessionScheduler`.
6. Delete old filename-pair logic and reload-specific bridges.

After import, only the new model is written.

## Non-Goals For V1

- full multi-lane execution
- arbitrary session compaction policies
- channel-specific routing abstractions beyond plain stable `routeKey` strings

If long sessions later require compaction, add it as a storage operation over semantic events. Do not complicate v1 to solve that early.

## Why This Wins

- one canonical session identity across runtime, storage, and UI
- idle shutdown becomes a cold-state transition, not conversation death
- cross-entry continuity becomes a routing problem instead of a reload hack
- queueing becomes a property of sessions, not UI glue
- storage layout naturally owns artifacts and future evidence files

This is the simplest design that actually solves the brief.

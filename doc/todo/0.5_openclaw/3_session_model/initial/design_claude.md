# Design: Make Session The Durable Product Object

## Goal

Turn `Session` into the one object the product, runtime, and storage all agree on.

Today the codebase has three overlapping session shapes:

- `session/AgentSession.kt` owns the live runtime.
- `history/model/SessionRecord.kt` owns chat history for restore and browsing.
- `history/model/SessionRuntimeSnapshot.kt` owns reload state.

That split leaks everywhere:

- `SessionCoordinator` tracks reload-only state with `selectedSessionForReload` and `lastDeadSessionFileName`.
- `SessionHistoryManager` needs `externalActiveSessionId` because the active session lives somewhere else.
- Session identity is partly `sessionId`, partly file name, partly whichever runtime object is still alive.

The product problem in the brief is real: when the runtime dies, the conversation should not die with it.

## Design Principles

- One durable session id. No file-name-derived identity.
- Runtime is attached to a session; it is not the session.
- Idle shutdown and process death are normal cold states, not terminal states.
- Storage should make browsing cheap and recovery safe.
- Start with one executor lane for the whole device.
- Keep the model simple enough that future Web or messaging entry points can reuse it without another rewrite.

## Proposed Model

### 1. Canonical Session Aggregate

Introduce a durable session record that is the source of truth for listing, routing, and recovery:

```kotlin
data class StoredSession(
    val id: String,
    val key: SessionKey,
    val title: String?,
    val status: SessionStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val lastTask: TaskSnapshot?,
    val metadata: SessionMetadata,
)

data class SessionKey(
    val type: SessionKeyType,
    val value: String,
)

enum class SessionKeyType {
    MAIN,
    EXPLICIT,
    DIRECT_CHANNEL,
    GROUP_CHANNEL,
}

enum class SessionStatus {
    OPEN,
    ARCHIVED,
}

data class TaskSnapshot(
    val state: TaskState,
    val pendingInputCount: Int,
)

enum class TaskState {
    IDLE,
    QUEUED,
    RUNNING,
    PAUSED,
    RECOVERING,
}
```

The key point is that `StoredSession` is durable product state. `AgentSession` becomes a hot execution object created on demand for a `StoredSession.id`.

### 2. Separate Product State From Runtime Residency

The current `SessionState` is doing too much. We need two orthogonal axes:

- Product state: `OPEN` or `ARCHIVED`
- Runtime residency: `HOT` or `COLD`

`Shutdown` today means "the runtime ended". The new rule is:

- runtime shutdown changes `HOT -> COLD`
- the session remains `OPEN`
- follow-up input targets the same session id

That removes the need for dead-session reload glue in the activity layer.

### 3. Session Key Instead Of Reload Heuristics

Every input source resolves to a session through a key:

- app default chat: `MAIN/main`
- user-picked history thread: `EXPLICIT/<sessionId>`
- future Telegram DM: `DIRECT_CHANNEL/telegram:<chatId>`
- future group chat: `GROUP_CHANNEL/telegram:<groupId>`

This keeps multi-entry continuity simple: routing picks a session first, runtime comes second.

## Storage Layout

Use one directory per session:

```text
files/sessions/<sessionId>/
  session.json
  events.jsonl
  checkpoint.json
  artifacts/
```

### session.json

Small summary file used for session list, routing, and metadata lookups.

It replaces the role of `SessionInfo`, plus the bits of active/reload state currently spread across `SessionHistoryManager`, `SessionCoordinator`, and UI code.

Fields:

- `id`
- `key`
- `title`
- `status`
- `createdAt`
- `updatedAt`
- `lastTask`
- preview fields such as last user text and completion reason
- source metadata

### events.jsonl

Append-only durable timeline for the session.

Use event records for:

- session created
- user input appended
- task queued
- task started
- agent message finalized
- action recorded
- screen artifact recorded
- task completed
- session archived

Do not persist every token delta. Persist finalized semantic events only. The current `AgentMessageBuffer` is already moving in that direction and should stay.

### checkpoint.json

Fast runtime hydration file. It keeps what `SessionRuntimeSnapshot` already does well:

- prompt history
- todos
- scratchpad
- conversation config
- last safe checkpoint

This stays a separate file because browsing and recovery have different access patterns. The mistake is not having a checkpoint file; the mistake is treating that file as a parallel identity model.

## Runtime Architecture

### SessionStore

Replace filename-pair logic in `SessionStorage` with a session-directory store.

Responsibilities:

- create a session directory
- read and write `session.json`
- append to `events.jsonl`
- read and write `checkpoint.json`
- list sessions by reading summaries, not full transcripts

### SessionRegistry

Replace the public role of `SessionHistoryManager`.

Responsibilities:

- resolve by session id
- resolve or create by session key
- list sessions for UI
- archive and delete sessions
- publish active session selection as canonical state

This is where `externalActiveSessionId` disappears. The active session becomes repository state, not a bridge field.

### SessionRuntimePool

Own live `AgentSession` instances keyed by durable session id.

Responsibilities:

- create a hot runtime for an open session
- reload from `checkpoint.json` when a cold session gets input
- release idle runtimes back to cold state
- expose hot/cold residency

This keeps `AgentSession` useful while demoting it from product identity to runtime implementation detail.

### SessionExecutor

Replace `SessionCoordinator`.

Initial behavior:

- one global device lane
- each session has a logical inbox
- one running task at a time
- new input for a running session is collected into the same follow-up batch

This is simpler than full lane-based concurrency and already solves the actual product issue: sessions survive while tasks serialize.

## State Machine

```text
Session status:  OPEN -------------------------------> ARCHIVED

Residency:       HOT <------hydrate/release---------> COLD

Work state:      IDLE -> QUEUED -> RUNNING -> IDLE
                          ^          |
                          |          v
                       collect     PAUSED
```

Rules:

- only `OPEN` sessions accept new input
- `COLD + OPEN` is a valid steady state
- `RUNNING + new input` stays in-session and increments pending work
- `ARCHIVED` sessions remain visible but cannot execute

## Entry-Point Flow

1. Derive a `SessionKey` from the source of the input.
2. Ask `SessionRegistry` for the open session for that key.
3. Create it if absent.
4. Append the user input event.
5. Queue execution through `SessionExecutor`.
6. If the session is cold, hydrate runtime first, then run.

This replaces:

- `selectedSessionForReload`
- `consumeDeadSessionFileName()`
- UI-driven auto-reload branches

## UI Changes

The UI should bind to a durable session id, not to whatever runtime object still exists.

That means:

- session list renders from `session.json` summaries
- current session selection is a stable session id
- chat screen can render persisted history even when runtime is cold
- active/running badges come from canonical session state, not bridges

The UI becomes a view over sessions rather than a holder of session truth.

## Migration

Do not keep the old split model around long-term.

Implementation order:

1. Introduce session directories and the new summary model.
2. Add a loader that imports existing `session-*.json` plus paired `context-*.json` into the new layout.
3. Switch new writes to the directory format only.
4. Move session resolution and active-session ownership out of `MainActivity` and `SessionCoordinator`.
5. Replace `SessionHistoryManager` and `SessionCoordinator` with `SessionRegistry` and `SessionExecutor`.
6. Delete filename-pair logic and reload-specific bridges.

## Trade-Offs

### Why This Design Wins

- Keeps the session concept durable even when the runtime is gone.
- Keeps recovery fast without making checkpoints the primary product model.
- Gives future multi-entry support a clean routing primitive.
- Removes UI-only lifecycle hacks from the core flow.
- Keeps concurrency simple by staying single-lane for now.

### Main Cost

This is a real refactor, not a patch. It touches storage, session resolution, UI binding, and task execution ownership.

### Rejected Alternative: Patch The Existing Split

Adding more fields to `SessionInfo`, more reload branches, or more active-session bridges would preserve the core mistake: the product would still not know what a session is.

### Rejected Alternative: Full Parallel Lane Scheduler Now

The device is still effectively single-actor. A one-lane executor with per-session inboxes is enough. Lane expansion can come later without changing the session model.

## Self-Review

This design stays close to the current codebase:

- `AgentSession` remains the execution engine
- `SessionRuntimeSnapshot` becomes `checkpoint.json`
- `AgentMessageBuffer` still produces finalized persisted messages

But it changes the right thing at the center:

- session identity is durable
- browsing, recovery, and execution attach to that identity
- runtime death becomes cold storage, not conversation death

That is the minimum design that solves the brief cleanly and leaves room for future entry points.

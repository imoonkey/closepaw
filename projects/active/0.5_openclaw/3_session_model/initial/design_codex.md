# Design: Session As The Canonical Product Object

## Goal

Make `Session` the durable object that every entry point talks to.

Today the repository already has a useful runtime session in `session/AgentSession.kt`, plus transcript persistence in `history/SessionRecord.kt`, plus checkpoint persistence in `history/SessionRuntimeSnapshot.kt`. The problem is that these are three different models stitched together by UI-only state such as `selectedSessionForReload`, file-name pairing, and `externalActiveSessionId`.

The design goal is to replace that split with one canonical session model that:

- has a durable identity and routing key
- survives task completion, idle shutdown, process death, and entry-point changes
- is the mount point for history, actions, screenshots, errors, and checkpoints
- supports a simple queue model now and lane-based execution later
- keeps the current single-device implementation simple

## What Is Broken Now

The current codebase is close, but the product object is still fragmented:

- `AgentSession` is the live runtime object, not the canonical persisted session.
- `SessionRecord` is a chat transcript file optimized for UI restore, not runtime recovery.
- `SessionRuntimeSnapshot` is a separate checkpoint file optimized for reload, not browsing.
- `SessionCoordinator` owns queueing and reload intent through `selectedSessionForReload`, which is really product state, not UI glue.
- `SessionHistoryManager` needs an `externalActiveSessionId` bridge because there are effectively two recording owners.
- `Shutdown` is terminal for the runtime, but not for the user’s conversation. That mismatch causes the “dead session auto-reload” patch path in `MainActivity`.

The result is a weak session concept:

- one conversation can be represented by multiple shapes
- identity is partly `sessionId`, partly `fileName`, partly current runtime pointer
- entry points do not resolve into the same session object cleanly
- queueing is per-process glue, not a first-class session concern

## Design Principles

- One session identity, one source of truth.
- Separate product lifecycle from runtime residency.
- Keep task execution ephemeral; keep session continuity durable.
- Use append-only events for the durable timeline.
- Start with one global execution lane. Do not design full parallelism before it is needed.
- Make cross-entry continuity a routing problem, not a reload hack.

## Proposed Model

### 1. Canonical Session Object

Introduce one persisted session aggregate:

```kotlin
data class AgentSessionRecord(
    val id: SessionId,
    val routeKey: SessionRouteKey,
    val label: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lifecycle: SessionLifecycle,
    val runtime: SessionRuntimeSummary,
    val metadata: SessionMetadata,
    val lastTask: TaskSummary?,
)
```

```kotlin
@JvmInline
value class SessionRouteKey(val value: String)

enum class SessionLifecycle {
    OPEN,
    ARCHIVED
}

data class SessionRuntimeSummary(
    val residency: SessionResidency,
    val execution: SessionExecutionState,
    val pendingInputCount: Int,
)

enum class SessionResidency {
    HOT,
    COLD
}

enum class SessionExecutionState {
    IDLE,
    QUEUED,
    RUNNING,
    PAUSED,
    RECOVERING
}
```

Key rule:

- `SessionId` is the immutable durable identity.
- `routeKey` is how entry points find or create the right session.

Examples of `routeKey` values:

- `main`
- `session:<sessionId>`
- `agent:<agentId>:main`
- `direct:telegram:<chatId>`
- `group:telegram:<groupId>`

This mirrors the OpenClaw insight without forcing premature multi-channel complexity into the first implementation. For Android-only use, the default key is `main`.

### 2. Session Versus Task

Keep the distinction explicit:

- `Session` is the durable conversation thread.
- `Task` is one execution run inside a session.

Tasks stay ephemeral and sequential inside a session. The existing `TaskStarted` and `TaskCompleted` events already fit this model well and should remain.

### 3. Split Product Lifecycle From Runtime State

This is the main conceptual fix.

Current `SessionState` mixes:

- whether the session exists as a user-visible object
- whether a live runtime is currently loaded
- whether execution is active right now

The new model separates them:

- `SessionLifecycle`: durable product state, usually `OPEN`
- `SessionResidency`: whether a live runtime is loaded (`HOT` or `COLD`)
- `SessionExecutionState`: `IDLE`, `QUEUED`, `RUNNING`, `PAUSED`, `RECOVERING`

Important consequence:

- idle timeout or process death changes residency from `HOT` to `COLD`
- it does not “kill the conversation”
- follow-up input targets the same session ID and route key

That removes the need for “dead session auto-reload” as a special case.

## Storage Design

### Session Directory

Persist each session in its own directory:

```text
files/sessions/<sessionId>/
  manifest.json
  events.jsonl
  checkpoint.json
  artifacts/
```

Why this shape:

- the session becomes the mount point for all durable state
- identity no longer depends on paired file names
- screenshots and future artifacts belong naturally to the session
- `events.jsonl` is append-only and crash-friendly
- `manifest.json` is small and cheap for listing
- `checkpoint.json` stays optimized for fast runtime hydration

### manifest.json

`manifest.json` is the indexed header used by UI and routing. It stores:

- `id`
- `routeKey`
- `label`
- `createdAt`
- `updatedAt`
- `lifecycle`
- `runtimeSummary`
- `metadata`
- lightweight preview fields such as `lastUserText`, `lastAgentText`, `lastCompletionReason`

This replaces `SessionInfo` as the listing source.

### events.jsonl

`events.jsonl` is the canonical durable timeline.

Each line is one event envelope, for example:

- session created
- user input accepted
- task queued
- task started
- message finalized
- action proposed/executed
- screen artifact recorded
- task completed
- session archived

Important detail: persist finalized events, not every UI streaming delta. The runtime can still stream deltas to the UI, but the durable log should stay compact and semantically meaningful. For agent text, persist completed message snapshots just as the current `SessionRecordingService` already builds via `AgentMessageBuffer`.

### checkpoint.json

`checkpoint.json` is the latest reloadable runtime snapshot:

- prompt history
- todos
- scratchpad
- config snapshot
- last safe recovery point

This keeps the good part of the current `SessionRuntimeSnapshot` design, but the file now lives under the canonical session directory instead of being paired with a transcript file by filename convention.

## Runtime Architecture

### 1. SessionRepository

Add a single repository as the source of truth for product sessions.

Responsibilities:

- resolve by `sessionId`
- resolve by `routeKey`
- create/open/archive sessions
- update manifest summaries
- append durable events
- read/write checkpoints
- list sessions for UI

This replaces the split responsibilities currently shared by `SessionHistoryManager`, `SessionStorage`, and pieces of `MainActivity`.

### 2. SessionRuntimeManager

Own live `AgentSession` instances keyed by `sessionId`.

Responsibilities:

- create live runtime for a session
- hydrate from `checkpoint.json` when a cold session becomes active
- expose current residency and execution state
- release runtime on idle timeout or process death without changing session identity

`session/AgentSession.kt` remains useful, but it should become an execution runtime owned by a session record, not the product session itself.

### 3. SessionScheduler

Replace `SessionCoordinator` with a scheduler that is explicitly session-aware.

Initial policy:

- one global execution lane for the device
- each session has its own pending inbox
- only one session may run at a time
- other sessions sit in `QUEUED`

This is enough for current Android constraints and matches the brief’s recommendation to keep single-lane execution first.

### 4. Collect Semantics

When multiple user inputs arrive for a session that is already `RUNNING` or `QUEUED`, do not start multiple follow-up tasks.

Instead:

- append the inputs to that session’s inbox
- when the session becomes runnable, coalesce them into one follow-up payload in arrival order

This turns “user sent three quick messages” into one canonical continuation instead of three agent launches. It is simpler and more useful than preserving a raw FIFO of follow-up tasks for the same session.

## Entry-Point Flow

### Session Resolution

All entry points use the same resolution flow:

1. derive a `routeKey`
2. ask `SessionRepository` for the open session for that key
3. create one if none exists
4. append user input event
5. enqueue work in `SessionScheduler`

Examples:

- main app input uses `main`
- selecting a historical session uses `session:<sessionId>`
- future Telegram DM uses `direct:telegram:<chatId>`

The key point is that “resume” is no longer a separate UX path with special reload logic. It is the same session resolution path.

### Cold Follow-Up

If a session is `COLD` and receives new input:

1. scheduler marks it `RECOVERING`
2. runtime manager hydrates `AgentSession` from `checkpoint.json`
3. session becomes `IDLE`
4. scheduler starts the next task

This replaces `selectedSessionForReload`, `consumeDeadSessionFileName()`, and the explicit “autoReload” branch in `MainActivity`.

## UI Model

The UI should render from canonical session summaries instead of stitched state.

Sidebar/session list shows:

- label or derived title
- last updated time
- runtime state badge: `Running`, `Queued`, `Paused`, `Ready`
- active marker based on the selected/open session ID, not an external recording bridge

Chat screen binds to a selected `sessionId`. If the runtime is hot, it streams live events. If cold, it reads the persisted timeline and waits until the session becomes active again.

Key rule:

- UI is a view over sessions
- it is not responsible for owning reload intent or session identity

## Changes To Existing Components

### Keep

- `AgentSession` task execution lifecycle
- `HistoryManager`
- `SessionRuntimeSnapshot` checkpoint content
- `TaskStarted` and `TaskCompleted`
- `AgentMessageBuffer` logic for finalized persisted agent messages

### Replace Or Fold

- `SessionHistoryManager` becomes part of `SessionRepository`
- `SessionCoordinator` becomes `SessionScheduler`
- `SessionInfo` becomes a manifest-derived view model
- file-name-based transcript/context pairing disappears
- `externalActiveSessionId` disappears
- `selectedSessionForReload` disappears

## State Machine

Per session:

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
- `ARCHIVED` sessions are browseable but not runnable
- `COLD + OPEN + new input` is canonical and expected
- `RUNNING + new input` stays in the same session and uses collect semantics

## Migration Strategy

Do not keep the old split model long-term.

Implementation plan:

1. Introduce `SessionRepository`, manifest model, and session directory layout.
2. Rehome runtime checkpoint writes from paired `context-*.json` files to `checkpoint.json`.
3. Rehome transcript writes to `events.jsonl` and manifest summaries.
4. Move `MainActivity` to resolve sessions through repository + scheduler only.
5. Delete `selectedSessionForReload`, `externalActiveSessionId`, and file-name-based lookup logic.

For existing local data, use a one-time importer from old `session-*.json` plus `context-*.json` into the new per-session directory format. After import, only the new format is written.

## Trade-Offs

### Why This Wins

- One canonical session identity across runtime, persistence, and UI.
- Cold follow-up becomes a normal path instead of a recovery hack.
- Queueing becomes a property of the session system, not activity glue.
- Cross-entry support becomes straightforward because routing is explicit.
- Storage is safer and more extensible for artifacts.

### Costs

- Larger refactor than patching `SessionHistoryManager`.
- Requires replacing file-name-based storage assumptions.
- Requires a clean migration from the current dual-file persistence model.

### Rejected Alternative: Keep Current Split And Add More Bridges

This would mean keeping:

- transcript file
- checkpoint file
- runtime object
- UI reload selection
- active-session bridge

That path is cheaper short-term but keeps the core mistake: session identity is still not canonical.

### Rejected Alternative: Full Multi-Lane Parallelism Now

The device only executes one automation task at a time today. Building true concurrent session execution now adds complexity without product value. A global single lane with per-session inboxes is enough, and the design still leaves room for future lane expansion.

## Self-Review

This design solves the actual problem in the brief:

- session becomes the first-class product object
- session continuity survives multiple tasks and multiple entry points
- queueing is session-aware
- storage is durable and scoped by session

It also removes current accidental complexity instead of layering on more:

- no file-name pairing as identity
- no dead-session reload special case
- no UI-owned reload intent
- no duplicate active-session bookkeeping

The design is intentionally conservative about concurrency and ambitious about identity. That is the right trade-off for this repository now.

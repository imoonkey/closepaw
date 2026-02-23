# Clean Refactor — Aligned Design (Codex + Claude)

Date: 2026-02-23  
Status: Draft aligned baseline (awaiting Claude review)

## 1. Alignment Summary

Both sides agree on the core direction:
1. Fix real correctness bugs first (`clearSession` race, history intent loss, fuzzy session lookup).
2. Remove retry/polling spaghetti from session input flow.
3. Extract session orchestration out of `MainActivity`.
4. Reduce high-copy action executor code.

Disagreements were resolved by code evidence:
- **Resolved**: no actor/channel state machine for coordinator (use simpler serialized suspend API).
- **Resolved**: no 4-way `AgentSession` split now (do targeted extraction/flattening).
- **Resolved**: `PersistedHistoryItem` removal is valuable but high-blast-radius; move to later phase.

---

## 2. Evidence-Based Findings

### F1. Async clear race (P0)

`SessionRecordingService.clearSession()` clears state in fire-and-forget coroutine:
- `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:271`

Callers continue immediately and may initialize/resume a new session before old clear executes:
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:248`

Risk: delayed clear wipes newly created `currentSession/currentFileName/contextFileName`.

### F2. History compression can drop user intent (P0)

`HistoryManager.compress()` repeatedly calls `removeFirstItem()` without preserving `role="user"`:
- `app/src/main/kotlin/com/moonkey/androidagent/history/HistoryManager.kt:238`

This can remove supplements / user corrections, while original task remains in system prompt.  
Existing run analysis already documents this failure mode.

### F3. Session file matching is fuzzy (P1)

Session lookup uses substring match:
- `app/src/main/kotlin/com/moonkey/androidagent/history/SessionHistoryManager.kt:90`

`contains(sessionId)` is logically wrong for identity matching.

### F4. Pending input flow is timer-loop spaghetti (P1)

Busy state enqueues input, timer drains queue, drain re-enters same method and re-enqueues while busy:
- enqueue path: `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:421`
- poll drain path: `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:559`

This creates avoidable retry churn during long-running tasks.

### F5. Click/LongPress executors are heavily duplicated (P1)

Current sizes:
- `ClickExecutor.kt` 173 lines
- `LongPressExecutor.kt` 180 lines

Measured normalized sequence similarity: **0.736** (73.6%).  
Shared line-heavy structures: target resolution, bounds check, fallback loop, post-capture success/failure formatting.

### F6. Checkpoint state hardcoded schedule is fragile (P2)

Mutation listener schedule always writes `RUNNING_DIRTY`:
- `app/src/main/kotlin/com/moonkey/androidagent/session/SessionCheckpointCoordinator.kt:37`

Not proven as current active bug in Idle path, but fragile for future idle-time mutations.

### F7. `AgentSession.submit` callers are already main-thread serialized today (P2)

All production callsites come from `lifecycleScope`, `viewModelScope`, or service `scope` configured with Main dispatcher:
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt`

So introducing actor/channel now adds complexity without solving an observed concurrency bug.

---

## 3. Aligned Refactor Decisions

### D1. Deterministic clear API (P0)

Replace:
- `fun clearSession()`

With:
- `suspend fun clearSessionAndAwait()`

Behavior:
1. cancel pending save/checkpoint jobs
2. join them
3. clear in-memory session state synchronously before return

No fire-and-forget mutation after return.

### D2. Preserve user intent during compression (P0)

Compression rule:
1. still truncate old tool outputs aggressively
2. when removing old items, skip `ResponseItem.Message(role="user")`
3. remove paired outputs when removing calls

Goal: supplements and user corrections become canonical preserved context.

### D3. Exact session identity matching (P1)

Do not use `contains()`.  
Parse `session-{timestamp}-{sessionId}.json` and compare exact `sessionId`.

If parse fails: skip file instead of fuzzy matching.

### D4+D5. Extract `SessionCoordinator` with built-in event-driven queue (P1/P2)

Create `SessionCoordinator` to own:
- selected session for reload
- current runtime session
- input queue
- create/reload/shutdown transitions

Queue behavior (internal implementation detail of coordinator):
1. all user inputs append to queue
2. queue drains only when session state is `Created` or `Idle`
3. when `TaskCompleted` arrives, trigger one drain attempt
4. no timer retry loop, no recursive self-calls

Implementation choice:
- plain `suspend` methods + internal mutex/critical section
- **no actor/channel reducer** for now

Rationale: simpler, enough for current call graph, easier debugging.

### D6. Deduplicate point-action execution (P1)

Introduce shared point-action execution helper (name flexible, e.g. `PointActionExecutorCore`) for:
- common target resolve/bounds/fallback/post-capture flow

Keep thin wrappers:
- `ClickExecutor`
- `LongPressExecutor`

`ScrollExecutor` stays separate (area-based semantics).

### D7. Targeted `AgentSession` cleanup, not 4-way split (P2)

Do now:
- flatten `handleUserInput` into explicit state `when`
- remove redundant dead bits (e.g. `channelCloseScheduled` if still redundant)
- extract small private helpers for readability

Do not do now:
- forced split into `LifecycleController + RuntimeLease + EventPublisher + ...`

### D8. Derive checkpoint schedule state from lifecycle phase (P2)

Keep current checkpoint architecture, but make scheduled snapshot state lifecycle-aware:
- running/paused -> `RUNNING_DIRTY`
- idle -> `IDLE_READY`
- shutdown path remains explicit `flushClosed()`

---

## 4. Deferred Item (Explicitly Not in this pass)

### D9. Remove `PersistedHistoryItem` parallel hierarchy (Deferred)

Why deferred:
1. high blast radius through parsing/prompt/serialization/test paths
2. correctness-sensitive for tool-call argument fidelity
3. not blocking immediate robustness issues

Current evidence of footprint:
- `PersistedHistoryItem/HistoryItemConverter` usages are widespread (25 references).

Decision:
- keep current model in this refactor
- revisit in a dedicated migration once correctness backlog is closed

---

## 5. Implementation Plan

### Phase A — Must-Fix Correctness
1. D1 deterministic clear API
2. D2 preserve user messages in compression
3. D3 exact session id file matching

### Phase B — Structure (parallel tracks)
1. Track 1: D4+D5 `SessionCoordinator` extraction + built-in queue simplification
2. Track 2: D6 executor dedup core extraction
3. Track 3: D7 targeted `AgentSession` flatten + D8 checkpoint scheduled-state derivation

### Phase C — Cleanup
1. dead code cleanup (`onTaskCompleted` no-op hook, unused `autoStart` payload if still unused)

### Phase D — Optional Follow-up
1. D9 persisted history hierarchy unification (separate RFC)

---

## 6. Test Requirements

1. `SessionRecordingService`: clear-then-create race regression test.
2. `HistoryManager`: `compress()` never removes user messages; still reduces token count.
3. `SessionHistoryManager`: exact file match test (negative case for substring).
4. Coordinator tests: queued input drains only on `Idle/Created`; no timer polling.
5. `AgentSession`: hot-idle follow-up and idle-timeout transitions remain correct.
6. Action tests: click/long-press wrappers still keep channel order + fallback behavior.

---

## 7. Non-Goals

1. No backward compatibility with old orchestration internals.
2. No actor-style coordinator unless new evidence demands it.
3. No large persistence-model migration in this pass.

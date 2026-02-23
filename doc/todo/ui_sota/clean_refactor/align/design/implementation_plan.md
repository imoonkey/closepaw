# Clean Refactor — Implementation Plan

Date: 2026-02-22
Based on: design.md aligned design

## Phase A — Must-Fix Correctness (D1, D2, D3)

### A1. D1: Deterministic clear API (P0)

**Problem:** `SessionRecordingService.clearSession()` is fire-and-forget. Callers continue immediately, risking session state corruption when a new session is created before old clear completes.

**Changes:**
1. `SessionRecordingService.kt`: Convert `fun clearSession()` to `suspend fun clearSessionAndAwait()`
   - Cancel pending save/checkpoint jobs
   - Join them (await completion)
   - Clear state synchronously before return
   - No coroutine launch — caller awaits

2. `SessionHistoryManager.kt`: If any wrapper calls clearSession, update signature

3. `MainActivity.kt`: `clearCurrentSession()` already in `lifecycleScope.launch`, so making the call suspend-compatible is straightforward

4. **Test:** Add `SessionRecordingServiceTest` case: clear-then-create race regression — verify new session state survives after clearAndAwait

### A2. D2: Preserve user intent during compression (P0)

**Problem:** `HistoryManager.compress()` can remove user messages, losing supplements and corrections.

**Changes:**
1. `HistoryManager.kt`: Modify `compress()` Strategy 2 loop:
   - When iterating for removal, skip items where `item is ResponseItem.Message && item.role == "user"`
   - Still remove function calls and their paired outputs
   - Add safety: if only user messages remain and still over budget, stop (don't infinite loop)

2. **Test:** Add `HistoryManagerTest` case: `compress never removes user messages` — fill history with mixed user/tool items, compress, verify all user messages survive

### A3. D3: Exact session identity matching (P1)

**Problem:** `SessionHistoryManager.loadSession()` uses `contains(sessionId)` — fuzzy and incorrect.

**Changes:**
1. `SessionHistoryManager.kt`: Replace `files.find { it.name.contains(sessionId) }` with exact parse:
   - Parse filename pattern `session-{timestamp}-{sessionId}.json`
   - Extract sessionId field
   - Compare with exact string equality
   - If parse fails, skip file

2. Also audit `deleteSession()` for same pattern

3. **Test:** Add `SessionHistoryManagerTest` case: exact match rejects substring overlap

---

## Phase B — Structure (D4+D5, D6, D7+D8)

### B1. D4+D5: Extract SessionCoordinator (P1/P2)

**Problem:** MainActivity owns session creation, input queue, reload logic with timer-loop spaghetti. Complex, hard to test, fragile.

**Changes:**
1. Create `session/SessionCoordinator.kt`:
   - Owns: `currentSession`, `selectedSessionForReload`, input queue
   - API: `submit(text)`, `selectSessionForReload(session)`, `createFreshSession(config)`, `reloadSelectedSession()`, `shutdown()`
   - Internal queue: `MutableList<String>` protected by mutex
   - Drain: event-driven, triggers on `TaskCompleted` and `SessionCreated` — no timer
   - Uses `Mutex` for serialization (not actor/channel)

2. Simplify `MainActivity.kt`:
   - Remove `pendingInputs`, `drainPendingInputs()`, `scheduleDrainPendingInputs()`, `enqueuePendingInput()`
   - Remove `sessionCreationLock`, `sessionCreationInProgress`
   - Delegate to `SessionCoordinator` for all session lifecycle

3. **Test:** SessionCoordinator unit tests:
   - Queue drains only on Idle/Created
   - No timer polling exists
   - Concurrent submits are serialized

### B2. D6: Point-action executor dedup (P1)

**Problem:** ClickExecutor (174 lines) and LongPressExecutor (181 lines) share 73.6% code.

**Changes:**
1. Create `tool/action/executor/PointActionExecutorCore.kt`:
   - Shared: target resolution, bounds check, channel fallback loop, post-capture, success/failure formatting
   - Generic over channel type (ClickChannel / LongPressChannel)
   - Core function: `executePointAction(target, channels, actionPerformer, snapshot, platform, isCancelled)`

2. Thin wrappers:
   - `ClickExecutor`: calls core with click channels and tap performer
   - `LongPressExecutor`: calls core with long-press channels and long-press performer

3. **Test:** Existing executor tests must continue passing unchanged

### B3. D7+D8: AgentSession cleanup + checkpoint derivation (P2)

**Problem:** `handleUserInput` uses chained if-statements. `channelCloseScheduled` may be dead. Checkpoint always hardcodes `RUNNING_DIRTY`.

**Changes:**
1. `AgentSession.kt`:
   - Refactor `handleUserInput()` from chained `if` to explicit `when(_state.value)`
   - Audit and remove `channelCloseScheduled` if dead
   - Extract small helpers for readability

2. `SessionCheckpointCoordinator.kt`:
   - Accept current session state as parameter to `scheduleCheckpoint()`
   - Derive: running/paused -> `RUNNING_DIRTY`, idle -> `IDLE_READY`
   - Update callers (mutation listener)

3. **Test:** Verify idle-timeout and hot-idle follow-up transitions remain correct

---

## Phase C — Cleanup

1. Remove dead code: `onTaskCompleted` no-op hook if present, unused `autoStart` payload
2. Remove any backward-compat shims
3. Final code simplification pass

---

## Execution Order

```
Phase A (sequential, correctness-first):
  A1 → A2 → A3 → verify → commit

Phase B (can be parallelized per track):
  B1 (SessionCoordinator) → verify → commit
  B2 (Executor dedup) → verify → commit
  B3 (AgentSession + checkpoint) → verify → commit

Phase C:
  Cleanup → verify → commit
```

Each phase follows: implement → TDD → code-review → fix issues → commit

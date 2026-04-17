# State Management & Concurrency Review

Scope: session/ (13 files), protocol/ (27 files), history/ (15 files), Agent.kt, ToolRouter.kt, PolicyEngine.kt, ChatViewModel.kt, ToolRegistry.kt.

---

## Perspective A: Correctness & Thread Safety

### 1. FINDINGS: Thread Safety Issues

#### A1. SessionAgentRunner: start() publishes state AFTER launching coroutine [RACE]

In `SessionAgentRunner.start()`, the coroutine is launched at line 87 and the `state` field is only updated at line 104 inside `synchronized(stateLock)`. Between these two points, the coroutine has already started executing `newAgent.run()`. If `pause()`, `resume()`, or `stop()` is called in that window, they read stale state (agent=null) and silently no-op.

**Impact**: Low-moderate. Practically unlikely because `start()` returns before the caller could issue pause/stop, but the pattern is wrong on principle.

**Fix**: Move the `synchronized(stateLock)` assignment BEFORE the `scope.launch` call (capture the Job afterwards via `val newAgentJob = scope.launch {...}`, then assign in a second `synchronized` block, OR use a two-phase approach).

#### A2. HistoryManager: onMutation callback invoked OUTSIDE @Synchronized blocks [RACE]

Every mutation method (addItem, recordItems, compress, clear) calls `onMutation?.invoke()` after releasing the intrinsic lock. The onMutation callback is `scheduleCheckpoint()`, which reads `_state.value` and calls `historyManager.getAll()`. Because the lock is released before the callback, another thread could mutate the items list between the mutation and the callback's `getAll()` call.

**Impact**: Low. The checkpoint captures a slightly-after snapshot, which is acceptable since checkpoints are debounced anyway. But `onMutation` itself is a `var` without synchronization -- `setMutationListener(null)` during shutdown (line 564 AgentSession.kt) could race with a concurrent `addItem` invoking the old listener.

**Fix**: Either make `onMutation` `@Volatile` (like ScratchpadState does) or invoke the callback inside the synchronized block.

#### A3. TodoState: onMutation is not @Volatile [RACE]

`ScratchpadState.onMutation` is correctly annotated `@Volatile`. `TodoState.onMutation` is not. If `setMutationListener(null)` is called from one thread while `update()` calls `onMutation?.invoke()` from another, the reader may see a stale non-null reference.

**Impact**: Low. The listener invocation after null-set is a harmless extra checkpoint schedule. But inconsistent with ScratchpadState.

**Fix**: Add `@Volatile` to `TodoState.onMutation`.

#### A4. ToolRegistry: not thread-safe [RACE]

`ToolRegistry.tools` is a plain `mutableMapOf()`. It is mutated via `register()` from `SessionAgentRunner.ensureDelegationToolRegistered()` (called from within a coroutine) and read via `ToolRouter.execute()` (also in a coroutine). Both share the same `ToolRegistry` instance via `SessionServices`. No synchronization exists.

**Impact**: Moderate. In practice, registration happens once before the first `execute()` call, so collisions are rare. But if a second task is started (Hot Idle follow-up), `ensureDelegationToolRegistered()` reads `contains()` while `execute()` reads `get()` -- both on a `HashMap` that was potentially being written.

**Fix**: Use `ConcurrentHashMap` for `tools`, or add `@Synchronized` to mutating methods.

#### A5. SessionCoordinator: main-thread-confined fields accessed from mutex-guarded coroutines [DESIGN TENSION]

The class header states "All public methods must be called from the main thread" but also uses a `Mutex` for serialization. The `Mutex` is necessary for coroutine suspension (inside `createAndSubmit`, `submit`, `clearSession`), but `enqueue()`, `attachSession()`, `detachSession()`, `consumeDeadSessionFileName()` are NOT guarded by the mutex and rely solely on main-thread confinement. The `observeSessionState` collector runs in `scope` which may be `Dispatchers.Main.immediate`, but `drainPending()` acquires the mutex from within that collector.

**Impact**: Low if all callers honor main-thread discipline. But this dual strategy (mutex + thread confinement) is fragile -- a single off-main call breaks the contract silently.

#### A6. Agent.stop() writes to pauseState without lifecycle mutex [MINOR RACE]

`Agent.stop()` sets `pauseState.value = false` without acquiring `lifecycleMutex`. Meanwhile, `pause()` and `resume()` do acquire it. If `stop()` and `pause()` race, `pauseConfirmed` may never complete (the agent exits the pause-wait because `pauseState` went false, but `pauseConfirmed` was set by `pause()` after `stop()` already cleared it).

**Impact**: Low. The deferred simply goes un-completed; the `AgentSession.handleTakeover()` that called `pause()` already set `_state = Paused` but the agent loop exits anyway via `shouldContinue()` returning false.

#### A7. ChatEventReducer: synchronized on chatStateLock but messages is SnapshotStateList [CORRECT BUT FRAGILE]

`ChatEventReducer.handle()` synchronizes on `chatStateLock`. The `messages` list is a `SnapshotStateList` (Compose observable). Compose reads the list on the main thread without holding `chatStateLock`. This works because `SnapshotStateList` has its own thread-safe snapshot mechanism, but the `synchronized` block is protecting compound read-modify-write operations (e.g., find last agent message, then update it). The external lock prevents the reducer from interleaving with itself but does NOT prevent Compose reads from seeing partial state within a multi-step update.

**Impact**: Minimal. Compose snapshot isolation means readers see a consistent "before" or "after" view. The lock is defensive.

---

### 2. FINDINGS: State Machine Validity

#### B1. AgentSession state machine: all transitions are valid [OK]

The state machine (Created -> Running -> Idle -> Running | Shutdown; Running -> Paused -> Running; * -> Shutdown) is enforced via guard clauses at the top of each handler. No impossible states are reachable. The idempotency guard in `handleShutdown()` is correct.

#### B2. ToolRouter state machine: cleanup is correct [OK with note]

The `activeToolCalls.remove(resolvedCallId)` is called in every terminal path AND in a `finally` block on the execution path. The `finally` block (line 303) ensures cleanup even on unexpected exceptions. The `pendingApprovals.remove()` is called in the `finally` of the approval wait block. This is correct.

**Note**: `cancelAll()` calls `pendingApprovals.values.forEach { it.complete(ABORT) }` then `pendingApprovals.clear()`. There is a tiny window where a new approval could be added between `forEach` and `clear()`. However, since `cancelAll()` is only called during cleanup (shutdown), no new approvals would be created.

#### B3. SessionState: no "zombie" state possible [OK]

If `handleAgentComplete` is called while `_state == Shutdown`, it returns early (line 339). This prevents the session from moving backward from Shutdown to Idle. The `handleShutdown` idempotency guard prevents double-cleanup.

---

### 3. FINDINGS: Coroutine Scope & Lifecycle

#### C1. AgentSession scope is externally provided [OK]

The `scope` parameter comes from the caller (typically `lifecycleScope` of the service). When the service is destroyed, the scope is cancelled, which cancels the agent job and idle timeout job. The `NonCancellable` wrapper in `deliverCompletion` (SessionAgentRunner line 112) ensures the completion callback runs even during cancellation.

#### C2. SessionRecordingService: scope.launch jobs could outlive the session [LOW RISK]

`scheduleSave()` and `scheduleCheckpoint()` launch coroutines in `scope`. If the scope is cancelled while a save is in-flight, the coroutine is cancelled and the file write may be interrupted. `clearSessionAndAwait()` cancels and joins pending jobs, which is correct. `completeSession()` does NOT await the final save -- it launches in `scope` and returns. If scope is cancelled immediately after, the final save may be lost.

**Impact**: Low. The IDLE_READY checkpoint (written synchronously before `handleAgentComplete` returns) serves as the recovery point. The session-record save is supplementary.

#### C3. No coroutine leaks detected [OK]

`idleTimeoutJob` is cancelled in `cancelIdleTimeout()` and `handleShutdown()`. `stateObserverJob` is cancelled in `teardownLocked()` and `detachSession()`. `eventCollectionJob` is cancelled in `onCleared()`. `saveJob` and `checkpointSaveJob` are cancelled in `clearSessionAndAwait()`.

---

### 4. FINDINGS: Deadlock Analysis

#### D1. No deadlock possible in current code [OK]

Lock ordering analysis:
- `SessionCoordinator.mutex` (Kotlin Mutex, non-reentrant)
- `SessionRecordingService.stateLock` (synchronized)
- `HistoryManager` (intrinsic @Synchronized)
- `TodoState.lock`, `ScratchpadState.lock` (synchronized)
- `SessionAgentRunner.stateLock` (synchronized)
- `Agent.lifecycleMutex` (Kotlin Mutex)

No code path acquires two of these locks in conflicting order. The `SessionCoordinator.mutex` is the outermost lock; it calls into `AgentSession.submit()` which calls `HistoryManager.addItem()` -- this is always outer-to-inner, never reversed.

---

### 5. FINDINGS: Memory / Resource Leaks

#### E1. WeakHashMap in SessionLlmBootstrapper [OK]

`cachedCatalogByAssets` uses `WeakHashMap<AssetManager, ModelCatalog>`. The key (`AssetManager`) is held by the `Context`, so the cache entry is collected when the Context is GC'd. Correct.

#### E2. SessionServices.cleanup() is comprehensive [OK]

Calls `toolRouter.cancelAll()`, `userResponseChannel.cancel()`, `historyManager.clear()`, `platform.stop()`, `llmClient.cleanup()`, `llmClientFactory.cleanupAll()`, `traceRecorder.close()`. No leaked resources.

---

## Perspective B: Simplicity & Minimality

### 6. FINDINGS: Unnecessary Complexity

#### F1. Dual synchronization strategy in SessionCoordinator [UNNECESSARY COMPLEXITY]

The class uses both a `Mutex` (for coroutine-suspending operations) and main-thread confinement (for non-suspending operations). This creates cognitive overhead and a fragile contract. Since the class is already using a Mutex, ALL state-mutating operations should go through it (or all should use main-thread confinement with `withContext(Dispatchers.Main)`).

#### F2. SessionCheckpointCoordinator is a thin wrapper [MINOR]

The class has 3 public methods and 1 private builder. It exists to separate checkpoint-building from AgentSession. While this is clean separation, the `scheduleCheckpoint` method just calls `recordingService.scheduleCheckpoint()` with a lambda. The indirection adds a layer without significant logic.

#### F3. Three separate bootstrapper objects [ACCEPTABLE]

`SessionHistoryBootstrapper`, `SessionLlmBootstrapper`, `SessionToolingBootstrapper` each create a small cluster of objects. This is clean and aids testability. No simplification needed.

#### F4. AgentMessageBuffer state management [OK]

The buffer tracks partial agent message state (text accumulation, action interleaving). This complexity is inherent to the streaming message model. The `finalizeSnapshot()` / `buildPartialSnapshot()` split is clean.

#### F5. Protocol module: 27 files, mostly ~10 lines each [ACCEPTABLE]

Each event is a separate file. This is verbose but follows Kotlin convention of one-class-per-file for data classes. The alternative (grouping in fewer files) would not reduce complexity meaningfully. The domain-marker interfaces (`SessionLifecycleEvent`, `TurnDomainEvent`, etc.) provide useful categorization for event routing.

#### F6. SessionHistoryManager: sessionInfoCache uses ConcurrentHashMap + Mutex [REDUNDANT]

`sessionInfoCache` is a `ConcurrentHashMap` (thread-safe reads/writes) but cache operations are also guarded by `cacheMutex`. Either ConcurrentHashMap alone or Mutex-guarded HashMap would suffice.

**Fix**: Use plain `HashMap` guarded by `cacheMutex`, or remove `cacheMutex` and rely on ConcurrentHashMap atomicity.

#### F7. HistoryManager: lastTokenEstimate nullable cache [OK]

The nullable `Long?` cache pattern (set to null on mutation, lazily recomputed) is simple and correct. Not over-engineered.

#### F8. TodoState invariant check in update() [OK]

The `require(IN_PROGRESS count <= 1)` check is a useful invariant enforcement. Not over-complex.

---

## Synthesis

### Overall Assessment

The state management is **well-designed and largely correct**. The session state machine is clean, lifecycle management is disciplined, and the separation between protocol (immutable events), session (state coordination), and history (persistence) is sound.

The **concurrency model is pragmatic**: each component uses the simplest appropriate mechanism (intrinsic locks for simple state, ConcurrentHashMap for concurrent collections, Mutex for suspending operations, AtomicReference/AtomicBoolean for single-value atomics).

### Prioritized Issues

| ID | Severity | Component | Issue |
|----|----------|-----------|-------|
| A4 | **Moderate** | ToolRegistry | Unsynchronized HashMap accessed from multiple coroutines |
| A1 | Low-Mod | SessionAgentRunner | State published after coroutine launch |
| A3 | Low | TodoState | Missing @Volatile on onMutation |
| A2 | Low | HistoryManager | onMutation callback outside lock, not @Volatile |
| F1 | Low | SessionCoordinator | Dual sync strategy fragility |
| F6 | Low | SessionHistoryManager | Redundant ConcurrentHashMap + Mutex |
| A6 | Low | Agent | stop() bypasses lifecycleMutex |
| C2 | Low | SessionRecordingService | completeSession final save not awaited |

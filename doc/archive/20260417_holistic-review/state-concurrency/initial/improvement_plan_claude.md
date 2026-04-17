# State & Concurrency Improvement Plan

Based on review.md findings. Ordered by risk and effort. All items are reduction-oriented -- no new abstractions.

---

## P0: Fix before next release

### 1. ToolRegistry: use ConcurrentHashMap

**File**: `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRegistry.kt`

**Change**: Replace `mutableMapOf<String, ToolSpec>()` with `ConcurrentHashMap<String, ToolSpec>()`.

**Why**: `register()` is called from `SessionAgentRunner.start()` (coroutine on default dispatcher) while `get()` is called from `ToolRouter.execute()` (also coroutine). Plain HashMap is not thread-safe for concurrent read/write.

**Effort**: 1 line change. No API change.

```kotlin
// Before
private val tools = mutableMapOf<String, ToolSpec>()

// After
private val tools = ConcurrentHashMap<String, ToolSpec>()
```

---

## P1: Fix soon (low risk but incorrect patterns)

### 2. TodoState: add @Volatile to onMutation

**File**: `app/src/main/kotlin/com/moonkey/androidagent/session/TodoState.kt`

**Change**: Add `@Volatile` annotation to `onMutation` field.

**Why**: Matches ScratchpadState convention. Prevents stale reads when `setMutationListener(null)` is called from a different thread than `update()`.

**Effort**: 1 line.

```kotlin
// Before
private var onMutation: (() -> Unit)? = null

// After
@Volatile private var onMutation: (() -> Unit)? = null
```

### 3. HistoryManager: add @Volatile to onMutation

**File**: `app/src/main/kotlin/com/moonkey/androidagent/history/HistoryManager.kt`

**Change**: Add `@Volatile` annotation to `onMutation` field.

**Why**: Same reasoning as TodoState. The field is read outside `@Synchronized` blocks (in callback invocation after lock release).

**Effort**: 1 line.

```kotlin
// Before
private var onMutation: (() -> Unit)? = null

// After
@Volatile private var onMutation: (() -> Unit)? = null
```

### 4. SessionAgentRunner: assign state before launching coroutine

**File**: `app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt`

**Change**: Move the `synchronized(stateLock) { state = ... }` block before `scope.launch`. Capture the job reference via a variable.

**Why**: `pause()`, `resume()`, `stop()` read `state.agent` inside `synchronized(stateLock)`. If called before the post-launch assignment, they see `agent = null` and silently no-op.

**Effort**: Small refactor (~10 lines).

```kotlin
// Before (simplified):
val newAgentJob = scope.launch { newAgent.run() ... }
synchronized(stateLock) {
    state = RunnerState(agent = newAgent, agentJob = newAgentJob, ...)
}

// After:
val signal = CompletableDeferred<AgentStopReason>()
val newAgent = Agent(...)
// Pre-register state with null job -- pause/stop can find the agent immediately
synchronized(stateLock) {
    state = RunnerState(agent = newAgent, agentJob = null, cancellationSignal = signal)
}
val newAgentJob = scope.launch { ... }
synchronized(stateLock) {
    state = state.copy(agentJob = newAgentJob)
}
```

---

## P2: Cleanup (simplification, no correctness impact)

### 5. SessionHistoryManager: remove redundant ConcurrentHashMap + Mutex

**File**: `app/src/main/kotlin/com/moonkey/androidagent/history/SessionHistoryManager.kt`

**Change**: Replace `ConcurrentHashMap<String, CachedSessionInfo>` with plain `HashMap`. All access is already guarded by `cacheMutex`.

**Why**: Using both ConcurrentHashMap and a Mutex is redundant. The `cacheMutex.withLock` blocks already serialize access. Pick one strategy.

**Effort**: 1 line change.

```kotlin
// Before
private val sessionInfoCache = ConcurrentHashMap<String, CachedSessionInfo>()

// After
private val sessionInfoCache = HashMap<String, CachedSessionInfo>()
```

### 6. Agent.stop(): acquire lifecycleMutex (optional hardening)

**File**: `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt`

**Change**: The simplest fix is to NOT acquire the mutex (stop is intentionally fire-and-forget). However, `pauseState.value = false` should be removed from `stop()` -- the `shouldContinue()` check already causes the agent loop to exit, and the `pauseState.first { !it }` in the run loop will unblock naturally when the coroutine is cancelled.

**Why**: `stop()` setting `pauseState.value = false` without the mutex can interleave with `pause()` setting `pauseState.value = true` + `pauseConfirmed = deferred`. This can leave `pauseConfirmed` un-completed. Removing the `pauseState` write from `stop()` avoids the race entirely -- `stopRequested.set(true)` is sufficient.

**Effort**: 1 line removal.

```kotlin
// Before
fun stop() {
    stopRequested.set(true)
    pauseState.value = false  // <-- remove this
}

// After
fun stop() {
    stopRequested.set(true)
}
```

**Caveat**: If the agent is currently paused (waiting on `pauseState.first { !it }`), it will NOT unblock from the stop alone. The coroutine cancellation (from `SessionAgentRunner.shutdown()` calling `agentJob.cancel()`) handles that case. For the `stop()` path (interrupt without shutdown), the agent remains paused until resumed or the scope is cancelled. This may be intentional -- interrupt does not force-unpause. If force-unpause on interrupt is desired, keep the line but wrap it in `lifecycleMutex` (requires making `stop()` suspend).

---

## Not Recommended (reviewed and rejected)

### SessionCheckpointCoordinator extraction
Considered inlining it back into AgentSession. Rejected: the class correctly separates snapshot-building concern. 3 public methods is not over-abstracted.

### Consolidating bootstrapper objects
Considered merging the 3 bootstrappers into SessionServices.create(). Rejected: they are already effectively inlined there. The separate objects aid testability and keep individual files small.

### SessionCoordinator dual sync strategy
Considered converting all operations to mutex-guarded. Rejected: `enqueue()` is called from `onSessionNeeded` callback (potentially from Compose), where suspending is undesirable. The current main-thread-confinement contract for non-suspending methods is acceptable if documented and enforced by callsite convention.

---

## Implementation Order

1. **P0-1** (ToolRegistry): Immediate, 1 line.
2. **P1-2** (TodoState @Volatile): Immediate, 1 line.
3. **P1-3** (HistoryManager @Volatile): Immediate, 1 line.
4. **P1-4** (SessionAgentRunner state ordering): Next PR, small refactor.
5. **P2-5** (SessionHistoryManager cache): Next cleanup pass.
6. **P2-6** (Agent.stop): Next cleanup pass, needs decision on interrupt-unpause semantics.

Total effort: ~30 minutes for P0+P1, ~1 hour including P2.

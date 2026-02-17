# Code Review: Phase 1 — Runtime Safety & Bootstrap (commit 15b6a74)

**Reviewer**: Claude
**Date**: 2026-02-16
**Commit**: `15b6a74 fix: harden phase1 runtime safety and bootstrap flow`
**Scope**: 12 files, +498/-324 lines

---

## Summary

Phase 1 addresses MT1/MT2 (main-thread safety), TC1 (completion finalize convergence), H1 (multi-tool loop detection), H4 (trace channel bound), and G1/G2/G3/G6/G8/G9/G10 (concurrency hardening). Overall the changes are well-targeted and correct. Two issues found.

---

## High

### 1. SessionRecordingService: stale-read-then-overwrite race in `recordScreenState()`

**File**: `SessionRecordingService.kt:200-240` (post-change)
**What**: `recordScreenState()` reads `currentSession` inside a synchronized block, then does normalization and metadata computation OUTSIDE the lock, then writes `currentSession` inside a SECOND synchronized block. Between these two lock acquisitions, another thread can modify `currentSession` via `recordUserMessage()`, `startAgentMessage()`, etc. The final write overwrites those changes.

```kotlin
fun recordScreenState(state: ScreenStateRecord) {
    val session = synchronized(stateLock) { currentSession ?: ... return }  // read
    val normalizedState = normalizeScreenStateRecord(state)  // work outside lock
    val updatedMetadata = ...                                 // work outside lock
    synchronized(stateLock) {                                 // write — can overwrite
        currentSession = session.copy(...)                    // stale session.copy()
    }
}
```

**Fix**: Either hold the lock for the entire method, or re-read `currentSession` inside the second synchronized block and apply changes to the latest value. Since `normalizeScreenStateRecord()` is a pure function and the metadata logic is lightweight, holding the lock throughout is simplest.

### 2. SessionRecordingService: `finalizeCurrentAgentMessage()` and `updateAgentMessageInSession()` access `agentMessageBuffer` outside `stateLock`

**File**: `SessionRecordingService.kt:282,322` (post-change)

`finalizeCurrentAgentMessage()` calls `agentMessageBuffer.finalizeSnapshot()` BEFORE acquiring `stateLock`. Currently all callers hold `stateLock` when calling this (reentrant), so it's safe TODAY. But the function's signature doesn't communicate this requirement. Similarly, `updateAgentMessageInSession()` calls `agentMessageBuffer.buildPartialSnapshot()` outside the lock.

If a future caller invokes these without holding the lock, `agentMessageBuffer` access races with `appendTextDelta()`, `recordAction()`, etc.

**Fix**: Either (a) add a `// Caller must hold stateLock` comment/annotation, or (b) move the buffer access inside the synchronized block for self-containment. Option (b) is safer since `synchronized` is reentrant — it's a no-op when already held.

---

## Medium

### 3. MainActivity: recursive `postDelayed` retry during session creation

**File**: `MainActivity.kt:390-397` (post-change)

When `sessionCreationInProgress == true`, the code falls through to a retry path that uses `window.decorView.postDelayed({ ensureSessionAndSend(text) }, 200)`. If multiple user messages arrive during session creation, each one posts another delayed retry, which can compound into multiple queued sends.

```kotlin
if (!shouldCreate) {
    lifecycleScope.launch {
        val active = currentSession
        if (active != null) {
            active.submit(Op.UserInput(text))
        } else {
            window.decorView.postDelayed({ ensureSessionAndSend(text) }, 200)
        }
    }
    return
}
```

**Risk**: Not a crash, but could result in duplicate message processing or unnecessary retries.
**Fix**: Use a `Channel` or pending-message queue instead of polling. Or at minimum, only retry once (track whether a retry is already pending).

### 4. SessionLlmBootstrapper: `requireOffMainThread()` warns but doesn't throw

**File**: `SessionLlmBootstrapper.kt:82-88`

The function logs a warning when called on the main thread but doesn't enforce it with an exception. While callers have been updated to use `withContext(Dispatchers.Default)`, a future caller could miss this and the warning would be buried in logcat.

**Fix**: Consider `check(Looper.myLooper() != Looper.getMainLooper()) { "..." }` for hard enforcement in debug builds, with the warning fallback in release.

---

## Low

### 5. VirtualDisplaySurfaceController: Shizuku IPC inside synchronized block

**File**: `VirtualDisplaySurfaceController.kt:55-73` (post-change)

`shizuku.setVirtualDisplaySurface()` is called inside `synchronized(stateLock)`. This is correct for consistency (prevents another thread from switching mode while IPC is in flight). However, if the Shizuku IPC is slow, it blocks other writers calling `switchToImageReader()` or `reset()`.

**Impact**: Low — readers (`mode()`, `liveSurfaceView()`) are NOT blocked since they read the `@Volatile` field directly. Only writer contention is affected.
**Action**: Acceptable trade-off. Document the locking rationale.

### 6. SessionAgentRunner: `state` field is not `@Volatile`

**File**: `SessionAgentRunner.kt:41`

`private var state = RunnerState(...)` — not `@Volatile`. All reads/writes go through `synchronized(stateLock)` which provides happens-before guarantees, so this is correct. But it's inconsistent with `VirtualDisplaySurfaceController` which uses `@Volatile` for the same pattern. Adding `@Volatile` would be defensive.

---

## Per-Fix Assessment

| Fix | Status | Notes |
|-----|--------|-------|
| G1: SessionAgentRunner | ✓ Correct | Clean sealed-state + lock pattern. `shutdown()` does snapshot-then-clear atomically. |
| G2: UserResponseChannel | ✓ Correct | AtomicReference + CAS. Lock-free, correct. `deliver()` does get → check → CAS → complete. |
| G3: VirtualDisplaySurfaceController | ✓ Correct | Bundled state + @Volatile + synchronized. Readers see consistent snapshots. |
| G6: AgentService | ✓ Correct | `isServiceActive` flag, `instance=null` moved up, guards on `submitOp`/`runAgent`/`observeExternalSession`. Session shutdown race fixed. |
| G8: SessionRecordingService | ⚠ Mostly correct | All state guarded, but `recordScreenState()` has stale-read-then-overwrite gap (High #1). |
| G9: LLMClientFactory | ✓ Correct | `getOrPut` → `computeIfAbsent`. One-line fix, atomic. |
| G10: LFMLLMClient | ✓ Correct | `modelMutex` now protects both `loadModel`, `cleanup`, and `getOrLoadModel`. `loadModelLocked()` extraction is clean. |
| MT1: MainActivity off-main | ✓ Correct | `withContext(Dispatchers.Default)` wrapping `AgentSession.create()`. |
| MT1: AgentService off-main | ✓ Correct | Same pattern in `runAgent()`. |
| MT2: Model catalog cache | ✓ Correct | `WeakHashMap<AssetManager, ModelCatalog>` with `synchronized(catalogLock)`. Cache invalidates when AssetManager is GC'd. |
| TC1: Completion convergence | ✓ Correct | `AgentServiceEventHandler` now calls `completeSession()` (was `completeAgentMessage()`). `MainActivity` callback removed. Single owner. |
| H1: Multi-tool loop detection | ✓ Correct | Uses first tool's action for loop detection instead of last. Simple, effective. |
| H4: Trace channel bound | ✓ Correct | `Channel(2048)` replaces `Channel.UNLIMITED`. `trySend()` drops on full — acceptable for tracing. |

---

## Recommendation

**CHANGES_REQUESTED** — High #1 (`recordScreenState()` stale-read-then-overwrite) should be fixed. The rest is solid.

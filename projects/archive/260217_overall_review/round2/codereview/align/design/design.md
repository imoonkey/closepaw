# Aligned Code Review: Phase 2+3 (commit 61db9b2)

**Reviewers**: Claude, Codex
**Commit**: `61db9b2 refactor: phase 2+3 craftsmanship — DRY, split, generics, design smells`
**Scope**: 39 Kotlin files + 24 doc renames, +1737/-1579 lines

---

## Agreed: Phase 1 Review HIGHs — All Fixed

Both reviewers confirm the Phase 1 review HIGHs are resolved:
- `recordScreenState()` stale-read-then-overwrite: entire read-modify-write now in single `synchronized(stateLock)` block
- `finalizeCurrentAgentMessage()` / `updateAgentMessageInSession()` buffer outside lock: both now fully inside `synchronized(stateLock)`

---

## Critical

None.

---

## High

### H1 (Codex): sessionRetryScheduled silently drops subsequent user inputs

**File**: `MainActivity.kt:256-284`
**Source**: Codex
**Status**: ACCEPTED by Claude — verified by code reading

When `sessionRetryScheduled == true` (a retry is already pending), subsequent `ensureSessionAndSend(text)` calls silently discard the `text` — the `if (shouldScheduleRetry)` block is the only path that captures the text, and when it's false, nothing happens with the message.

```kotlin
if (shouldScheduleRetry) {
    window.decorView.postDelayed({
        synchronized(sessionCreationLock) { sessionRetryScheduled = false }
        ensureSessionAndSend(text)   // only first text is captured
    }, 200)
}
// else: text2 is silently lost
```

**Fix**: Replace single-flag retry with a pending-input queue, or schedule one retry per distinct input payload.

### H2 (Codex): completedNormally=true for all completion reasons — PRE-EXISTING

**File**: `SessionRecordingService.kt:212`, `AgentServiceEventHandler.kt:75-78`
**Source**: Codex
**Status**: ACCEPTED by Claude — verified, but **pre-existing**, not a regression from this commit

`completeSession()` unconditionally sets `completedNormally = true` regardless of `CompletionReason`. `TaskCompleted` event carries `event.reason` (`ERROR`, `USER_STOPPED`, `INTERRUPTED`, etc.) but doesn't pass it to `completeSession()`.

This was NOT introduced by the Phase 2+3 changes — `completeSession()` was not modified in this commit. However, it is a real semantic incorrectness.

**Fix**: Add `reason: CompletionReason` parameter to `completeSession()` and derive `completedNormally` from it.

---

## Medium

### M1 (Codex): recordUserMessage() stale snapshot after finalizeCurrentAgentMessage()

**File**: `SessionRecordingService.kt:87-108`
**Source**: Codex
**Status**: ACCEPTED by Claude — verified by code reading

```kotlin
fun recordUserMessage(...) {
    val recorded = synchronized(stateLock) {
        val session = currentSession ?: ...       // ← captures old ref
        finalizeCurrentAgentMessage()              // ← modifies currentSession (reentrant)
        currentSession = session.copy(             // ← overwrites with stale ref
            messages = session.messages + userMessage, ...
        )
        true
    }
}
```

`finalizeCurrentAgentMessage()` merges the buffered agent message into `currentSession`. But `session` was captured BEFORE the finalize call, so `session.copy(...)` overwrites `currentSession` with the pre-finalize data, losing the just-finalized agent message.

**Fix**: Re-read `currentSession` after `finalizeCurrentAgentMessage()`:
```kotlin
finalizeCurrentAgentMessage()
val latestSession = currentSession ?: return@synchronized false
currentSession = latestSession.copy(messages = latestSession.messages + userMessage, ...)
```

### M2 (Claude): AgentServiceViewerBridge provider lambda race

**File**: `AgentServiceViewerBridge.kt:49-58`
**Source**: Claude
**Status**: LOW-MEDIUM, safe fallback

`onViewerTouch()` reads `overlayControllerProvider()` for the mode check and `platformProvider()` for the touch injection in separate calls. If the session tears down between these reads, touch returns `false` rather than `true`. Low risk — narrow window, safe fallback.

### M3 (Claude): isAccessibilityEnabled evaluated once at compose-time — PRE-EXISTING

**File**: `MainActivityContent.kt:27`, `MainActivity.kt:93`
**Source**: Claude
**Status**: Pre-existing behavior, not a regression from the file split.

### M4 (Codex): Test coverage gap

**Source**: Codex
**Status**: ACCEPTED

No unit tests exist for:
- Concurrent `ensureSessionAndSend()` calls during session creation
- `TaskCompleted(reason=ERROR|USER_STOPPED)` persisting correct metadata

---

## Agreed: All 19 Refactoring Items Correct

Both reviewers confirm all structural and quality refactoring items are correctly implemented:

| Fix ID | Description | Status |
|--------|-------------|--------|
| J1 | CloudStreamRetryRunner | ✓ |
| J2 | MessageContentExtractor | ✓ |
| J5 | Tool observation buildObservation() | ✓ |
| I1 | AgentService split | ✓ |
| I2 | MainActivity split | ✓ |
| I3 | HistoryManager split | ✓ |
| I4 | SessionRecordingService split | ✓ |
| I5 | AgentTrace split | ✓ |
| I6 | ChatViewModel split | ✓ |
| D1 | AccessibilityNodeFinder extraction | ✓ |
| D2 | NodeActionPerformer extraction | ✓ |
| K | SettingsDropdown\<T\> generics | ✓ |
| L1 | SessionServices data→class | ✓ |
| L2 | SessionServicesBuilder deleted | ✓ |
| L3 | AgentModelResolver fallback logging | ✓ |
| L4 | DEFAULT_MODEL hoisting | ✓ |
| L5 | ShizukuDisplayTransport helpers | ✓ |
| — | Prompt hygiene (StandaloneAgentDef) | ✓ |
| — | HistoryManager.getSummary() @Synchronized | ✓ |

---

## Recommendation

**CHANGES_REQUESTED** — H1 (input loss) and M1 (stale snapshot) are real bugs requiring fixes. H2 is pre-existing but should be tracked as a follow-up.

# Code Review 2 (Claude): Session Reload Implementation

## Summary

Implements LLM context persistence and session reload per the aligned design. Adds `SessionRuntimeSnapshot`/`PersistedHistoryItem` data model, bidirectional converter, checkpoint coordinator, atomic file I/O, lifecycle change (task completion → checkpoint → cleanup → `Completed`), and reload path in `MainActivity`. Fixes all 10 findings from Codex's code review.

**Build**: passes. **Tests**: all green.

## Codex Review Findings — Verification

| # | Finding | Status |
|---|---------|--------|
| 1 | Reload overrides recording | Fixed: `initializeNewSession` guard checks `recorderSessionId` (`AgentSession.kt:257-263`) |
| 2 | Config missing LLM routing | Fixed: `llmBackendType`/local config fields in `ConversationConfigSnapshot` (`SessionRuntimeSnapshot.kt:51-53`, `SessionCheckpointCoordinator.kt:86-88`) |
| 3 | Fresh session still reloads | Fixed: `FORCE_FRESH` launch policy (`MainActivity.kt:83-86`, `218`) |
| 4 | Delete doesn't delete context | Fixed: `deleteSessionPair()` (`SessionStorage.kt:239`, `SessionHistoryManager.kt:113,122`) + test |
| 5 | Task completion doesn't release resources | Fixed: `services.cleanup()` after checkpoint (`AgentSession.kt:337-341`) |
| 6 | forceCheckpoint is async | Fixed: `suspend` function, synchronous save (`SessionRecordingService.kt:307-315`) |
| 7 | Reload addItem triggers auto-compress | Fixed: `replaceAll()` skips compression (`HistoryManager.kt:72-82`) |
| 8 | scheduleCheckpoint not wired | Fixed: init block wires mutation listeners (`AgentSession.kt:199-203`) |
| 9 | Missing tests | Partially: converter + config round-trip tests added |
| 10 | Tests are red | Fixed: Click/LongPress tests updated for node-first priority |

## New Findings

### High

**H1. `HistoryManager.getAll()` not `@Synchronized` — race condition with debounced checkpoints**

`HistoryManager.kt:88`: `getAll()` does `items.toList()` without acquiring the intrinsic lock. All mutation methods (`addItem`, `recordItems`, `compress`, etc.) ARE `@Synchronized`. `forPrompt()` IS `@Synchronized`. During task execution, `scheduleCheckpoint` fires `buildSnapshot()` → `historyManager.getAll()` from the recording service's coroutine scope, while the agent concurrently calls `addItem()` from its own coroutine. Result: `ConcurrentModificationException` or partially mutated snapshot.

Fix: add `@Synchronized` to `getAll()`.

**H2. `handleShutdown()` has no guard for `Completed` state — potential double `services.cleanup()`**

`AgentSession.kt:435`: `handleShutdown()` unconditionally calls `services.cleanup()`. After the new lifecycle, `handleAgentComplete()` already calls `services.cleanup()` and transitions to `Completed`. If `Op.Shutdown` arrives while in `Completed` state (e.g., `debug-run.sh` `stop_agent` broadcast arrives before `AgentService.session` is nulled by the async event handler), `cleanup()` runs twice. `platform.stop()` on an already-stopped platform may throw.

Fix:
```kotlin
// top of handleShutdown()
if (_state.value == SessionState.Completed) {
    Log.d(TAG, "Session already completed, skipping shutdown")
    return
}
```

**H3. `@SerialName` discriminators missing on `PersistedHistoryItem` subtypes**

`SessionRuntimeSnapshot.kt:18-41`: The aligned design specified `@SerialName("message")`, `@SerialName("function_call")`, `@SerialName("function_call_output")`. The implementation omits them. Without explicit discriminators, kotlinx.serialization uses fully-qualified class names as type keys, making the JSON fragile to package renames and needlessly verbose. If a class is ever moved/renamed, existing checkpoint files become unreadable.

Fix:
```kotlin
@Serializable @SerialName("message")
data class Message(...) : PersistedHistoryItem

@Serializable @SerialName("function_call")
data class FunctionCall(...) : PersistedHistoryItem

@Serializable @SerialName("function_call_output")
data class FunctionCallOutput(...) : PersistedHistoryItem
```

### Medium

**M1. Missing test: `SessionRuntimeSnapshot` JSON serialization round-trip**

Converter round-trip test exists (`HistoryItemConverterTest`), but no test serializes a full `SessionRuntimeSnapshot` to JSON and deserializes it back. This is the critical persistence path — a serialization issue silently breaks reload. Core unit test per TDD.

**M2. Missing test: reload hydration produces identical `forPrompt()` output**

The byte-identical reproduction invariant (design §8) is the core guarantee. No test validates it. Should: create HistoryManager → add items → capture `forPrompt()` → persist via converter → reload into fresh HistoryManager via `replaceAll()` → assert `forPrompt()` identical.

**M3. `completeSession()` timing relative to `services.cleanup()`**

In `handleAgentComplete()`, `services.cleanup()` runs at line 338, then `SessionCompleted` is emitted at line 343. The event handler calls `recordingService?.completeSession()` in response. Since `cleanup()` doesn't touch `recordingService`, this works — but the ordering is fragile. If `cleanup()` ever starts cleaning up the recording service, `completeSession()` would operate on cleaned-up state. Consider calling `recordingService.completeSession()` directly in `handleAgentComplete()` before `services.cleanup()`.

### Low

**L1. Redundant schema version check**

`AgentSession.reload():110` checks `snapshot.schemaVersion != 1`, and `MainActivity.tryReloadSelectedSession():408` also checks the same. Harmless but redundant.

## Recommendation

**CHANGES_REQUESTED**

3 High fixes (all small, mechanical):
- H1: one-line `@Synchronized` annotation
- H2: 3-line state guard
- H3: 3 `@SerialName` annotations

2 Medium tests (M1, M2) for the core persistence guarantee.

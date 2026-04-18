# Error Handling Patterns

This document catalogs the error handling patterns used across the ClosePaw harness, with particular attention to **silent-failure sites** — places where exceptions or failed operations are swallowed (logged-only, defaulted, or returned as boolean false) without halting the surrounding flow.

Silent failures are not inherently wrong: many are deliberate trade-offs that prefer keeping the session alive over crashing on a non-critical failure. But they need to be enumerated so that future debugging knows where to look when state appears inconsistent without a thrown exception.

## Patterns

### 1. Boolean-returning side effects
Operations that may fail but whose failure should not stop the caller return a `Boolean` success flag. The caller decides whether to surface a user-visible warning. Example: checkpoint flushes return `false` on failure rather than throwing.

### 2. Catch-and-log (non-fatal)
A `try { … } catch (e: Exception) { Log.w(...) }` block protects a cleanup or background step where failure must not interrupt the larger lifecycle (e.g., releasing platform resources, flushing traces, collecting events). The exception is logged but never rethrown.

### 3. `valueOf` with default fallback
Enum deserialization from snapshots/persisted strings uses `try { Enum.valueOf(name) } catch (_: IllegalArgumentException) { DEFAULT }`. This makes restore tolerant of removed/renamed enum values across schema versions, at the cost of silently substituting a default.

### 4. CancellationException re-raise
Coroutine bodies that `catch (e: Exception)` must first re-raise `CancellationException` (`catch (e: CancellationException) { throw e }`) so that structured concurrency cancellation continues to propagate. See `AgentService.observeSession`.

### 5. Schema/version guard returning null
`AgentSession.reload` returns `null` (rather than throwing) when the snapshot schema version mismatches or the checkpoint state is not reloadable. Caller treats `null` as "start a new session."

## Silent-failure site catalog

The seven sites below intentionally swallow or default on failure. When investigating "state looks wrong but no exception was thrown," start here.

### (1) Checkpoint flush returns Boolean; caller logs only

- `app/src/main/kotlin/ai/closepaw/session/SessionCheckpointCoordinator.kt:46-54` — `flushIdleReady()` returns `Boolean`; on `false` it logs `"Failed to flush IDLE_READY checkpoint"` but does not throw.
- `app/src/main/kotlin/ai/closepaw/session/SessionCheckpointCoordinator.kt:57-64` — `flushClosed()` mirrors the same pattern for the CLOSED state.
- `app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:441-444` — Caller of `flushIdleReady()` checks the boolean, emits a status message (`"⚠️ Checkpoint save failed; session kept alive in memory."`), logs an error, and continues to transition the session to Idle. A failed checkpoint does not abort the task-completion flow; the session remains alive in memory and the user is told process-death recovery may not be available.

**Why silent**: a failed disk write should not crash an otherwise-successful task. The user is told via a status message and may re-issue the task; in-memory state is preserved.

### (2) Event collector catch-and-log

- `app/src/main/kotlin/ai/closepaw/app/AgentService.kt:279-296` — Inner `try` around `eventHandler.handleEvent(...)` catches per-event failures and logs `"Failed to handle event"`, allowing the collector to continue draining the flow. The outer `try` re-raises `CancellationException` and logs any other exception as `"Session event collector crashed"`.

**Why silent**: a single malformed event should not tear down the entire UI/recording pipeline.

### (3) `SessionServices.cleanup` non-fatal blocks (×4)

Each cleanup step is wrapped independently so that a failure in one does not skip the others.

- `app/src/main/kotlin/ai/closepaw/session/SessionServices.kt:211-215` — `platform.stop()` → logs `"Platform stop failed (non-fatal)"`.
- `app/src/main/kotlin/ai/closepaw/session/SessionServices.kt:218-222` — `llmClient.cleanup()` → logs `"LLM client cleanup failed (non-fatal)"`.
- `app/src/main/kotlin/ai/closepaw/session/SessionServices.kt:224-228` — `llmClientFactory.cleanupAll()` → logs `"LLM client factory cleanup failed (non-fatal)"`.
- `app/src/main/kotlin/ai/closepaw/session/SessionServices.kt:230-235` — `traceRecorder.close()` → logs `"Trace recorder close failed (non-fatal)"`.

**Why silent**: cleanup is best-effort. The session is going away regardless; partial leaks are preferable to throwing from a teardown path that may itself be running inside a `finally`/cancellation handler.

### (4) `AgentSession.reload` enum `valueOf` fallbacks (×4)

When restoring from a persisted snapshot, unknown enum names default rather than abort the restore.

- `app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:172-176` — `TodoStatus.valueOf(todo.status)` → falls back to `TodoStatus.PENDING`. Silent.
- `app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:202-207` — `TaskOutcome.valueOf(outcomeName)` → logs `"Unknown TaskOutcome in snapshot"` and skips setting the field. Logged but non-fatal.
- `app/src/main/kotlin/ai/closepaw/session/SessionCheckpointCoordinator.kt:114` — `AgentMode.valueOf(agentMode)` inside `ConversationConfigSnapshot.toSessionConfig()` → falls back to `AgentMode.PRO`. Silent.
- `app/src/main/kotlin/ai/closepaw/session/SessionCheckpointCoordinator.kt:121` — `PlatformMode.valueOf(platformMode)` → falls back to `PlatformMode.ACCESSIBILITY`. Silent.

(Two adjacent fallbacks — `LLMBackendType.valueOf` at line 123-127 and `ApprovalMode.valueOf` at line 138 — follow the same pattern; the four called out above are those referenced in the task scope as `TodoStatus + ConversationConfigSnapshot`.)

**Why silent**: schema evolution. A snapshot written by an older build may contain enum names that no longer exist; restore should still succeed with reasonable defaults rather than orphan the session.

## Guidelines

- **Prefer `Result`/`Boolean` over throwing** when failure is expected and the caller has a meaningful recovery (e.g., checkpoint flush).
- **Always re-raise `CancellationException`** inside catch-all blocks in coroutine code.
- **Log at `Log.w` for non-fatal**, `Log.e` for fatal-but-contained failures. Include the exception so stack traces reach logcat.
- **Document the why** when adding a new silent-failure site — append to this catalog so the rationale survives the next refactor.

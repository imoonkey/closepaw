# Review: round2 design implementation (Codex)

## Summary
- Scope: `b904592..HEAD`（Phase1 + Phase2/3 implementation）
- Checked: runtime correctness, concurrency/lifecycle safety, refactor regressions
- Verification run:
  - `./gradlew testDebugUnitTest` ✅
  - `./gradlew lintDebug` ✅

## Critical
- None found.

## High
1. Pending user inputs can be silently dropped while session creation is in progress.
- Where: `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:61`, `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:262`, `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:264`
- Why: `sessionRetryScheduled` allows only one delayed retry. If multiple inputs arrive before session creation finishes, only the first retry is scheduled; later inputs hit `sessionRetryScheduled == true` and are discarded.
- Impact: user-visible message loss under fast consecutive sends / automation bursts.
- Fix: replace single-flag retry with a pending-input queue (drain after session created), or schedule one retry per input payload.

2. Task completion metadata is recorded as "completedNormally=true" even for error/user-stopped completions.
- Where: `app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceEventHandler.kt:75`, `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:212`, `app/src/main/kotlin/com/moonkey/androidagent/protocol/CompletionReason.kt:18`
- Why: `TaskCompleted` always calls `recordingService.completeSession()`, and `completeSession()` unconditionally writes `completedNormally = true`.
- Impact: persisted session metadata is semantically wrong for `ERROR` / `USER_STOPPED` / `INTERRUPTED`, which can skew history UX/analytics.
- Fix: pass completion reason into recording (`completeSession(reason)`), and derive `completedNormally` from reason (`GOAL_ACHIEVED` / maybe `MAX_TURNS` only).

## Medium
1. `recordUserMessage()` can overwrite a just-finalized agent message due to stale session snapshot.
- Where: `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:90`, `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:98`, `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:101`
- Why: method captures `session = currentSession`, then calls `finalizeCurrentAgentMessage()` (which may update `currentSession`), then writes `currentSession = session.copy(...)` using stale pre-finalize data.
- Impact: potential message loss in edge ordering where an unfinished agent buffer exists when user message is recorded.
- Fix: re-read `currentSession` after finalize, or perform both finalize+append in one immutable transform against latest state.

2. Test coverage gap for the two regressions above.
- Where: no unit tests found for session-creation retry queueing and completion-reason-to-metadata mapping.
- Impact: these regressions are easy to reintroduce during future refactors.
- Fix: add focused tests for:
  - concurrent `ensureSessionAndSend()` calls before session materialization;
  - `TaskCompleted(reason=ERROR|USER_STOPPED)` persisting `completedNormally=false`.

## Recommendation
**CHANGES_REQUESTED**

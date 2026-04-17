REQUEST-CHANGES

## High

1. `SessionRecordingService.completedNormally` still depends on a transient `lastTaskOutcome` cache that can be stale or missing.
   `lastTaskOutcome` is only written on `TaskCompleted`, never cleared when a new task starts, and never restored on reload. That leaves two bad paths: `task A succeeds -> task B starts -> user shuts down while task B is still running` can persist `completedNormally=true` from task A, and a hot-idle reload can lose the previous successful outcome entirely because the checkpoint/session snapshot does not store it. `Op.Shutdown` goes straight to `handleShutdown()`, while `handleAgentComplete()` returns immediately once the session is already `Shutdown`, so the running task never gets a correcting `TaskCompleted(USER_STOPPED)` update. Refs: `app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceEventHandler.kt:37-45`, `app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceEventHandler.kt:74-84`, `app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceEventHandler.kt:113-123`, `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:34`, `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:96-108`, `app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:213-221`, `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:257-266`, `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:393-396`, `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:546-582`, `app/src/main/kotlin/com/moonkey/androidagent/session/SessionCheckpointCoordinator.kt:68-81`, `app/src/main/kotlin/com/moonkey/androidagent/history/model/SessionRuntimeSnapshot.kt:6-17`, `app/src/main/kotlin/com/moonkey/androidagent/history/model/SessionRuntimeSnapshot.kt:58-74`.

## Medium

1. The checkpoint round-trip test was not updated to prove the six new runtime-affecting fields survive persist+restore.
   The implementation in `SessionCheckpointCoordinator` now round-trips `actionDelayMs`, `approvalMode`, `debugMode`, `traceEnabled`, `traceRunId`, and `excludedTools`, but `SessionCheckpointConfigSnapshotTest` still only asserts the pre-existing LLM-routing fields. This leaves the exact regression this commit fixed uncovered. Refs: `app/src/main/kotlin/com/moonkey/androidagent/session/SessionCheckpointCoordinator.kt:85-140`, `app/src/test/kotlin/com/moonkey/androidagent/session/SessionCheckpointConfigSnapshotTest.kt:15-48`.

2. There is no session-level regression test for the approval-policy invariant.
   The security fix in `AgentSession.handleApproval()` looks mechanically correct, and I did not find any remaining `allowPackage*` call outside the `resolveApproval()` gate. But the current tests do not exercise the session path that matters here: unmatched or duplicate `Op.Approve` must not mutate policy. `ToolRouterTest` only covers router-side approval resolution, while `AgentSessionTest` does not cover `Op.Approve` at all. Refs: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:587-603`, `app/src/test/kotlin/com/moonkey/androidagent/tool/ToolRouterTest.kt:64-80`, `app/src/test/kotlin/com/moonkey/androidagent/session/AgentSessionTest.kt:37-319`.

## Verification

- `./gradlew assembleDebug test`
- `./gradlew lint`
- `./gradlew :app:testDebugUnitTest --tests 'com.moonkey.androidagent.session.AgentSessionTest'`
- `./gradlew :app:testDebugUnitTest --tests 'com.moonkey.androidagent.history.SessionRecordingServiceTest'`
- `./gradlew :app:testDebugUnitTest --tests 'com.moonkey.androidagent.session.SessionCheckpointConfigSnapshotTest'`
- `./gradlew :app:testDebugUnitTest --tests 'com.moonkey.androidagent.ui.overlay.CapsuleStateHolderTest'`

---

## Round 2 Re-review (`236dfbf3`)

APPROVE

### Re-verification

1. HIGH (`lastTaskOutcome` lifecycle): Addressed.
   `TaskStarted` now clears the cached outcome via `SessionRecordingService.onTaskStarted()`, `handleShutdown()` emits `TaskCompleted(USER_STOPPED)` for active tasks before `SessionCompleted`, and checkpoint save/reload now persists and restores `lastTaskOutcome` through `SessionRuntimeSnapshot`.

2. MEDIUM 1 (checkpoint regression coverage): Partially addressed, non-blocking.
   `SessionCheckpointConfigSnapshotTest` now covers the six runtime-affecting config fields: `actionDelayMs`, `approvalMode`, `debugMode`, `traceEnabled`, `traceRunId`, and `excludedTools`. I still do not see a test that round-trips `lastTaskOutcome` through checkpoint persist+reload, so the commit message overstates the current coverage.

3. MEDIUM 2 (stale approval invariant): Addressed.
   `AgentSessionTest` now verifies an unmatched `Op.Approve` does not call `allowPackagePersistent()` or `allowPackageForSession()`.

### Remaining Findings

- Medium: There is still no automated regression test proving `lastTaskOutcome` survives checkpoint persist+restore. Source inspection shows the implementation path is correct, so this is not blocking approval for real-device QA.

### Verification

- `git show 236dfbf3`
- `./gradlew :app:testDebugUnitTest --tests 'com.moonkey.androidagent.session.AgentSessionTest' --tests 'com.moonkey.androidagent.session.SessionCheckpointConfigSnapshotTest' --tests 'com.moonkey.androidagent.history.SessionRecordingServiceTest'`

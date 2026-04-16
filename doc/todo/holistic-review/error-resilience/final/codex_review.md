# Review: Error-Resilience Slice (`b09bb4a0^..799336d3`)

## Summary
Reviewed the 10 commits in `b09bb4a0^..799336d3` against `doc/todo/holistic-review/error-resilience/final/improvement_plan.md`. Most of the slice lands cleanly: approval fail-fast, delegate failure propagation, context-limit messaging, `TASK_IMPOSSIBLE` mapping, action-outcome semantics, corrupted-history placeholders, and dead-error cleanup all look sound. Targeted unit tests are green, but I found two substantive issues and one meaningful coverage gap.

## Critical
None.

## High
1. `complete_task` is checked against the raw planned tool list instead of the arbitrated execution plan. `TurnToolPolicy` deliberately drops `complete_task` whenever a screen action is present (`app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicy.kt:67-80`, `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicy.kt:91-106`), and `Turn.processResult()` marks any response containing `complete_task` as `isComplete` (`app/src/main/kotlin/com/moonkey/androidagent/agent/Turn.kt:200-213`). The new `decideTurnOutcome()` then errors whenever that raw `complete_task` ID is absent from `execution.executedToolIds` (`app/src/main/kotlin/com/moonkey/androidagent/agent/AgentRuntimeTypes.kt:91-97`). A turn like `[mobile_action, complete_task]` now executes the screen action successfully and still returns `TurnOutcome.Error("complete_task was planned but did not execute")` instead of `Continue`, which violates plan item #1's "selected in the plan" requirement (`doc/todo/holistic-review/error-resilience/final/improvement_plan.md:17-24`). The new test coverage never exercises the dropped-by-arbitration case (`app/src/test/kotlin/com/moonkey/androidagent/agent/TurnOutcomeDecisionTest.kt:11-99`).

## Medium
1. The bootstrap UX change stores pending input and startup error in the view-model, but the UI never consumes that state. `ChatViewModel` writes `_pendingInput` and `_startupError` in `reportStartupFailure()` (`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:135-142`, `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:290-313`), yet `MainActivityContent` and `ChatScreen` only pass callback-based send handlers down (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityContent.kt:57-68`, `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt:144-172`). `SmartCapsuleSurface` still owns `inputText` as internal `remember` state with no parameter for restoring failed input or presenting an explicit retry/error affordance (`app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurface.kt:66-80`, `app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurface.kt:132-157`). This means item #8 is only partially met: the chat gets an error bubble, but the compose box is not repopulated and retry/reload is still implicit rather than explicit (`doc/todo/holistic-review/error-resilience/final/improvement_plan.md:135-139`). The added test only covers the helper that appends chat messages, not the actual UI wiring (`app/src/test/kotlin/com/moonkey/androidagent/ui/chat/ChatStartupFailureTest.kt:12-32`).

## Low
1. The cleanup hardening change is only partially validated. The plan explicitly called for hardening both teardown and post-action observation fallback (`doc/todo/holistic-review/error-resilience/final/improvement_plan.md:90-102`), but the added coverage only exercises `SessionServices.cleanup()` continuation (`app/src/test/kotlin/com/moonkey/androidagent/session/SessionServicesCleanupTest.kt:30-92`). There is still no regression test that forces `captureObservationWithSnapshot()` to throw and verifies the new text-only fallback in `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:217-224`, and there is still no test coverage around the detached `onDestroy()` shutdown path in `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:212-230`. Those are the failure-heavy edges this slice was meant to harden.

## Commit Notes
- `b09bb4a0` (`er-approval-failfast`): looks correct; `ToolRouterTest` covers emitter failure.
- `a7263f9f` (`er-delegate-failure`): looks correct; failed sub-agent runs now propagate structurally.
- `e871694a` (`er-context-length-msg`): looks correct.
- `280ea6dd` (`er-ondestroy-anr`): implementation direction is sound, but it still lacks regression coverage (see Low).
- `b40f14d0` (`er-corrupted-history`): placeholder surfacing and logging look correct.
- `4fe5c375` (`er-completion-correctness`): changes requested; false-error path described above.
- `40f8da5a` (`er-bootstrap-ux`): changes requested; acceptance criteria are only partially met (see Medium).
- `a9ec79b8` (`er-task-impossible`): mapping looks correct.
- `20d81a16` (`er-harden-cleanup`): runtime changes look correct, but observation fallback is still untested (see Low).
- `919faf7d` (`er-action-outcome`): looks correct.
- `799336d3` (`er-dead-error-surface`): looks correct.

## Validation
Ran targeted unit tests and they passed:

```bash
./gradlew testDebugUnitTest \
  --tests 'com.moonkey.androidagent.agent.TurnOutcomeDecisionTest' \
  --tests 'com.moonkey.androidagent.tool.ToolRouterTest' \
  --tests 'com.moonkey.androidagent.tool.impl.DelegateTaskToolTest' \
  --tests 'com.moonkey.androidagent.agent.TurnErrorClassifierTest' \
  --tests 'com.moonkey.androidagent.session.SessionServicesCleanupTest' \
  --tests 'com.moonkey.androidagent.ui.chat.ChatStartupFailureTest' \
  --tests 'com.moonkey.androidagent.history.SessionHistoryManagerTest' \
  --tests 'com.moonkey.androidagent.agent.ActionOutcomeMappingTest'
```

## Recommendation
CHANGES_REQUESTED

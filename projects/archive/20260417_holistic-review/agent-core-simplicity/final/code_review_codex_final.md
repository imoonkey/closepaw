# Review: agent-core-simplicity changes (`55b597f..HEAD`)

## Summary

This range lands most of the structural cleanup cleanly: `AgentRoleDef` removes the old parallel role-definition types, `TurnObservation` centralizes prompt/history screen rendering, `AgentEventDispatcher` consolidates event emission, and the targeted unit suite still passes.

Verification run:

- `./gradlew testDebugUnitTest`

The remaining issues are semantic regressions and incomplete lifecycle hardening, not build failures.

## High

1. **Explicit history resume now fails open into a fresh session, silently dropping the context the UI says is loaded.**

   `MainActivity.onSessionSelect()` puts the app into “continue this history item” mode and restores that session’s messages into the chat UI (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:217-227`). But `createOrReloadSession()` now falls back to `createFreshSession()` even when the reload target was explicitly user-selected and `autoReload == false` (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:512-531`). `ChatViewModel.startEventCollection()` does not clear the restored transcript before attaching to the new session (`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:146-167`), so the user keeps seeing the old conversation while the newly created `AgentSession` starts with empty history/todos/scratchpad. That is a correctness bug, not just a UX choice: the next turn runs without the context the screen implies it has. Keep the fresh-session fallback for dead-session auto-reload only; for explicit history resume, fail closed and tell the user the checkpoint is not reloadable.

2. **“New session” reset is still asynchronous enough for the next message and late events to hit the old session.**

   The new button handler launches `coordinator.clearSession()` asynchronously and immediately clears the conversation (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:229-232`). Until that coroutine finishes, `ChatViewModel.sendMessage()` still sees the old non-shutdown session and submits directly to it (`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:192-199`). `SessionCoordinator.clearSession()` also waits `SHUTDOWN_GRACE_DELAY_MS` before teardown (`app/src/main/kotlin/com/moonkey/androidagent/session/SessionCoordinator.kt:164-176`), which widens the race. Separately, `startNewSession()` does not cancel the old `eventCollectionJob` (`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:150-167`, `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:285-286`), so shutdown/task-complete events from the old session can repopulate the freshly cleared UI. If “new session” is meant to hard-reset context, the detach/cancel has to happen synchronously before the UI accepts another send.

3. **P0 is still not enforced at runtime: the agent continues to execute every screen-changing tool returned in the same turn.**

   `TurnToolPolicy.arbitrateToolCalls()` explicitly keeps all screen-changing calls (`app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicy.kt:35-84`), and `TurnExecutionPhaseRunner.executeActions()` executes them sequentially with fresh post-action captures between tools (`app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:37-68`, `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:111-160`). That directly conflicts with the planner/standalone prompts, which tell the model that navigation actions should be isolated to one per turn (`app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt:26-41`, `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt:29-46`). The tests still encode the multi-screen-action behavior (`app/src/test/kotlin/com/moonkey/androidagent/agent/cognition/policy/TurnToolPolicyTest.kt:89-105`). If the simplicity refactor is meant to remove intra-turn navigation chaining, the runtime has not actually adopted that invariant yet.

## Medium

1. **The action-signature refactor is now orphaned, so the new decoder/signature path has no runtime effect.**

   `TurnRunnerState` no longer carries `previousActionSignature` (`app/src/main/kotlin/com/moonkey/androidagent/agent/AgentRuntimeTypes.kt:21-28`), and `AgentTurnRunner` no longer computes or stores any action signature across turns (`app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:81-109`). But `ActionSignature.kt` and the new `ActionTarget` tests still read like this is live policy-critical logic (`app/src/main/kotlin/com/moonkey/androidagent/agent/ActionSignature.kt:6-20`, `app/src/main/kotlin/com/moonkey/androidagent/agent/ActionTarget.kt:5-10`). After this refactor, those signatures only survive as dead-path helpers and tests. Either wire them into a real runtime policy again, or delete them so the codebase matches the simplified design.

## Recommendation

**CHANGES_REQUESTED**

# Code Review: Unified Agent Mode

## Summary

Reviewed the branch from `157fef68` through the unified-agent-mode changes, including uam-1 through uam-9. The main bridgeEvent design is sound: `ActionProposed` and `ActionExecuted` are forwarded unchanged through the parent emitter, `parentEventEmitter` is plumbed from `SessionAgentRunner.emitEvent`, and non-action `SessionError` still bridges as `SubAgentActivity` (`app/src/main/kotlin/ai/closepaw/agent/subagent/SubAgentRunner.kt:162`, `app/src/main/kotlin/ai/closepaw/session/SessionAgentRunner.kt:155`).

Reload safety still matches the design: `RUNNING_DIRTY` is not reloadable, scheduled running snapshots remain dirty, and only explicit idle/closed flushes write reloadable states (`app/src/main/kotlin/ai/closepaw/history/model/SessionRuntimeSnapshot.kt:84`, `app/src/main/kotlin/ai/closepaw/session/SessionCheckpointCoordinator.kt:37`). AgentMode removal and `executorModel` -> `subagentModel` cleanup are complete in `app/src/main`, `app/src/test`, and `app/src/androidTest`; the requested greps now return no matches.

Coverage by task: uam-1 role collapse verified (`AgentDefRegistry.main` + one delegatable role), uam-2 AgentMode removal verified, uam-3 subagent model rename verified, uam-4 `AgentExecutionRole` verified as exactly `MAIN`/`SUBAGENT`, uam-5 subagent exclusions verified and tightened, uam-6 settings UI verified with "Subagent Model", uam-7 tests/intent cleanup verified, uam-8 docs refreshed, and uam-9 action streaming verified with new regression tests.

## Critical

None.

## High

1. Subagent excluded tools were still accepted by the child agent allowlist. `IsolatedSubAgentRunner` filtered `delegate_task` and `remember_experience` out of the child `ToolRegistry`, but passed the unfiltered `resolvedRoleDef.allowedToolNames` into `AgentExecutionConfig`; a model-emitted call for an excluded tool could therefore pass turn-level allowlist filtering and fail later as an unknown routed tool (`app/src/main/kotlin/ai/closepaw/agent/subagent/SubAgentRunner.kt:78`). Fixed in commit `6f23899f` by deriving `childAllowedToolNames` from the same exclusion set used for the child registry and passing it into the child config (`app/src/main/kotlin/ai/closepaw/agent/subagent/SubAgentRunner.kt:21`, `app/src/main/kotlin/ai/closepaw/agent/subagent/SubAgentRunner.kt:82`, `app/src/main/kotlin/ai/closepaw/agent/subagent/SubAgentRunner.kt:107`). Added regression tests for unchanged action forwarding and pre-routing suppression of excluded subagent tool calls (`app/src/test/kotlin/ai/closepaw/agent/subagent/SubAgentRunnerTest.kt:69`, `app/src/test/kotlin/ai/closepaw/agent/subagent/SubAgentRunnerTest.kt:121`).

## Medium

1. Current docs still described AgentMode, Basic/Pro, planner/executor defs, and `executorModel` in several `doc/main` pages after the implementation removed them. Fixed in commit `34d06a9a` by refreshing the active architecture/settings/protocol docs to the unified Default-role model, including the multi-agent overview (`doc/main/agent/multiagent.md:6`), settings docs (`doc/main/app/settings.md:16`), and protocol config docs (`doc/main/protocol/config.md:8`).

## Low

1. The exact command `./gradlew clean test lint` executed the test/lint tasks but exited non-zero while storing the configuration cache because `:app:licenseDebugReport` invokes `Task.project` at execution time. This appears unrelated to unified-agent-mode code. Rerunning the same verification with configuration cache disabled, `./gradlew --no-configuration-cache clean test lint`, passed in 2m17s.

## Recommendation

APPROVE-with-follow-ups

Follow-up: fix or disable configuration-cache use for `:app:licenseDebugReport` so the exact `./gradlew clean test lint` command exits zero when configuration cache is enabled.

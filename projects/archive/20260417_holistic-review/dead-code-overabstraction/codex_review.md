# Review: dead-code-overabstraction (`2956a165..HEAD`)

## Verdict
APPROVE

## CRITICAL
None.

## HIGH
None.

## MEDIUM
None.

## LOW
1. `delegate_task` no longer rejects an obsolete `agent_name` at runtime, so this phase is not quite "pure deletion" from a behavior-contract perspective. `ToolRouter` only calls `tool.validate()` before `createInvocation()` and does not enforce `parameterSchema.additionalProperties = false` (`app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:93`). After this refactor, `DelegateTaskTool.validate()` only checks `query`/`important_notes` and ignores unknown keys (`app/src/main/kotlin/com/moonkey/androidagent/tool/impl/DelegateTaskTool.kt:60`), while `createInvocation()` always routes to the single resolved executor role (`app/src/main/kotlin/com/moonkey/androidagent/tool/impl/DelegateTaskTool.kt:75`). That means a stale call like `{"agent_name":"planner","query":"tap login"}` now succeeds and delegates to executor instead of failing. Impact is low because the current schema no longer advertises `agent_name`, but if you want this refactor to stay strictly deletion-only, either reject `agent_name` explicitly in `validate()` or add a regression test that locks in the intentional "ignored if present" behavior.

## INFO
- Verified zero code callers for the deleted symbols/files with `rg` across `app/src` and `app/src/test`; only docs/archive references remain.
- Onboarding wiring matches the plan and no partially-wired/null state remains: `MainActivity` constructs `OnboardingDemoController` inline and passes it into `OnboardingViewModel` at creation time (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:150`, `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:156`, `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:28`, `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:262`).
- `delegate_task` still resolves through the registry path rather than a hardcoded role constant: `SessionAgentRunner` passes `AgentDefRegistry.delegatableRoles()` into the tool (`app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt:142`), and the registry still resolves to the single executor role (`app/src/main/kotlin/com/moonkey/androidagent/agent/definition/AgentDefRegistry.kt:16`).
- No missing-import / compile issues showed up in review: `./gradlew :app:compileDebugKotlin` passed.
- Test coverage impact looks clean for the deleted APIs: no dead tests remain under `app/src/test/kotlin`, `DelegateTaskToolTest` was updated for the new contract, and both `./gradlew :app:testDebugUnitTest --tests 'com.moonkey.androidagent.tool.impl.DelegateTaskToolTest'` and full `./gradlew test` passed.

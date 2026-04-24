# Review: agent-security round 2

## High

1. `open_app` can bypass the BLOCKED tier entirely. `PolicyEngine.check()` classifies only the current foreground package, not the app being opened, so `open_app("Chase")` from a NORMAL app is allowed even though the destination is BLOCKED (`app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt:33-46`, `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt:188-220`). This also immediately captures the blocked screen after launch.

2. Escape handling is broken for the actual back/home tool. The policy special-case only looks at `action` or `toolName`, but real back/home navigation now goes through `system_button(button="back"|"home")`, while `mobile_action` no longer accepts `back`/`home` at all (`app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt:41-42`, `app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt:78-81`, `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt:55-60`). Result: the agent can be trapped on a BLOCKED app. The corresponding tests are stale because they assert impossible `mobile_action(action="back"|"home")` cases (`app/src/test/kotlin/com/moonkey/androidagent/tool/PolicyEngineTest.kt:38-54`).

3. The perception gate is only applied to the pre-turn snapshot. Raw blocked content still flows through post-action and approval-refresh captures, then gets emitted to events/traces as normal screen state (`app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:147-163`, `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:220-224`, `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt:207-216`, `app/src/main/kotlin/com/moonkey/androidagent/tool/handlers/UIActionInvocation.kt:74-80`, `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:108-146`, `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:215-236`). So `BLOCKED -> masked` is not correct end-to-end.

4. The memory gate only checks the current foreground app, not the memory target. From a NORMAL screen, the agent can still write `scope=app` memory for a BLOCKED package such as `com.chase.sig.android` (`app/src/main/kotlin/com/moonkey/androidagent/tool/impl/RememberExperienceTool.kt:144-159`). If blocked apps are supposed to be non-observable/non-learnable, this bypasses that guarantee.

## Test gaps

1. No test covers `open_app` into a BLOCKED destination, which is the main policy bypass.

2. No test covers the actual escape path `system_button(button="back"|"home")` on a BLOCKED app; the current tests cover an invalid `mobile_action` shape instead (`app/src/test/kotlin/com/moonkey/androidagent/tool/PolicyEngineTest.kt:38-54`).

3. No test covers masking on post-action captures / approval refresh / app launch into a BLOCKED app.

4. `RememberExperienceToolTest` does not cover blocked foreground rejection or blocked `package_name` writes (`app/src/test/kotlin/com/moonkey/androidagent/tool/impl/RememberExperienceToolTest.kt:81-110`).

## Recommendation

CHANGES_REQUESTED

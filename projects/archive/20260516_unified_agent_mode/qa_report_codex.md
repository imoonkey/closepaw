# QA Report: Unified Agent Mode

## Result

**PASS-with-follow-ups**

## Context

Post-rebase verification for `task/unified-agent-mode` after rebasing onto current `main`.

## Flows Verified

1. **Build** — ✓
   - Ran `./gradlew --no-configuration-cache clean assembleDebug lint test`.
   - Captured full log at `projects/archive/20260516_unified_agent_mode/qa_artifacts_codex/gradle_clean_assembleDebug_lint_test.log`.
   - Result: `BUILD SUCCESSFUL in 7s`.

2. **Settings page renders cleanly** — ✓
   - Installed fresh debug APK on `100.64.43.95:5555`.
   - Settings home renders without crash; no `Mode`, `Pro`, or `Basic` dropdown appears in the uiautomator dump.
   - LLM auth subpage renders after scrolling and shows `Subagent Model`.
   - Evidence: `settings_home.png`, `settings_home_uiautomator.xml`, `settings_llm.png`, `settings_llm_uiautomator.xml`.

3. **Main agent simple task, no delegation** — ✓
   - Ran `./scripts/debug-run.sh "Open Settings"` with `DEBUG_AUTO_APPROVE=true`.
   - Task completed in 2 turns with `open_app` followed by parent `complete_task`.
   - Chat shows final answer `Opened Settings.` and the log shows `Task completed ... outcome: GOAL_ACHIEVED`.
   - Evidence: `debug_run_simple_open_settings.log`, `debug_run_simple_agent.log`, `simple_after_run_chat.png`, `simple_after_run_chat_uiautomator.xml`.

4. **Delegation triggers and subagent actions visible (Design C)** — ✓
   - Ran `./scripts/debug-run.sh "Use delegate_task exactly once: ask a subagent to open Calculator or 计算器, compute 7*8, and return the displayed result."` with `DEBUG_AUTO_APPROVE=true`.
   - Parent called `delegate_task`; subagent attempted `open_app("Calculator")`, recovered with `open_app("计算器")`, performed `mobile_action` steps, and completed with `56`.
   - Parent received the subagent result and completed with `56`.
   - Expanded chat action ledger shows the parent `delegate_task` plus subagent `open_app`, `mobile_action`, `wait`, and `complete_task` cards in the same row.
   - Capsule stayed active during delegation; 10-frame sequence captured with `adb exec-out screencap -p`.
   - Evidence: `debug_run_delegation.log`, `debug_run_delegation_agent.log`, `delegation_sequence/delegation_0.png` through `delegation_9.png`, `delegation_actions_expanded.png`, `delegation_actions_expanded_uiautomator.xml`.

5. **Reload preserves subagent action cards** — ✓
   - Pulled persisted session JSON:
     `adb shell run-as ai.closepaw cat files/sessions/session-2026-05-16T01-50-13-ccf5ab04-67cf-4d2c-aef4-a2666fd1d9a2.json > projects/archive/20260516_unified_agent_mode/qa_artifacts_codex/persisted_session_with_subagent_actions.json`
   - Inspected with:
     `jq '.messages[1].contentBlocks | map({type, toolName, state, title, resultSummary})' ...`
   - The agent message contains parent `delegate_task`, subagent `open_app` failure, subagent `open_app` success, `mobile_action` x3, `wait`, subagent `complete_task`, parent `complete_task`, and final text.
   - Force-stopped/relaunched the app, reopened the recent session from the drawer, and expanded the action row; subagent action cards still render.
   - Evidence: `persisted_session_with_subagent_actions.json`, `reload_delegation_session.png`, `reload_delegation_actions_expanded.png`, `reload_delegation_actions_expanded_uiautomator.xml`.

6. **Subagent ask_user displays** — ✓ code-traced
   - `SessionAgentRunner.ensureAskUserToolRegistered` registers `AskUserTool` with the parent `eventDispatcher` (`app/src/main/kotlin/ai/closepaw/session/SessionAgentRunner.kt:168`).
   - `SubAgentRunner` creates the child registry via `toolRegistry.createFilteredCopy(...)`; `ask_user` is not excluded, so the child keeps the same `AskUserTool` instance (`app/src/main/kotlin/ai/closepaw/agent/subagent/SubAgentRunner.kt:78`).
   - `AskUserTool` emits through that dispatcher, then `AgentServiceEventHandler` routes `AskUser` to `ServiceOverlayController.onAskUser` (`AgentServiceEventHandler.kt:174`, `ServiceOverlayController.kt:389`).
   - `UserResponseChannel` enforces a single pending request, preventing parent/subagent ask_user collisions.

## Commands Run

- `./gradlew --no-configuration-cache clean assembleDebug lint test`
- `adb connect 100.64.43.95:5555`
- `adb -s 100.64.43.95:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
- `adb -s 100.64.43.95:5555 shell settings put secure enabled_accessibility_services ai.closepaw/ai.closepaw.app.AgentService`
- `adb -s 100.64.43.95:5555 shell settings put secure accessibility_enabled 1`
- `./scripts/debug-run.sh "Open Settings"`
- `./scripts/debug-run.sh "Use delegate_task exactly once: ask a subagent to open Calculator or 计算器, compute 7*8, and return the displayed result."`
- `adb -s 100.64.43.95:5555 exec-out screencap -p > <artifact>.png`
- `adb -s 100.64.43.95:5555 shell uiautomator dump /sdcard/window.xml && adb -s 100.64.43.95:5555 pull /sdcard/window.xml <artifact>.xml`
- `adb -s 100.64.43.95:5555 shell run-as ai.closepaw cat files/sessions/session-2026-05-16T01-50-13-ccf5ab04-67cf-4d2c-aef4-a2666fd1d9a2.json > projects/archive/20260516_unified_agent_mode/qa_artifacts_codex/persisted_session_with_subagent_actions.json`
- `jq '.messages[1].contentBlocks | map({type, toolName, state, title, resultSummary})' projects/archive/20260516_unified_agent_mode/qa_artifacts_codex/persisted_session_with_subagent_actions.json`
- `/home/qiguo/ld-workspace/agent-config/global/skills/update-tasks/scripts/update-tasks.py set fix-active-turn-survives-rotation <json>`

## Notes

- No code fixes were made.
- The first delegation attempt was interrupted by a device orientation change/relaunch while the OpenAI stream was in flight. The LLM had returned `delegate_task`, but the stream was canceled before `ToolCallReceived`, so delegation did not start. I locked device rotation and reran successfully.
- Follow-up filed: `fix-active-turn-survives-rotation`.
- `qa_artifacts_codex/` contains fresh post-rebase artifacts. The pre-rebase artifacts remain in `qa_artifacts_codex_pre_rebase/` and were not used as evidence for conclusions.

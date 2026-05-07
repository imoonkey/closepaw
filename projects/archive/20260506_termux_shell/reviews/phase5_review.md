# Phase 5 Review — Prompt Updates + Conditional Registration + Runner Wiring + PRO Mode

## Verdict
REQUEST_CHANGES

## Findings

1. severity: medium, file: app/src/main/kotlin/ai/closepaw/agent/definition/WorkspaceShellPromptSection.kt:5, description: The injected Workspace Shell section does not match the Phase 5 design prompt closely enough for a prompt-contract change. It keeps the heading, shell split, workspace path, UI-vs-shell hint, and Planner directive, but drops several design requirements: canonical input/output guidance, the `/sdcard/Download/` handoff for sharing with other apps, the fuller toolchain wording, and the explicit "Working directory: ~/closepaw/workspace/" framing. It also tells the model to use `~/.closepaw/artifacts/` to share large files between commands, while the design says input/output files belong in `~/closepaw/workspace/` and artifacts are bridge spill refs. This can steer mixed UI+workspace tasks to put user-facing files in the wrong place. suggested fix: Replace `WORKSPACE_SHELL_PROMPT_SECTION` with the design's System Prompt Extension content, keeping `PLANNER_WORKSPACE_SHELL_DIRECTIVE` appended only through `workspaceShellPromptSectionFor(AgentExecutionRole.PLANNER)`.

2. severity: medium, file: app/src/test/kotlin/ai/closepaw/agent/definition/AgentRoleResolveTest.kt:47, description: The new resolve tests cover the positive Planner directive case, but not the negative invariants the checklist calls out. A regression that appends the Planner directive when Termux is unavailable/excluded, or appends it to Standalone/Executor when Termux is available, would not be caught by the directive-specific assertions. The timeout tests also cover unavailable but not the excluded-Termux path. suggested fix: Add assertions that Planner without exposed `termux_shell` does not contain the directive, Standalone/Executor with exposed `termux_shell` do not contain the directive, and Executor with `excludedTools = setOf(ToolName.TermuxShell)` keeps `ExecutorRoleDef.timeoutMs`.

3. severity: low, file: app/src/main/kotlin/ai/closepaw/tool/impl/ShellTool.kt:21, description: The `shell` tool description mentions Android toybox and points to `termux_shell`, but it is shorter than the design wording and omits command substitution (`$()`) and the broader allowed-command examples. Since `ShellTool.validate()` rejects `$`, this missing prompt detail can leave the model less informed about why some shell-shaped commands fail. suggested fix: Use the design's Shell Tool Description Update wording, or at least add "no command substitution ($())" and a few more command examples.

## Notes

- Focused verification passed: `./gradlew :app:testDebugUnitTest --tests ai.closepaw.agent.definition.AgentRoleResolveTest`.
- `AgentRoleDef.resolve()` correctly derives tools from base minus excluded plus `TermuxShell` only when `snapshot.available`, injects the workspace prompt only when `TermuxShell` is in the resolved tool list, applies the Planner directive only through the Planner role, and bumps Executor timeout to `150_000` only when `TermuxShell` is exposed.
- `SessionServices.create()` captures `TermuxCapabilitySnapshot` once per session from `TermuxBridgeManager.get(context).snapshot(...)`; `SessionRuntimeSnapshot` does not serialize it; `AgentSession.reload()` calls `SessionServices.create()` again, so reload captures a fresh snapshot.
- `SessionToolingBootstrapper` resolves the main and delegatable roles from the same snapshot, registers `TermuxShellTool` only when the resolved union contains `termux_shell`, and the top-level/sub-agent runners pass `ResolvedAgentRole.allowedToolNames` into the agent loop.
- `registerTermuxShellTool()` obtains the process-wide `TermuxBridgeManager` through `TermuxBridgeManager.get(context)`, whose holder normalizes to `applicationContext`. The current `TermuxShellTool` constructor is still HTTP-client based, so the manager is not otherwise passed into the tool.
- File sizes are within the 400-line limit: `SessionServices.kt` is 350 lines, `SubAgentRunner.kt` is 235, `SessionAgentRunner.kt` is 218, and the remaining files in scope are smaller.

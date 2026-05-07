# Engineering Plan Review: Termux Shell Tool v2

Reviewer: Codex  
Design reviewed: `projects/active/coding/final/cn/design.md`  
Date: 2026-05-01

Note: This review treats the CN design as authoritative. Security/policy hardening is intentionally deferred, so findings below do not flag auto-allow, command blocklists, risk classification, or workspace-boundary enforcement as blockers.

---

## Plan Summary

The plan adds `termux_shell` as a full Linux bash workspace tool backed by a Python daemon inside Termux. ClosePaw keeps existing Android UI tools and the existing restricted `shell` tool. v1 success means one session can combine UI automation with Termux workspace commands, and Standalone, Planner, and Executor all see `termux_shell` when Termux is ready and enabled.

The updated design is directionally implementable. The remaining risk is not the bridge concept; it is the runtime wiring around state, role allow-lists, bootstrap idempotency, command lifetime, and testable phase ordering.

## What Already Exists

- `ToolSpec`, `ToolInvocation`, `ToolRegistry`, and `ToolRouter` are already the right abstraction for a new tool. `ShellTool.kt` is the closest implementation shape for validation, timeout, and text observations.
- `SessionToolingBootstrapper` registers built-in tools once during `SessionServices.create()`. This is the obvious hook for `TermuxShellTool`, but it currently has no bridge state, token store, or runtime settings input.
- `TurnPlanningPhaseRunner` already appends dynamic system-prompt sections for Agent Skills. The Termux prompt section should use the same per-turn append point.
- Role filtering is static today: main agents use `AgentRoleDef.allowedTools`, and isolated sub-agents create filtered registry copies from the same static role definitions.
- `ToolName.isScreenChanging` drives both turn arbitration and approval routing. Adding `TermuxShell` here is necessary, but turn-order behavior needs extra thought because `termux_shell` is long-running and side-effectful even though it is not screen-changing.
- `AppSettingsStore` is plain SharedPreferences for non-secret settings. `AuthStore` shows the existing `EncryptedSharedPreferences` pattern for token-like values.
- Current `network_security_config.xml` globally disables cleartext, and the manifest package visibility only covers launchable apps. The design's localhost exception and Termux queries are real implementation requirements, not optional cleanup.

## Findings

### F1: `TermuxBridgeState` Needs a Concrete Owner and Update Semantics

Severity: high

The design correctly names `TermuxBridgeState` as the single source of truth, but the implementation plan does not yet say who owns it or whether readiness changes update an already-running session.

Current behavior is mostly snapshot-based: tools are registered in `SessionToolingBootstrapper.create()`, allowed tools are copied into `AgentExecutionConfig`, and sub-agents create filtered registry copies at delegation time. Prompt construction happens every turn, but tool registration and role filtering do not automatically follow a later health-check transition.

Before coding, decide one of these semantics:

- Termux availability is fixed at session/task start. Setup changes apply to the next task/session only.
- Termux availability is live. A manager updates registry exposure and prompt injection during a running task.

The simpler v1 is fixed-at-task-start. If that is chosen, document it and make Settings say setup takes effect on the next task. If live update is desired, add a small runtime capability resolver used by `SessionToolingBootstrapper`, `SessionAgentRunner`, `TurnPlanningPhaseRunner`, and `IsolatedSubAgentRunner`.

### F2: All-Role Exposure Conflicts With Executor Timeout

Severity: high

The design says Standalone, Planner, and Executor all get `termux_shell`. Today `ExecutorRoleDef` has `timeoutMs = 30_000`, while `termux_shell` defaults to 120 seconds. A delegated executor shell command can be killed by the role timeout long before the tool timeout, producing confusing failures.

Resolve this before implementation:

- Increase executor timeout when `termux_shell` is enabled.
- Add per-role `termux_shell` timeout caps so Executor commands fit inside its role timeout.
- Keep the tool visible to Executor but prompt Planner to run long workspace commands itself.

The first option is simplest if "all three roles get the tool" is a hard requirement.

### F3: Turn Arbitration Will Reorder `termux_shell` Ahead of UI Actions

Severity: high

`TurnToolPolicy` currently hoists all non-screen-changing tools before all screen-changing tools. That is safe for `scratchpad` and `write_todos`, but it is surprising for a real shell. If the model returns `mobile_action` followed by `termux_shell`, current arbitration will execute `termux_shell` first.

Do not treat every non-screen-changing tool as reorderable. Minimal fix: define a small set of hoistable cognitive tools (`scratchpad`, `write_todos`, maybe `remember_experience`) and preserve ordering or require solo execution for `shell`, `termux_shell`, `ask_user`, and `activate_skill`. Add a test where `mobile_action` and `termux_shell` appear in both orders.

### F4: Bootstrap Progress Must Be Derived, Not Just Remembered

Severity: high

The design says Settings remembers completed setup steps. That can become stale if Termux app data is cleared, packages are removed, the bridge script is outdated, the token changed, or the fixed port is held by an old daemon.

Use persisted setup progress only as UI resume metadata. Readiness should be derived from actual probes:

- PackageManager sees Termux.
- RUN_COMMAND probe returns expected output.
- Required package checks pass.
- Deployed bridge version matches the APK resource version.
- Health succeeds with the currently selected token.

Each step should be idempotent and safe to retry from real state, not local "step completed" flags.

### F5: Token Rotation Needs a Two-Phase Commit

Severity: high

Re-setup rotates the token, but the plan needs exact promotion semantics. If ClosePaw overwrites the stored token before the new bridge passes health, a failed start can break a previously working bridge. If it health-checks with the old token after deploying a new token, it will report a false token mismatch.

Use a candidate-token flow:

1. Deploy bridge and parse exactly one `CLOSEPAW_TOKEN=<hex>` line.
2. Start or restart bridge using that candidate.
3. Poll `/v1/health` with the candidate.
4. Promote candidate to encrypted storage only after health returns 200.
5. Keep the previous token until promotion succeeds.

Settings can then distinguish "old bridge still works", "new setup pending", "token mismatch", and "health timeout".

### F6: Fixed-Port Idempotent Startup Needs Ownership Rules

Severity: medium-high

A fixed port is fine for v1, but "kill stale -> rebind" is under-specified. The start script should not blindly kill whatever owns port `18422`; it needs a way to identify an old ClosePaw bridge.

Minimum v1 rules:

- Bridge writes a pidfile with port, version, and script identity.
- Startup first asks `/v1/health` with known/candidate token where possible.
- If health returns ClosePaw bridge with wrong version, restart it.
- If the port is occupied by an unknown process, fail with a typed `port_in_use` setup reason instead of a blind kill.

This avoids a coding surprise where idempotent startup is either unsafe or not actually idempotent.

### F7: Bridge Packaging and Versioning Need One Source of Truth

Severity: medium

The design allows `tools/termux-bridge/` or `app/src/main/res/raw/`, but implementation should pick one canonical source. If the Python file lives only under `tools/`, it will not be deployable from the APK unless a build step packages it. If it lives only under `res/raw`, host-side tests can still read it, but the version constant must be easy to compare with `/v1/health`.

Before implementation, specify:

- The exact resource path for `closepaw_bridge.py`.
- The bridge protocol version constant.
- How `TermuxBridge` decides the deployed script is stale.
- A test that fails if the raw resource is missing or the expected version line cannot be found.

### F8: Tool Result Semantics Need to Match Shell Reality

Severity: medium-high

`POST /v1/exec` returns HTTP 200 with `exit_code`, even when the command exits non-zero. `TermuxShellTool` should map that to a successful tool execution containing `exit_code`, `stdout`, `stderr`, `duration_ms`, `truncated`, and `output_ref`. Non-zero process exit is command information for the model, not a router-level tool failure.

Reserve `ToolExecutionResult.Failure` for transport/protocol failures: not ready, unauthorized, invalid request, health timeout, malformed JSON, IO failure, or bridge internal errors. This keeps later tool calls in the same turn from being aborted just because `grep` returned 1 or a build failed with useful stderr.

### F9: Timeout-Only Cancellation Still Needs OkHttp Cancellation Wiring

Severity: medium-high

The design intentionally skips a `/v1/cancel` endpoint. That is acceptable, but the stated client-disconnect behavior only works if the Kotlin client actually closes the HTTP call when the agent is interrupted or the coroutine is cancelled.

Current cancellation tokens are checked before execution, while long-running work depends on coroutine cancellation and tool implementation details. `TermuxShellTool` should use cancellable OkHttp execution and call `Call.cancel()` on coroutine cancellation. Tests should verify that interrupt/shutdown does not wait for the full 120-second command timeout on the Kotlin side.

### F10: Settings Setup Needs a Lifecycle-Stable Runner

Severity: medium

`AgentBehaviorSettingsPage` is currently a stateless Compose page. The Termux setup flow is async, multi-step, and uses RUN_COMMAND result callbacks. It should not be owned by `remember` state inside the page.

Add a lifecycle-stable `TermuxBridgeManager` or setup controller that:

- Exposes `StateFlow<TermuxBridgeStatus>` for Settings and sessions.
- Serializes setup, health polling, and restart attempts behind a mutex.
- Survives configuration changes.
- Converts PendingIntent/RUN_COMMAND results into typed step outcomes.
- Avoids concurrent setup from Settings while a session health monitor is restarting the bridge.

### F11: Manifest and Cleartext Changes Should Land Before Bridge-Client Debugging

Severity: medium

The design already calls these out, but phase ordering should make them early acceptance criteria. Without package visibility entries, detection can report "Not Installed" even when Termux exists. Without the localhost cleartext exception, OkHttp can fail before the bridge client code is meaningfully testable.

Add manifest and network config changes in the first implementation phase, with tests or instrumentation checks for:

- Querying `com.termux`.
- Resolving the RUN_COMMAND service intent.
- Reaching `http://127.0.0.1:<port>` under the app network security config.

### F12: Concurrent Exec Policy Should Be Explicit

Severity: medium

`ThreadingHTTPServer` allows concurrent requests. ClosePaw usually executes selected tool calls sequentially, but sub-agents, setup/restart logic, health checks, and future session behavior can overlap. Concurrent shell commands in one workspace can race on files, artifacts, package installs, and bridge lifecycle.

For v1, prefer one active `/v1/exec` at a time per bridge. Health checks can remain concurrent. If concurrency is allowed, document it and make artifact filenames collision-safe with call ids.

## Test Plan

- Bridge daemon host tests: health 200, wrong token 401, malformed request, command stdout/stderr/exit code, timeout process-group kill, output cap with `output_ref`, fixed-port stale/unknown owner behavior, idle watchdog.
- Kotlin bridge client tests with MockWebServer: 200 health, 401 token mismatch, connection refused, malformed JSON, HTTP timeout, server timeout response, non-zero exit code preserved as tool success.
- `TermuxShellTool` tests: schema validation, default `cwd`, optional `cwd`, timeout bounds, result formatting, `ToolObservation.TextOutput`, non-zero exit code does not become `ToolCallResult.Error`.
- Capability wiring tests: `termux_shell` absent when disabled/not ready, present when ready/enabled, prompt section and tool registration derive from the same state, all three roles include the tool, sub-agent filtered registry includes it.
- Turn policy tests: `termux_shell` is not reordered ahead of UI tools, and `complete_task` behavior still works when paired with shell-only commands.
- Bootstrap state-machine tests: probe failure, permission missing, package install failure, deploy token missing/duplicate/malformed, bridge outdated, token mismatch, health timeout, retry from each failed step, token promotion only after health success.
- Settings UI tests: Not Installed, Needs Setup, Ready, Disabled, setup in progress, failure reason shown compactly, rotation/configuration change does not lose setup state.
- Session lifecycle tests: fresh session, hot-idle follow-up, checkpoint reload, interrupt/shutdown during long `termux_shell`, bridge gone mid-session, bridge becomes ready after setup according to chosen update semantics.
- Device QA: pure UI task still works, pure workspace task works in BASIC and PRO, mixed UI+workspace task works, Planner and Executor both expose the tool, localhost cleartext works on target API/device.

## Not In Scope

- Security hardening for `termux_shell`: auto-allow, command blocklists, risk classification, and workspace-boundary enforcement are deferred to `projects/active/shell_security/problem.md`.
- Independent file/git/search tools.
- Remote backend, Shizuku/root shell, native backend expansion, and process-management UX.
- Active `/v1/cancel` endpoint.
- Health `capabilities` field.
- Any design conflict with the English aligned design; the CN design is the source of truth for this review.

## Next Step

Update the implementation plan before `/implement` with:

1. A concrete `TermuxBridgeManager` / `TermuxBridgeState` owner and fixed-at-task-start vs live-update semantics.
2. Revised phase ordering: manifest/network/resource substrate, bridge manager + bootstrap state machine, daemon/client contract, tool behind state, role/prompt wiring, settings UI, QA.
3. Explicit decisions for Executor timeout, turn arbitration ordering, token promotion, fixed-port ownership, and one-at-a-time exec.

After those are written down, the plan is ready to implement in small vertical slices.

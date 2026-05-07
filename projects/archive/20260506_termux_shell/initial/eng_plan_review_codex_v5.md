# Engineering Plan Review: Termux Shell Tool v5

Reviewer: Codex
Design reviewed: `projects/active/coding/final/cn/design.md`
Date: 2026-05-02

Security and policy hardening are intentionally out of scope for this review. I did not flag token auth, command blocklists, risk classification, workspace boundary enforcement, auto-allow, or secrets redaction.

## Plan Summary

The plan adds a `termux_shell` tool backed by a Python HTTP bridge daemon running inside Termux. ClosePaw keeps the existing Android UI automation tools and restricted Android `shell` tool, while exposing a full bash workspace when Termux is installed, setup has completed, the bridge is healthy, and the user toggle is enabled.

Success means a task can combine UI tools and workspace shell commands, and Standalone, Planner, and delegated Executor all see a consistent fixed-at-task-start capability: matching tool schemas, prompts, role allow-lists, sub-agent timeout, and setup/status UI.

## What Already Exists

- `SessionServices.create()` builds the session-scoped tool registry, router, state, history, model clients, memory, browser tool, and settings access. It is currently called from `AgentSession.create()` and `AgentSession.reload()`, not from each task start.
- `AgentSession` supports Hot Idle follow-up tasks. `startTask()` reuses the same `SessionServices` and the same `SessionAgentRunner`; an idle follow-up only reacquires the platform.
- `SessionAgentRunner.start()` is already the per-task entry point for resolving the main role, registering delegation, building `AgentExecutionConfig`, and applying `config.excludedTools`.
- `IsolatedSubAgentRunner` builds a filtered child registry from the parent registry and the delegated role allow-list, then runs with `roleDef.timeoutMs`.
- `TurnToolPolicy` currently groups every non-screen-changing tool before screen-changing tools and handles `complete_task` deferral through `hasScreenAction`.
- Existing Planner, Executor, and `delegate_task` text are UI-centric. Planner tells the model to delegate grounded UI execution; `DelegateTaskTool` says "Delegate ONE atomic UI action"; Executor says it executes one atomic UI action.
- `AppSettingsStore` is the right home for a non-secret Termux enabled toggle.
- `network_security_config.xml` already exists with cleartext disabled by default, and `app/build.gradle.kts` already has OkHttp and MockWebServer dependencies.

## Findings

### F1: The Snapshot Is Still Planned at Session-Service Creation, Not at Each Task Boundary

Severity: high

The design says setup changes affect the next task and that reload is a new task boundary with a fresh snapshot. However, the concrete path still says `SessionServices.create()` captures `termuxSnapshot`, and this codebase creates `SessionServices` once per hot session. `AgentSession.create()` and `AgentSession.reload()` call `SessionServices.create()`, while Hot Idle follow-up tasks call `startTask()` with the existing services.

That means a user can finish Termux setup or toggle Termux Shell while the session is idle, then start a follow-up task in the same session and still get the old registry/prompt/role snapshot. This is especially likely because the Settings UI says setup changes apply to the next task.

Before implementation, choose one explicit lifecycle:

- Capture `TermuxCapabilitySnapshot` inside `AgentSession.startTask()` / `SessionAgentRunner.start()` and make per-task tool registration/prompt/role resolution read that snapshot; or
- Recreate the relevant per-task services at each task boundary while preserving history, todos, scratchpad, memory, recording, and platform; or
- Change the product contract to "next session only" and update UI/docs/tests.

The current v5 wording mixes "Session start" and "task start"; implementation needs one canonical boundary.

### F2: Effective Role Resolution Must Include `excludedTools`

Severity: medium-high

The plan defines `AgentRoleDef.resolve(snapshot)` as adding `termux_shell` and appending the workspace prompt whenever Termux is available. Existing runtime behavior also applies `config.excludedTools` in `SessionAgentRunner.start()`. If those remain separate, `termux_shell` can be removed from the actual tool schema while the system prompt still tells the model it exists.

This affects top-level agents and sub-agents. `SessionAgentRunner` currently subtracts exclusions from the static role allow-list, and `IsolatedSubAgentRunner` filters child tools from `roleDef.allowedTools`. The resolved runtime role should be based on `staticRole + snapshot + excludedTools`, with prompt injection gated on the effective tool being exposed.

Define something like:

```kotlin
fun AgentRoleDef.resolve(
    snapshot: TermuxCapabilitySnapshot,
    excludedTools: Set<String>
): ResolvedAgentRole
```

Then use the resolved role for prompt, schema allow-list, child registry filtering, and Executor timeout. Add a test where Termux is ready but `config.excludedTools` contains `termux_shell`: the registry may contain the tool, but the LLM schema and workspace prompt must both be absent.

### F3: Planner/Executor Delegation Text Is Still UI-Only

Severity: medium-high

The design says all three roles get `termux_shell`, including Planner and Executor. The existing prompts/tool descriptions still frame Planner and Executor as UI-only:

- Planner says it does not perform low-level UI actions directly and should call `delegate_task` or `open_app`.
- `delegate_task` says it delegates one atomic UI action.
- Executor says it executes one atomic UI action per delegation and its examples are all UI interactions.

Appending a workspace prompt section is not enough to resolve this contradiction. In PRO mode, pure workspace tasks may fail because the Planner's loop does not say when it should run `termux_shell` directly versus delegate. If delegated, the Executor may ignore `termux_shell` because its role contract says UI action.

Decide the PRO contract before coding:

- Planner runs workspace commands directly, and Executor remains UI-only; or
- Executor can perform one atomic UI or workspace action, and `delegate_task` plus Executor prompt get updated examples and completion rules for workspace commands.

The implementation should include a PRO-mode test that proves the chosen contract: a pure workspace task, and a mixed UI-to-workspace task if Executor is intended to receive shell delegations.

### F4: Turn Reordering Needs a Full Arbitration Contract, Not Just a `reorder()` Helper

Severity: medium

The design correctly says `shell`, `termux_shell`, and `ask_user` must preserve LLM order. The pseudo-code only shows:

```kotlin
val hoistable = toolCalls.filter { it.name in HOISTABLE_TOOLS }
val rest = toolCalls.filter { it.name !in HOISTABLE_TOOLS }
return hoistable + rest
```

But current `TurnToolPolicy` also computes `hasScreenAction`, drops `complete_task` when a screen-changing call is selected, and returns `droppedToolCalls`. Replacing it with the pseudo-code risks regressing completion deferral or trace/drop semantics.

Specify the exact arbitration algorithm:

- Hoist only the named hoistable tools, preserving their relative order.
- Preserve the original relative order of all non-hoistable tools, including `shell`, `termux_shell`, `ask_user`, `activate_skill`, `mobile_action`, `open_app`, and `delegate_task`.
- Keep the existing `complete_task` deferral semantics, or explicitly replace them.
- Keep `hasScreenAction` and `droppedToolCalls` meaningful for `decideCompletion()` and trace output.

Also reconcile the design text: one place says hoistable tools are `scratchpad` and `write_todos`; the pseudo-code includes `remember_experience`.

### F5: Bridge Protocol Still Contains Conflicting Response Contracts

Severity: medium

Most v4 protocol issues are fixed, but v5 still has a few conflicting schemas:

- The system invariant says later `/v1/exec` requests are queued, while the API section says an existing command immediately returns HTTP 409 `busy` and does not queue.
- The main exec response uses `stdout_truncated`, `stderr_truncated`, `stdout_ref`, and `stderr_ref`, but the timeout response still uses singular `truncated` and `output_ref`.
- The bridge exec pseudo-code returns `{"error": "timeout", ...}` for an HTTP 200 timeout, while the result semantics say timeout is `timed_out=true` and maps to `ToolExecutionResult.Success`.

Define one canonical `ExecResponse` schema and one canonical `ExecError` schema. Timeout should use the same stdout/stderr fields as normal completion, with `exit_code=null` and `timed_out=true`. Busy should be either immediate 409 or queueing, not both.

### F6: Bootstrap Startup Errors Need an Observable Contract

Severity: medium

The Settings UI promises typed reasons such as `port_in_use`, `bridge outdated`, and `health timeout`. The startup step is currently `RUN_COMMAND: python3 ~/.closepaw/bridge.py &` followed by polling `/v1/health`.

If the daemon fails to bind, exits immediately, or sees an unknown port owner, the background shell command can still return before ClosePaw observes the real failure. Without an explicit startup status channel, several distinct cases collapse into "health timeout."

Before coding, define how `TermuxBridgeManager.runSetup()` observes startup failures:

- Start the daemon in a mode that writes a one-shot startup JSON/status file before backgrounding; or
- Keep the start command in the foreground until bind succeeds or fails, then daemonize; or
- Write structured startup errors to a known log file that the manager reads after health polling fails.

Also decide how the bridge idle watchdog interacts with 30s health polling. If every `/v1/health` request resets the idle timer, the 30-minute watchdog never fires. If health checks do not count as activity, the bridge must track exec activity separately.

### F7: Client-Disconnect Cancellation Is Still Aspirational

Severity: medium

The plan depends on `Call.cancel()` causing TCP disconnect, then the Python bridge detecting that disconnect and killing the subprocess promptly. The bridge pseudo-code says `wfile.write probe or select on socket`.

That needs a concrete implementation contract. A write probe before the final JSON response can corrupt the HTTP response, and `BaseHTTPRequestHandler` does not give a simple cancellation callback while a long command is running. If this is not made precise, "stop session during long command" can wait for the full 120s bridge timeout.

Define the server-side mechanism and test it with a long-running command:

- If using socket polling, specify exactly how it detects peer close without writing partial response bytes.
- If reliable detection is too much for v1, downgrade the guarantee to "timeout-based cancellation" and add `/v1/cancel` earlier.

### F8: Stdout/Stderr Encoding Is Not Defined

Severity: low-medium

The bridge caps output by bytes and drains `stdout`/`stderr` as bytes, but JSON response fields are strings. Commands can emit invalid UTF-8 or binary data. Without a defined encoding rule, the bridge can crash during JSON serialization or produce inconsistent truncation behavior.

Define the v1 text contract: for example, decode in-memory snippets as UTF-8 with replacement, keep artifact files as raw bytes, and include byte counts/truncation fields. Add a host test for invalid UTF-8 output.

### F9: Bridge Resource Copy Mechanics Need a Concrete Gradle Shape

Severity: low-medium

The design says `tools/termux-bridge/closepaw_bridge.py` is canonical and Gradle copies it to `app/src/main/res/raw/closepaw_bridge_py`. If the copy task writes into `src/main/res/raw` during builds, Gradle can dirty the source tree and clean builds may depend on generated files appearing in a source directory at the right time.

Before implementation, choose one:

- Commit `app/src/main/res/raw/closepaw_bridge_py` and add a CI check that fails when it differs from `tools/termux-bridge/closepaw_bridge.py`; or
- Generate into a build directory such as `build/generated/res/termuxBridge/raw/` and wire that directory into Android source sets before resource merging.

Either is fine, but the build should have one deterministic source-to-packaged-resource path.

## Test Plan

- Hot Idle snapshot test: create a session with Termux unavailable, complete a task, change fake manager state to ready+enabled, start a follow-up task in the same session, and assert schema/prompt/role resolution now match the new task snapshot.
- Snapshot consistency tests: ready+enabled exposes `termux_shell` in tool schema, prompt, top-level allow-list, delegated allow-list if chosen, and Executor timeout; disabled/not-ready excludes all.
- Excluded-tool test: Termux ready but `excludedTools = setOf("termux_shell")` means no workspace prompt and no `termux_shell` schema for top-level or delegated agents.
- PRO contract tests: pure workspace task in PRO follows the chosen Planner/Executor contract; mixed UI+workspace task does not get trapped by UI-only delegation text.
- Turn arbitration tests: `mobile_action` then `termux_shell` preserves order; `termux_shell` then `mobile_action` preserves order; hoistable cognitive tools move first; `complete_task` is still deferred when screen-changing tools are selected.
- Kotlin `TermuxShellTool` tests with `MockWebServer`: non-zero exit code maps to `Success`; `timed_out=true` maps to `Success`; 409 busy maps to `Failure`; malformed JSON maps to `Failure`; configured OkHttp timeout exceeds bridge timeout by the 5s grace; coroutine cancellation calls `Call.cancel()`.
- Bridge host tests: health, exec success, non-zero exit code, timeout schema, busy behavior, stdout-only overflow, stderr-only overflow, both-stream overflow, invalid UTF-8, large pipe-buffer output, process-group kill, client disconnect behavior if retained, stale pidfile, unknown port owner.
- Bootstrap tests: RUN_COMMAND probe failure reasons, package install verification, deploy marker missing, startup failure status, health version mismatch, setup mutex serialization, stale PendingIntent result ignored.
- Resource tests: packaged raw bridge bytes match the canonical Python source and contain the expected `BRIDGE_VERSION` line.
- Settings UI tests: Termux row renders Disabled, Not Installed, Needs Setup with typed reason, Setup In Progress, Ready, and "applies next task" messaging.
- Integration QA: pure UI task regression, pure workspace task in BASIC, pure workspace task in PRO, mixed UI+workspace task, stop session during a long `termux_shell`, bridge dies mid-task and current task receives a transport error.

## Not In Scope

- Token auth and any bridge authentication design.
- Command blocklists, command risk classification, or policy approval changes.
- Workspace boundary enforcement for arbitrary shell commands.
- Auto-allow policy changes for shell tools.
- Secrets redaction from shell output.
- Independent file/read/write/edit/search/git tools.
- Remote backend, Shizuku/root shell, native backend expansion, process-management UX, and live tool hot-reload inside a running task.

## Next Step

Patch the v5 design before `/implement`. The plan is close, but implementation should not start until the task-boundary snapshot lifecycle, effective-role/excluded-tool contract, PRO delegation behavior, arbitration semantics, and bridge protocol schema are made unambiguous.

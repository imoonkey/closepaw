# Engineering Plan Review: Termux Shell Tool v4

Reviewer: Codex  
Design reviewed: `projects/active/coding/final/cn/design.md`  
Date: 2026-05-01

Security/policy hardening is intentionally deferred for this review. I did not flag auto-allow, command classification, or broader shell risk policy.

The six v3 findings are addressed in the v4 text. The remaining risks below are narrower implementation-contract gaps, mostly around how the new capability snapshot threads through this codebase and how bridge/client edge cases are represented.

---

## Plan Summary

The plan adds a `termux_shell` tool backed by a Python HTTP bridge daemon inside Termux. ClosePaw keeps the existing Android UI automation tools and the restricted Android `shell` tool, while exposing a full Termux bash workspace when setup is complete and the user has enabled it.

Success means a single session can combine UI actions and workspace shell commands, with Standalone, Planner, and delegated Executor all seeing the same fixed-at-task-start Termux capability. Setup, health, prompt injection, tool registration, role allow-lists, and executor timeout should all agree on the same immutable snapshot.

## What Already Exists

- `ToolSpec`, `ToolInvocation`, `ToolRegistry`, `ToolRouter`, and `ToolExecutionResult` are the right integration points for `TermuxShellTool`.
- `ShellTool` already demonstrates local validation, output formatting, cancellation checks, and text observations, but `termux_shell` needs a different success/error contract because shell exit codes must not become router failures.
- `AuthStoreHolder` is the right process-wide holder pattern to copy for `TermuxBridgeManagerHolder`.
- `SessionServices.create()` and `SessionToolingBootstrapper.create()` are the right session-start hooks for fixed tool registration.
- `SessionAgentRunner.start()` currently resolves static `AgentRoleDef`s, static allow-lists, prompt templates, and delegation registration after `SessionServices` has been created.
- `IsolatedSubAgentRunner` filters tools and applies timeout from the static delegated `AgentRoleDef`.
- `SessionRuntimeSnapshot` currently persists config/history/todos/scratchpad only; it has no Termux capability snapshot field.
- `AppSettingsStore` is plain SharedPreferences for non-secret settings. `AuthStore` is encrypted, but its public API is provider-credential oriented, not a generic secret store.
- `AgentBehaviorSettingsPage` is the natural UI surface for the Termux Shell settings section.
- `network_security_config.xml` already exists with cleartext disabled by default.

## Findings

### F1: Snapshot-Aware Agent Wiring Still Needs a Concrete Code Path

Severity: high

The design now correctly requires one immutable `TermuxCapabilitySnapshot`, but the current runtime has several static decision points outside `SessionServices.create()`:

- `SessionAgentRunner.start()` calls `AgentDefRegistry.mainFor(config.agentMode)` and computes `allowedToolNames = agentDef.allowedTools - config.excludedTools`.
- `resolvePromptTemplates(agentDef.systemPrompt)` builds the prompt from the static role prompt.
- `ensureDelegationToolRegistered()` passes static `AgentDefRegistry.delegatableRoles()` into `DelegateTaskTool`.
- `IsolatedSubAgentRunner` filters child tools with `roleDef.allowedTools` and uses `roleDef.timeoutMs`.

If implementation only captures the snapshot inside `SessionServices.create()` or `SessionToolingBootstrapper`, tool registration may be fixed while prompt injection, top-level allow-lists, delegated allow-lists, or Executor timeout still read static role definitions. That would recreate the class of mismatch v4 is trying to avoid.

Before implementation, define one explicit snapshot-aware role/prompt path. For example: store `termuxSnapshot` on `SessionServices`, add an `AgentRoleRuntime` or `AgentDefRegistry.resolve(mode, snapshot, excludedTools)` layer, and use that resolved value for tool registration, prompt extension, top-level `allowedToolNames`, delegated role allow-lists, and delegated timeout.

Also decide how checkpoint reload preserves fixed-at-task-start behavior. The design says reload should restore the snapshot and not re-probe, but `SessionRuntimeSnapshot` has no field for it today and `AgentSession.reload()` calls `SessionServices.create()` fresh. Either add a serialized snapshot with a schema bump, or explicitly change the design to say reload starts a new task boundary and may capture fresh Termux availability.

### F2: The Timeout Ladder Is Under-Specified for OkHttp and Sub-Agents

Severity: medium-high

The bridge command timeout is 120s and Executor timeout becomes 150s, but the Kotlin HTTP client timeout is not specified. OkHttp's defaults are not suitable for long shell commands unless explicitly configured; a default read timeout can fail a valid long-running command well before the bridge timeout.

Define the full timeout ladder:

- requested `timeout_seconds` is clamped to the tool maximum, likely 120s;
- bridge receives `timeout_ms`;
- OkHttp read/call timeout is `timeout_ms + grace`, still below any enclosing agent/sub-agent timeout;
- delegated Executor timeout is 150s only when the effective role actually has `termux_shell`;
- busy `409` returns immediately and does not consume the long command timeout.

Add tests with a fake HTTP server that delays the response body beyond the default OkHttp timeout but within the configured Termux command timeout.

### F3: Bridge Timeout Response Conflicts With Tool Result Semantics

Severity: medium-high

The bridge pseudo-code returns HTTP 200 for timeout with `{"error": "timeout", ...}`, while the tool mapping says "HTTP 200, any exit_code" maps to `ToolExecutionResult.Success` and non-200 maps to `Failure`.

This needs one canonical protocol. In the current ClosePaw execution loop, a `ToolExecutionResult.Failure` becomes `ToolCallResult.Error` and aborts the remaining tool calls in that turn. That may be right for transport/protocol errors, but it is probably wrong for ordinary command outcomes like `grep` returning 1. Timeout sits between those two categories and must be explicit.

Pick one:

- timeout is command-level output: HTTP 200 with fields such as `timed_out=true`, `exit_code=null`, partial stdout/stderr, and the Kotlin tool maps it to `Success`; or
- timeout is transport/protocol failure: HTTP 408/504 or a typed non-200 response, and the Kotlin tool maps it to `Failure`.

The design should say which behavior is expected, because it changes same-turn execution, trace status, and LLM recovery.

### F4: Token and Toggle Storage Need Named APIs, Not Just "AuthStore"

Severity: medium

The plan says `TermuxBridgeManager` receives `AuthStoreHolder.get(appContext)` and stores the promoted token in EncryptedSharedPreferences. The existing `AuthStore` API is keyed by `LLMProvider` and stores `AuthCredential`; it is not currently a generic encrypted secret API.

Define the storage shape before coding:

- either add a small `TermuxSecretStore` backed by EncryptedSharedPreferences;
- or extend `AuthStore` with a clearly namespaced generic secret API instead of overloading provider credentials.

The enabled toggle should also have one clear home, likely `AppSettingsStore`, while the promoted token belongs in encrypted storage. Keep `enabled` independent from probe-derived readiness so health polling cannot overwrite a user's disabled setting. This is not a security-hardening finding; it is about avoiding state split between Settings, manager state, and session snapshots.

### F5: Bridge Script "Single Canonical Source" Contradicts the Phase Plan

Severity: medium

The design says `app/src/main/res/raw/closepaw_bridge_py` is the single canonical source, but Phase 2 also lists `tools/termux-bridge/closepaw_bridge.py` for development/testing plus `res/raw/` for packaging. Without a sync rule, host tests can pass against one script while the APK ships another.

Pick the source-of-truth mechanics:

- keep the Python source under `tools/termux-bridge/` and add a Gradle copy/check task that materializes `res/raw/closepaw_bridge_py`; or
- keep `res/raw/closepaw_bridge_py` canonical and make host tests run against that exact file.

The CI version-line test should prove both "version line exists" and "host-tested bytes match packaged bytes" if two paths remain.

### F6: Output Overflow Needs Separate Stdout/Stderr Semantics

Severity: medium

The bridge uses separate drain threads for stdout and stderr, but the response shape has one `truncated` boolean and one `output_ref`. That is ambiguous when stderr alone overflows, both streams overflow, or one stream is capped in-memory while the other is fully in-memory.

Specify either:

- a combined transcript artifact with deterministic stream labels; or
- separate fields such as `stdout_truncated`, `stderr_truncated`, `stdout_ref`, and `stderr_ref`.

The Kotlin tool output should show exactly what was truncated and where the rest lives. Otherwise the LLM can receive a misleading `stderr`/`stdout` pair, and tests will be forced to assert accidental formatting.

### F7: Manual Setup Fallback Is Still a Placeholder

Severity: low-medium

The plan says that when `RUN_COMMAND` is unavailable the UI shows a copyable setup script plus token paste, but the implementation contract is still missing. If this is truly in v1, define how manual setup reaches `Ready`:

- where the pasted token is stored;
- whether the user starts the bridge manually or ClosePaw still starts it later;
- how ClosePaw validates `/v1/health` with the pasted token;
- how failed manual setup is mapped to `NeedsSetup(reason)`;
- whether the token is rotated again when `RUN_COMMAND` becomes available.

If the fallback is not required for v1, remove it from the v1 plan and make "Termux RUN_COMMAND unavailable" a typed `NeedsSetup`/unsupported state.

## Test Plan

- Snapshot wiring tests: ready+enabled includes `termux_shell` in registry, prompt, main allow-list, delegated allow-list, and delegated timeout; disabled/not-ready excludes all of them.
- Excluded-tool tests: if `config.excludedTools` contains `termux_shell`, the tool schema and workspace prompt are both absent even when Termux is ready.
- Reload tests: checkpoint reload either restores the serialized Termux snapshot or explicitly captures a fresh snapshot, matching the final design decision.
- Role tests: BASIC/Standalone, PRO/Planner, and delegated Executor all resolve their effective tools from the same snapshot; Executor timeout changes only when `termux_shell` is effectively available.
- Kotlin HTTP tests with `MockWebServer`: non-zero exit code maps to `Success`; `busy` 409 maps to `Failure` with typed error text; malformed JSON maps to `Failure`; long body delay does not hit OkHttp's default timeout; coroutine cancellation calls `Call.cancel()`.
- Bridge host tests: health auth, wrong token, busy lock, timeout response schema, stdout overflow, stderr overflow, both-stream overflow, large pipe-buffer output, process-group kill, stale pidfile, unknown port owner.
- Bootstrap tests: setup mutex serialization, candidate token promotion only after health 200, old token retained until replacement bridge is healthy, stale `RUN_COMMAND` result ignored, manual fallback behavior if retained.
- Settings UI tests: Termux row shows enabled toggle, Ready, Needs Setup with typed reason, Not Installed, Setup In Progress, and "applies next task" messaging.
- Integration QA: pure UI task regression, pure workspace task in BASIC, pure workspace task in PRO through Planner/Executor, mixed UI+workspace task, stop session during long `termux_shell`, bridge dies mid-task and current session receives a transport error.

## Not In Scope

- Security/policy hardening for `termux_shell`.
- Independent file/read/write/edit/search/git tools.
- Remote backend, Shizuku/root shell, native backend expansion, and process-management UX.
- `/v1/cancel` endpoint unless client-disconnect cancellation proves unreliable.
- Live hot-reload of tool lists inside an already-running task.

## Next Step

The v4 plan is close enough for implementation after a small contract patch. Before `/implement`, resolve the snapshot-aware role/prompt/reload path, OkHttp timeout ladder, timeout response schema, token/toggle storage APIs, bridge script source-of-truth, and stdout/stderr overflow schema. Manual setup can either be specified now or explicitly deferred.

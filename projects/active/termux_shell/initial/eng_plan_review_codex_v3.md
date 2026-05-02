# Engineering Plan Review: Termux Shell Tool v3

Reviewer: Codex  
Design reviewed: `projects/active/coding/final/cn/design.md`  
Date: 2026-05-01

Security/policy hardening remains intentionally out of scope for this review. I did not flag auto-allow, command classification, or shell risk policy as findings.

---

## Plan Summary

The v3 plan adds `termux_shell`, backed by a Python bridge daemon running inside Termux, while preserving ClosePaw's existing Android UI automation tools and restricted Android `shell` tool. Success means a single task can combine UI tools and a full Termux workspace shell, with Standalone, Planner, and Executor all able to see `termux_shell` when the bridge is ready and the feature is enabled.

The plan is now close to implementable. The v2 architectural blockers are addressed. The remaining work is mostly turning stated intentions into exact contracts at the bootstrap, bridge execution, and session-wiring boundaries.

## What Already Exists

- `ToolSpec`, `ToolInvocation`, `ToolRegistry`, `ToolRouter`, and `ToolExecutionResult` are the right integration points for `TermuxShellTool`. `ShellTool.kt` already shows the local validation/result-observation shape.
- `SessionToolingBootstrapper.create()` registers built-in tools once per session. That matches the design's fixed-at-task-start tool availability, but it currently has no bridge manager or capability snapshot input.
- `TurnPlanningPhaseRunner` builds the system prompt per turn, while tool registration happens once during `SessionServices.create()`. The Termux prompt section must therefore use the same session-start snapshot as tool registration, not live manager state.
- `AgentRoleDef` allow-lists and sub-agent timeout values are static today. Executor timeout changes need an explicit runtime path, not just an edit to one constant if the behavior depends on Termux being enabled.
- The app does not currently use Hilt or annotation-based DI. Process-wide services use holder objects such as `AuthStoreHolder`, and sessions are wired through `MainActivity`, `AgentService`, `AgentSession`, and `SessionServices`.
- `AuthStore` shows the existing encrypted-preferences pattern for secret values. `AppSettingsStore` is plain SharedPreferences for non-secret settings.

## Findings

### F1: `@Singleton` / `@Inject` Does Not Match This Codebase's Construction Pattern

Severity: high

The design sketches `TermuxBridgeManager` as an `@Singleton` with `@Inject`, but this project has no Hilt/Dagger wiring. Following that plan literally either will not compile or will create parallel ad-hoc instances between Settings, AgentService, and sessions.

Before implementation, define the actual ownership path in existing terms:

- a process-wide `TermuxBridgeManagerHolder.get(applicationContext, appScope)` pattern, similar to `AuthStoreHolder`; or
- explicit construction in `MainActivity` / `AgentService` and threading through `AgentSession.create()` and `SessionServices.create()`.

Also specify the coroutine scope that owns health polling and setup work. Use `applicationContext`, not an Activity/Service context, because the manager is process-lifetime state.

### F2: Candidate Token Flow Still Missing the Daemon-Side Token Contract

Severity: high

The design now correctly uses a candidate-token two-phase commit, but it does not say how the candidate token reaches the bridge process. Step 4 starts the bridge as:

```sh
python3 ~/.closepaw/bridge.py &
```

That command gives the daemon no token unless an unstated file, env var, argv, or generated config is used.

Specify this contract before coding:

- where Termux stores the bridge token, for example `~/.closepaw/token` or `~/.closepaw/bridge.json`;
- whether the bridge reads the token once at startup or per request;
- how setup writes candidate token material without breaking an already-running old bridge;
- how startup passes the candidate token to the new daemon before ClosePaw promotes it;
- what exact files are removed or retained when candidate health fails.

Without this, the Kotlin manager and Python bridge can both be correct in isolation and still fail token rotation.

### F3: Bridge Execution Loop Needs a Concrete Async Design

Severity: high

The bridge requirements are right: process-group timeout kill, client disconnect kill, stdout/stderr capture, output cap, and artifact overflow. The plan does not yet describe the execution loop that makes those guarantees true in Python stdlib.

This matters because a naive `Popen(...).communicate(timeout=...)` or "wait then read" implementation has failure modes:

- large stdout/stderr can fill OS pipes and deadlock;
- client disconnect may only be discovered when writing the HTTP response, after the subprocess already ran to completion or timeout;
- separate stdout and stderr drains can race or lose cap accounting unless the cap policy is explicit;
- a cancelled request can hold the one-at-a-time exec lock until timeout if disconnect detection is passive.

Before implementation, define the bridge-side loop: continuously drain stdout/stderr, enforce the byte cap while writing overflow artifacts, poll subprocess status, detect socket disconnect actively, and kill the whole process group on timeout or disconnect. Add host tests that prove a long `sleep` dies promptly when the HTTP client closes the connection and that output beyond pipe-buffer size does not hang.

### F4: One-at-a-Time `/v1/exec` Needs Bounded Queue Semantics

Severity: medium-high

The design says concurrent `/v1/exec` requests queue behind an internal lock. That avoids workspace races, but it creates timeout ambiguity. If request B waits behind request A for up to 120s and then runs its own 120s command, the caller may exceed the 150s Executor timeout before command execution even starts.

Pick one bounded behavior:

- reject while busy with a typed bridge error such as `busy`;
- wait only up to a small `queue_timeout_ms`, then return a typed error;
- allow queueing but make Kotlin client/read timeout and Executor timeout account for worst-case queue wait.

The simplest v1 behavior is usually "one active command, no unbounded queue": return a typed busy error and let the LLM retry or sequence work. If queueing remains, include queue wait duration in the response so client logs and tests can explain timeouts.

### F5: Fixed-at-Task-Start Needs an Explicit Session Capability Snapshot

Severity: medium

The design documents fixed-at-task-start semantics, but the implementation snippets still read `bridgeManager.state.value` directly in registration, and prompt injection is listed under `TurnPlanningPhaseRunner`, which runs every turn. In the current codebase, that can split behavior unless there is a dedicated immutable snapshot captured during `SessionServices.create()`.

Add a small session-level value, for example:

```kotlin
data class TermuxCapabilitySnapshot(
    val enabled: Boolean,
    val status: TermuxBridgeStatus,
    val workspacePromptEnabled: Boolean
)
```

Then use that same snapshot for:

- registering or omitting `TermuxShellTool`;
- appending or omitting the workspace prompt section;
- role allow-list adjustments and Executor timeout changes;
- checkpoint/reload behavior.

Also make `enabled` separate from probe-derived readiness. A `Disabled` enum state can accidentally erase the last real health status or be overwritten by background health polling.

### F6: RUN_COMMAND Bootstrap Result Handling Is Still Too Vague for Android Code

Severity: medium

The bootstrap flow says `RUN_COMMAND` is used for probe, package install, deploy, token generation, and bridge start, but the Android-side contract is not yet concrete enough to implement robustly.

Define:

- the component that sends `RUN_COMMAND` intents and receives PendingIntent results;
- request IDs and how late/stale results are ignored;
- per-step timeouts and retry behavior;
- the exact success marker format for each step, not only token generation;
- how stdout/stderr/result-code are parsed into `NeedsSetup(reason)`;
- how `EXTRA_STDIN` deploys the raw resource without shell quoting corruption;
- how the fallback manual setup path feeds the token/status back into `TermuxBridgeManager`.

This is not a design rewrite. It is a missing adapter contract between Android intents and the manager state machine.

## Test Plan

- Manager ownership tests: one process-wide manager instance is shared by Settings and sessions; construction uses application context; health polling job is cancelled/started predictably.
- Session capability tests: disabled/not-ready omits both tool and prompt; ready+enabled includes both; manager state changes mid-task do not change the current session; next task sees the new state.
- Role wiring tests: Standalone, Planner, and Executor expose `termux_shell` when registered; Executor timeout changes only for Termux-enabled sessions; sub-agent filtered registries include the tool when available.
- Bootstrap tests: probe missing permission, allow-external-apps missing, package check failure, deploy failure, missing/duplicate/malformed token, candidate health failure, old token retained, candidate promoted only after health 200, stale RUN_COMMAND result ignored.
- Bridge host tests: health auth, wrong token 401, version mismatch, pidfile ownership, unknown port owner, stdout/stderr larger than pipe buffer, timeout process-group kill, client disconnect kill, output cap plus artifact write, busy/queue behavior.
- Kotlin client tests with a fake HTTP server: HTTP 200 with exit code 0 and non-zero both map to `Success`; 401/4xx/5xx/malformed JSON/connection refused map to `Failure`; coroutine cancellation calls `Call.cancel()`.
- Turn policy tests: only cognitive tools are hoisted; `shell`, `termux_shell`, `ask_user`, and `activate_skill` preserve LLM order relative to UI tools.
- UI tests: Agent Behavior page shows Disabled, Not Installed, Needs Setup with typed reason, Setup In Progress, Ready, and "applies next task" messaging.
- Device QA: pure UI task regression, pure workspace task in BASIC and PRO, mixed UI+workspace task, stop session during long `termux_shell`, checkpoint reload with dead bridge.

## Not In Scope

- Security/policy hardening for `termux_shell`; auto-allow is accepted for this v1.
- Independent file/read/write/edit/search/git tools.
- Remote backend, Shizuku/root shell, native backend expansion, and process-management UX.
- `/v1/cancel` endpoint, unless the bridge-side disconnect implementation proves unreliable.
- Live hot-reload of tool lists inside an already-running task.

## Next Step

Do one small design patch before `/implement`: specify manager ownership without Hilt, token materialization/startup, bridge exec loop mechanics, bounded `/v1/exec` queue behavior, and the session capability snapshot. After those contracts are written down, the plan is ready to implement in phases.

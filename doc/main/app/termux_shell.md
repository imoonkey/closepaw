# Termux Shell

> Full Linux bash runtime for the agent through a Termux bridge daemon.
> Last updated: 2026-05-05

## Overview

`termux_shell` is the agent-facing tool for workspace-style command execution on Android. It runs
full bash inside Termux through a localhost bridge, so the agent can use pipes, redirects, git,
python, ripgrep, and other installed Termux packages.

Use `termux_shell` for files, repositories, scripts, tests, build tools, and data processing. Use
the existing `shell` tool only for quick Android toybox inspection commands; it has a restricted
command surface and intentionally rejects shell metacharacters such as pipes and redirects.

Canonical contract: [projects/active/termux_shell/final/design.md](../../../projects/active/termux_shell/final/design.md).
This page is the runtime map, not a duplicate design spec.

## Architecture

```
Termux bridge daemon (Python, 127.0.0.1:18422)
        ^
        | HTTP /v1/health + /v1/exec
        v
TermuxBridgeManager (Kotlin lifecycle, setup, health, snapshot)
        ^
        | immutable TermuxCapabilitySnapshot at session creation
        v
TermuxShellTool (ToolSpec, OkHttp, result mapping)
        ^
        | conditional registration + prompt injection
        v
AgentRoleDef.resolve(...) for Standalone / Planner / Executor
```

Primary files:
- `tools/termux-bridge/closepaw_bridge.py` - canonical bridge source, packaged into `res/raw`.
- `termux/TermuxBridgeManager.kt` - singleton bridge state, setup, restart, session readiness.
- `termux/TermuxRunCommandAdapter.kt` - Termux `RUN_COMMAND` bootstrap adapter.
- `tool/impl/TermuxShellTool.kt` - tool schema and HTTP execution.
- `agent/definition/AgentRoleDef.kt` - role resolution from a Termux capability snapshot.

## State Machine

| State | Meaning |
|-------|---------|
| `NotInstalled` | Termux is not installed. Settings points the user to F-Droid. |
| `NeedsSetup(reason)` | Termux is present or expected, but the bridge is not ready. |
| `SetupInProgress` | A setup, restart, or readiness operation is running. |
| `Ready` | Bridge health returned the expected identity and version. |
| `Disabled` | User settings disable `termux_shell`; the tool is hidden from sessions. |

`NeedsSetupReason` values:

| Reason | Meaning |
|--------|---------|
| `PERMISSION_MISSING` | ClosePaw lacks `com.termux.permission.RUN_COMMAND`. |
| `ALLOW_EXTERNAL_APPS_MISSING` | Termux has not enabled `allow-external-apps`. |
| `PACKAGES_MISSING` | Required Termux packages (`python3`, `git`, `rg`) are missing or failed to install. |
| `BRIDGE_OUTDATED` | Deployed bridge version differs from the APK-packaged bridge. |
| `HEALTH_TIMEOUT` | The bridge did not answer `/v1/health` within the readiness timeout. |
| `PORT_IN_USE` | `127.0.0.1:18422` is owned by another process. |
| `TERMUX_TIMEOUT` | A Termux `RUN_COMMAND` bootstrap command timed out. |
| `TERMUX_RUN_COMMAND_UNAVAILABLE` | The installed Termux build does not expose `RUN_COMMAND`. |
| `TERMUX_NOT_RUNNING` | Android rejected the cross-app service start; the user must open Termux manually. |
| `UNKNOWN` | Setup failed without a more specific mapped reason. |

## Lifecycle Invariants

- Session creation calls `ensureReadyForSession(...)`, then captures one immutable
  `TermuxCapabilitySnapshot`. Tool availability and prompt wording do not change during that
  session, including Hot Idle follow-up tasks.
- `ensureReadyForSession(...)` may restart an already-deployed idle bridge, but it does not run
  `apt` or redeploy packages. Full bootstrap remains explicit setup work.
- The Python bridge exits after 30 minutes of bridge idleness. The app does not keep it alive with
  background health polling; the next session readiness check restarts it if the deployed bridge
  exists.
- Bootstrap is consent-only. Passive Settings rendering may detect and probe state, but package
  install and bridge deploy run only from an explicit setup action. Session readiness may restart an
  already-deployed idle bridge.
- Setup, restart, and session readiness each have a process-owned in-flight `Job` keyed by
  `OperationKind` (`Setup`, `Restart`, `EnsureReady`). UI cancellation stops awaiting the operation
  without leaking or conflating another operation kind.

## Runtime Semantics

- `RUN_COMMAND` is bootstrap-only. Runtime command execution uses HTTP to avoid Binder result-size
  limits.
- `/v1/exec` runs one bash command at a time. Concurrent execution returns HTTP 409 `busy`; health
  checks still work.
- Non-zero process exit codes are normal tool output, not transport failures. The LLM receives
  `exit_code`, `stdout`, `stderr`, timeout flags, and truncation refs.
- Command timeout defaults to 120s. The executor sub-agent timeout is raised to 150s only when
  `termux_shell` is exposed.
- Workspace cwd defaults to `~/closepaw/workspace/`. Bridge-side cwd validation rejects paths
  outside that workspace. To share files with other Android apps, copy them to `/sdcard/Download/`.
- `termux_shell` is non-screen-changing and auto-allowed like `shell`, but it must not control
  Android UI or bypass app-tier restrictions. UI work still belongs to `mobile_action`,
  `system_button`, `open_app`, and related UI tools.
- `TurnToolPolicy` hoists only cognitive tools (`scratchpad`, `write_todos`,
  `remember_experience`). `termux_shell`, `shell`, and UI tools keep the LLM-returned order.

## Known Limitations

- F-Droid Termux is required. The Google Play build does not expose the required `RUN_COMMAND`
  surface and is reported as `TERMUX_RUN_COMMAND_UNAVAILABLE`.
- Some OEM ROMs block cross-app foreground-service starts with errors such as "forbidden to start a
  3rd process by service" and may also deny background activity launch attempts. On those devices,
  the user must open Termux manually, return to ClosePaw, and tap setup again.
- v1 has no `/v1/cancel` endpoint. Cancellation relies on OkHttp cancel / TCP disconnect; the bridge
  detects the closed client connection and kills the bash process group.
- v1 does not include standalone file, search, patch, git, or process tools. The agent uses bash for
  those workflows.
- Shell security hardening is deferred to `projects/active/shell_security/`; loopback-only binding
  and workspace cwd validation are the v1 boundary.

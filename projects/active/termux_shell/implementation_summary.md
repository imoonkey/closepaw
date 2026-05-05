# Termux Shell Implementation Summary

> Last updated: 2026-05-05

## What Was Built

The branch adds `termux_shell`, a new agent tool that runs full Linux bash inside Termux through a
small localhost bridge daemon. It gives ClosePaw a general-purpose workspace runtime while keeping
Android UI automation on the existing UI tool stack.

The implementation is intentionally narrow:
- One new agent-facing tool: `termux_shell`.
- One Python bridge daemon: `tools/termux-bridge/closepaw_bridge.py`.
- One Kotlin lifecycle owner: `TermuxBridgeManager`.
- Conditional role resolution: Standalone, Planner, and Executor expose the tool only when the
  session snapshot says Termux is enabled and ready.

The existing `shell` tool remains a restricted Android toybox inspection tool. File editing, git,
search, builds, and scripts go through bash in `termux_shell` for v1.

## Phases At A Glance

| Phase | Result |
|-------|--------|
| 1. Substrate | Added Termux manifest visibility, `RUN_COMMAND` permission/query, localhost cleartext exception, packaged bridge resource, and resource/config tests. |
| 2. Bridge daemon | Implemented Python `ThreadingHTTPServer` on `127.0.0.1:18422` with `/v1/health`, `/v1/exec`, one in-flight command, output caps/refs, timeout/process-group kill, pidfile takeover, and 30-minute idle exit. |
| 3. Bridge manager | Added `TermuxBridgeManager`, `TermuxRunCommandAdapter`, state types, setup/restart/readiness flows, typed setup reasons, F-Droid/Play-build detection, and settings-backed enablement. |
| 4. Tool | Added `TermuxShellTool`, `ToolName.TermuxShell`, HTTP result mapping, cancellation through OkHttp, non-zero exit-code success semantics, and ordering tests. |
| 5. Agent wiring | Added capability snapshots to `SessionServices`, conditional tool registration, `AgentRoleDef.resolve(...)`, workspace-shell prompt injection, Planner direct-shell directive, and Executor timeout bump. |
| 6. Settings + QA | Added the Agent Behavior Termux Shell row, setup/restart actions, state-specific hints, emulator QA, and real-device validation on P0110. |

## Key Decisions

- **RUN_COMMAND is bootstrap-only.** Binder result limits make it unsuitable for runtime command
  traffic, so the bridge owns command execution after setup.
- **Session snapshots are immutable.** `SessionServices.create(...)` calls
  `ensureReadyForSession(...)`, captures `TermuxCapabilitySnapshot`, and the tool roster/prompt stay
  fixed for the session, including Hot Idle follow-up tasks.
- **No background bridge polling.** Health checks refresh bridge idleness, so periodic polling would
  defeat the 30-minute idle exit. The bridge is restarted only from session readiness when the
  deployed bridge exists.
- **Bootstrap remains consent-only.** Passive Settings display can detect/probe state but does not
  install packages, deploy bridge bytes, or start bootstrap without a user action.
- **Shell output is data, not control flow.** Non-zero exit codes and bridge command timeouts return
  `ToolExecutionResult.Success` with structured fields. Transport/protocol issues return failures.
- **Cross-operation ownership is explicit.** `TermuxBridgeManager` owns process-scoped in-flight jobs
  per operation kind (`Setup`, `Restart`, `EnsureReady`) so UI coroutine cancellation does not leak
  a Termux mutation and setup/readiness calls do not satisfy each other incorrectly.

## Review Iterations

Phase reviews fixed the first implementation pass:
- Phase 1 tightened localhost cleartext and made the packaged raw bridge byte-identical to the
  canonical script.
- Phase 2 hardened bridge env/cwd parsing, output truncation, busy handling, pidfile behavior, and
  host-side pytest coverage.
- Phase 3 fixed deploy byte transfer, port-in-use reporting, cancellation state restoration, passive
  health behavior, and receiver export rules.
- Phase 4 fixed OkHttp cancellation/resource cleanup and locked turn-ordering coverage.
- Phase 5 aligned role prompts with the final design and added negative resolution tests.
- Phase 6 added actionable settings copy and the cold-start session probe before snapshot capture.

The final-pass reviews then closed larger lifecycle issues:
- Final review: moved setup behind explicit consent, restarted idle-exited deployed bridges from
  `ensureReadyForSession(...)`, and made package install idempotent.
- Second pass: moved setup/restart/readiness into manager-owned jobs, added atomic bridge deploy, and
  preserved typed Termux timeout reasons.
- Third pass: split in-flight jobs by operation kind and switched deploy temp files to unique paths.

QA pass findings also landed:
- Emulator QA against Termux 0.118.1 fixed `err=-1` success parsing, replaced missing `which` with
  `command -v`, and fixed the NotInstalled row flow.
- Fresh-Termux QA made first-time bootstrap unattended by creating the mirror symlink, using raw
  `apt` with date-check tolerance, hardcoding the Termux prefix for non-login RUN_COMMAND shells,
  and using `startForegroundService` on Android 8+.
- Real-device QA on P0110 (Android 16, `100.64.43.95:5555`) showed OEM background restrictions:
  cross-app FGS start can be blocked with "forbidden to start a 3rd process by service", and
  background activity launch can be denied as well. The reverted auto-wake attempt had no benefit;
  the shipped behavior is a typed `TERMUX_NOT_RUNNING` reason plus UI copy telling the user to open
  Termux manually.

## Known Limitations

- F-Droid Termux is required. The Google Play build lacks the required `RUN_COMMAND` service surface.
- Some OEM ROMs require a manual Termux launch before setup because the OS blocks ClosePaw from
  waking Termux in the background.
- v1 has no token auth and no command-risk classifier; shell security hardening is tracked under
  `projects/active/shell_security/`.
- v1 has no standalone file/search/git/process tools. Bash is the only workspace primitive.
- Mid-session bridge failure is surfaced as a tool transport error; the session tool list is not
  hot-reloaded.

## Current State

The branch is merged with current `main` and all review blockers have been addressed. Recorded
verification includes:
- `./gradlew assembleDebug`
- `./gradlew test`
- `python3 -m pytest tools/termux-bridge/tests -q`
- Emulator QA on Termux 0.118.1
- Real-device QA on P0110 for OEM restriction handling

Implementation head before this documentation pass: `9a6def5e`.

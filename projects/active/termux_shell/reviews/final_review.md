# Final Branch Review — termux-shell (24 commits)

## Verdict
REQUEST_CHANGES

## Critical / High findings

1. app/src/main/kotlin/ai/closepaw/ui/settings/AgentBehaviorSettingsPage.kt:95 — Opening the Agent Behavior settings page can run the full Termux bootstrap without an explicit setup tap. `LaunchedEffect(manager, termuxShellEnabled)` calls `manager.setup()` whenever the toggle is enabled and the in-memory state is still `NotInstalled`. Since `AppSettingsStore.DEFAULT_TERMUX_SHELL_ENABLED` is `true`, a user who simply opens this settings page after installing Termux can trigger the package install, bridge deploy, and bridge start path. That violates the design's Settings UI contract ("Needs Setup — tap to set up") and the v1 out-of-scope item "automatic package install without approval." It also regresses the phase-3 invariant that passive checks are probe-only: this effect was changed to solve fresh-install detection, but it now crosses from detection into mutation. Fix: make the effect call only `manager.healthCheck()`; that already detects installed-vs-not-installed and maps a missing bridge to `NeedsSetup`. Keep `manager.setup()` behind an explicit row action or another clear user command.

2. app/src/main/kotlin/ai/closepaw/termux/TermuxBridgeManager.kt:112 / app/src/main/kotlin/ai/closepaw/session/SessionServices.kt:214 / tools/termux-bridge/closepaw_bridge.py:353 — The bridge lifecycle promised by the design is not wired end-to-end. The daemon has a 30-minute idle watchdog, but `TermuxBridgeManager.startHealthPolling()` has no call sites. Session creation performs only a passive `healthCheck()` before snapshot capture; if the bridge exited due to the watchdog, `healthCheck()` maps that to `NeedsSetup(HEALTH_TIMEOUT)` and the snapshot hides `termux_shell` for the whole session. `TermuxShellTool` also talks directly to HTTP and maps connection failure to `bridge_unavailable`, so there is no on-demand restart path. Result: after a successful setup, waiting past the bridge idle timeout can silently remove the workspace tool from the next task until the user manually re-enters setup. Fix: add an explicit `ensureReadyForSession`/restart path that can start an already-deployed bridge without rerunning package install, or wire lifecycle health polling from an app/service scope and test the idle-exit-to-next-session path.

## Medium findings

1. app/src/main/kotlin/ai/closepaw/termux/TermuxBridgeManager.kt:58 — The install step is not idempotent in the way the design describes. It always runs `apt update` and `apt install` before checking `command -v python3 && command -v git && command -v rg`. A Termux install that already has the required packages but is temporarily offline or has a mirror issue will fail setup as `PACKAGES_MISSING` even though the runtime is usable. Fix: first probe the required binaries and skip apt entirely when they are present; only run mirror setup/update/install when something is missing.

2. Branch integration hygiene — The PR-owned diff from merge-base is the expected 39-file Termux change set, but raw `git diff main..HEAD` also shows a large unrelated browser/CDP delta because `task/termux-shell` is behind current `main` by many browser commits. Before merge, rebase or merge current `main` and run the final verification on the actual merge result so the browser security/runtime changes on `main` are not accidentally evaluated or shipped as absent from the branch state.

## Coverage gaps worth landing

- Add JVM tests for `TermuxRunCommandAdapter` result parsing: bundle-vs-top-level fields, `err=-1` success, `err=0`, positive `err`, non-empty `errmsg`, allow-external-apps text, missing `exitCode`, stale request IDs, and cancellation unregistering the receiver.
- Add fakeable `TermuxBridgeManager` state-machine tests for passive health vs setup, cancellation restoration, version mismatch, port-in-use, idle-exited bridge restart, and "packages already installed skips apt."
- Add Compose or presenter-level tests for the Termux settings row: initial render must probe without setup, row tap owns setup/F-Droid fallback, toggle-off renders Disabled without changing bridge state, and setup cannot re-enter from recomposition.
- Add a session integration test where an enabled, deployed bridge is not currently listening. The next session should either restart it or produce a deliberate setup status, not silently hide the tool due only to the idle watchdog.
- Keep the on-device QA matrix for fresh Termux, already-installed/offline Termux, RUN_COMMAND permission missing, allow-external-apps disabled, port conflict, and long-command cancellation. These are the exact classes of bugs the JVM suites did not catch.

## What's solid

- Manifest/package visibility, release localhost cleartext, and bridge resource packaging match the design; the packaged raw resource is byte-identical to `tools/termux-bridge/closepaw_bridge.py`.
- The Python bridge is coherent: loopback-only, workspace cwd guard, independent stdout/stderr caps and refs, non-zero exit as HTTP 200, timeout payloads, 409 busy, client-disconnect kill, and watchdog behavior are all covered by host tests.
- `TermuxShellTool` preserves the important result semantics: non-zero exit and bridge timeouts are tool successes, transport/protocol errors are failures, OkHttp timeout is `command timeout + grace`, and cancellation calls `Call.cancel()`.
- Role resolution, prompt injection, excluded-tool handling, Planner direct-shell directive, Executor timeout bump, and `TurnToolPolicy` ordering are consistent with the design.
- Focused verification run during this review passed:
  - `python3 -m pytest tools/termux-bridge/tests -q` — 18 passed
  - `./gradlew :app:testDebugUnitTest --tests 'ai.closepaw.tool.impl.TermuxShellToolTest' --tests 'ai.closepaw.agent.cognition.policy.TurnToolPolicyTest' --tests 'ai.closepaw.agent.definition.AgentRoleResolveTest' --tests 'ai.closepaw.session.SessionServicesTermuxSnapshotTest' --tests 'ai.closepaw.termux.ClosePawBridgeResourceTest'` — BUILD SUCCESSFUL

# Second-Pass Branch Review — termux-shell (25 commits)

## Verdict
REQUEST_CHANGES

The first-pass blockers are mostly fixed in the current code, but I would not merge this branch yet. The remaining blocker is a bootstrap cancellation/concurrency race: the app-side mutex can be released while a Termux RUN_COMMAND process is still mutating packages or `~/.closepaw/bridge.py`, allowing a second setup/session readiness path to overlap with the first one.

Verification run during this review:
- `python3 -m pytest tools/termux-bridge/tests -q` — 18 passed
- `./gradlew :app:testDebugUnitTest --tests 'ai.closepaw.tool.impl.TermuxShellToolTest' --tests 'ai.closepaw.agent.cognition.policy.TurnToolPolicyTest' --tests 'ai.closepaw.agent.definition.AgentRoleResolveTest' --tests 'ai.closepaw.session.SessionServicesTermuxSnapshotTest' --tests 'ai.closepaw.termux.ClosePawBridgeResourceTest'` — BUILD SUCCESSFUL
- `./gradlew test` — BUILD SUCCESSFUL

## Verification of first-pass fixes

1. **Passive Settings render must not call setup()** — fixed.
   `AgentBehaviorSettingsPage.kt:95-100` now runs `manager.detectInstalled()` and then `manager.healthCheck()` only when Termux is present. There is no `setup()` call in the `LaunchedEffect` passive path. The explicit setup paths remain behind row taps at `AgentBehaviorSettingsPage.kt:109-134`.

2. **Idle-exited deployed bridge must be restarted for the next session without package install** — implementation is present, but coverage is weak.
   `SessionServices.kt:218-228` now calls `ensureReadyForSession(timeoutMs)` before snapshot capture. `TermuxBridgeManager.kt:219-239` checks health, detects Termux, tests for `~/.closepaw/bridge.py`, and then runs only `startBridge()` when the bridge file exists. If the file is missing, it returns the passive `NeedsSetup(HEALTH_TIMEOUT)` state and does not run install/deploy.

   Caveats: the real restart branch is not exercised by the new test; see finding 2. Also, `ensureReadyForSession(timeoutMs)` is bounded by the outer `SessionServices` timeout at the production call site, but the method's own timeout starts after acquiring the manager mutex (`TermuxBridgeManager.kt:93-95`), so direct callers could still wait behind a long setup.

3. **Idempotent install command** — fixed.
   `INSTALL_COMMAND` now probes `command -v python3 && command -v git && command -v rg` before the apt path (`TermuxBridgeManager.kt:52-58`). Under bash's `&&`/`||` left-associative semantics, the apt group executes only if at least one probe fails. The mirror symlink/update/install path is grouped, so the initial probe short-circuits correctly when all three binaries are already present.

4. **Branch integration hygiene** — still a merge-result risk, not a Termux code blocker.
   `git diff $(git merge-base main HEAD)..HEAD` is the expected Termux scope. Raw `git diff main..HEAD` still shows many unrelated browser/CDP changes because this branch is behind current `main`; final verification should be run on the actual rebased or merged result.

## New findings

1. **High — `app/src/main/kotlin/ai/closepaw/termux/TermuxRunCommandAdapter.kt:37`, `app/src/main/kotlin/ai/closepaw/termux/TermuxRunCommandAdapter.kt:96`, `app/src/main/kotlin/ai/closepaw/termux/TermuxBridgeManager.kt:138`, `app/src/main/kotlin/ai/closepaw/ui/settings/AgentBehaviorSettingsPage.kt:109` — cancelled bootstrap can keep running in Termux after the app releases the setup lock.**

   `runShell()` wraps the PendingIntent wait in `withTimeoutOrNull`, and cancellation only unregisters the result receiver. It does not cancel the Termux-side command already launched through RUN_COMMAND. `runWithSetupState()` then restores the previous state and releases the mutex on `CancellationException`, while the external command can still be running. Because settings launches setup from a composition-scoped coroutine, navigating away from the settings row can cancel the app coroutine while `apt install`, deploy, or start is still active in Termux.

   This breaks the "manager owns bridge state" invariant and can overlap a second `setup()` or `ensureReadyForSession()` with the first still-running Termux mutation. The deploy step writes directly to `~/.closepaw/bridge.py`, so overlapping deploy/start paths can observe a partially written or concurrently rewritten bridge file.

   Fix: make bootstrap a process-owned operation that UI callers await rather than own. A conservative approach is to keep an in-flight setup/restart job inside `TermuxBridgeManager`, launch it from an application/lifecycle-stable scope, and have UI/session callers await or observe it instead of cancelling the underlying operation. Also make deploy atomic (`bridge.py.tmp` + `py_compile` + rename) and/or take a Termux-side lock so duplicate RUN_COMMAND setup invocations cannot mutate packages or bridge files concurrently.

2. **Medium — `app/src/test/kotlin/ai/closepaw/session/SessionServicesTermuxSnapshotTest.kt:115` — the new idle-restart test does not test the production restart logic.**

   `RestartingTermuxSessionBridge.ensureReadyForSession()` implements its own fake restart by incrementing `restarts` and calling `healthCheck()` again. That verifies `SessionServices.captureTermuxSnapshot()` delegates to `ensureReadyForSession()`, but it would still pass if `TermuxBridgeManager.ensureReadyForSessionLocked()` stopped checking `~/.closepaw/bridge.py`, stopped calling `startBridge()`, or accidentally ran install/deploy.

   Fix: add a fakeable seam for `TermuxBridgeManager`'s RUN_COMMAND adapter and health probe, then test the real manager state machine: healthy returns Ready, missing bridge file does not install, deployed idle bridge runs only `BRIDGE_EXISTS_COMMAND` + `START_BRIDGE_COMMAND`, and the whole path is bounded.

3. **Medium — `app/src/main/kotlin/ai/closepaw/termux/TermuxRunCommandAdapter.kt:137`, `app/src/main/kotlin/ai/closepaw/termux/TermuxBridgeManager.kt:334` — foreground-service startup failures collapse into generic setup/package errors.**

   If `startForegroundService()` starts Termux but Termux's service does not return a PendingIntent result, the adapter reports `RunCommandError.Timeout`. The manager then maps that timeout to the local fallback for the current step: probe becomes `UNKNOWN`, install becomes `PACKAGES_MISSING`, start becomes `UNKNOWN`. On a slow device or FGS promotion failure, the user is likely to see "Setup error" or "Missing packages" rather than "Termux RUN_COMMAND timed out / did not respond."

   Fix: preserve timeout as its own setup reason, or at least map RUN_COMMAND timeouts to a user-visible "Termux command timed out; retry with Termux open / check RUN_COMMAND" message. Also catch Android's FGS start-not-allowed exception explicitly if target SDK behavior exposes it before the PendingIntent timeout path.

4. **Low — `app/src/main/kotlin/ai/closepaw/termux/TermuxBridgeManager.kt:100` — `startHealthPolling()` is still dead code.**

   I do not recommend wiring it as-is: `/v1/health` refreshes the bridge watchdog timer, so a 30-second health poll would keep the daemon alive forever and defeat the 30-minute idle-exit behavior. Either remove `startHealthPolling()` for v1 and rely on `ensureReadyForSession()`, or redesign health polling so passive status checks do not refresh bridge idleness.

5. **Low — `app/src/main/kotlin/ai/closepaw/termux/TermuxBridgeManager.kt:93` — `ensureReadyForSession(timeoutMs)` does not bound mutex acquisition by itself.**

   The production `SessionServices` call wraps it in an outer `withTimeoutOrNull`, so current session creation remains bounded. The manager method's own contract is still misleading: a direct caller can wait behind a long `setup()` before the internal timeout starts. Fix by moving the timeout outside `mutex.withLock`.

## Risk assessment

- The main remaining risk class is cross-scope bootstrap ownership: UI composition scope, session startup, and Termux's independent RUN_COMMAND execution do not share one cancellable operation model.
- On-device QA should explicitly cover: start setup then leave Settings mid-install/deploy; immediately re-open Settings and tap setup again; start a new agent session while setup is still running; slow Termux RUN_COMMAND response after `startForegroundService()`; and idle-exited bridge restart after 30+ minutes.
- App-update bridge version changes remain under-tested. If an old `bridge.py` exists but is idle-exited, `ensureReadyForSession()` starts it and reports `BRIDGE_OUTDATED`; it does not redeploy during session readiness. That may be acceptable for "no install without tap," but should be an explicit product decision.
- Instrumented/device coverage is still required for RUN_COMMAND permission missing, allow-external-apps disabled, port conflict, fresh Termux bootstrap, and cancellation killing long `termux_shell` commands via TCP disconnect.

## What's solid

- Manifest visibility, release localhost cleartext, and bridge resource packaging match the contract; the resource test checks byte identity against the canonical bridge script.
- The Python bridge is compact and well covered: workspace guard, env, output refs, busy 409, timeout kill, client disconnect, pidfile takeover, port conflict, and watchdog behavior all have host tests.
- `TermuxShellTool` preserves the important runtime semantics: non-zero exit and command timeout are tool successes, transport/protocol errors are failures, and OkHttp cancellation is wired to `Call.cancel()`.
- Tool exposure, prompt injection, Planner direct-shell directive, Executor timeout bump, and turn-order preservation are coherent and covered by focused JVM tests.
- The first-pass consent regression is fixed: opening Agent Behavior settings no longer starts package install or bridge setup.

# Third-Pass Branch Review — termux-shell (27 commits)

## Verdict
REQUEST_CHANGES

Local note: `git rev-list --count main..HEAD` reports 26 commits at `cac2ca64`, while the requested report title says 27 commits. I kept the requested title, but reviewed the branch as it stands locally and in PR #39 at head `cac2ca64`.

Verification run during this review:
- `./gradlew :app:testDebugUnitTest --tests 'ai.closepaw.termux.TermuxBridgeManagerTest' --tests 'ai.closepaw.session.SessionServicesTermuxSnapshotTest'` — BUILD SUCCESSFUL
- `./gradlew test` — BUILD SUCCESSFUL
- `python3 -m pytest tools/termux-bridge/tests -q` — 18 passed

## Verification of second-pass fixes

1. **In-flight Job correctness** — partially fixed, but not correct enough to merge.
   `TermuxBridgeManager.awaitInFlight()` does make a UI awaiter cancellation stop at the await boundary instead of cancelling the manager-owned job. `TermuxBridgeManagerTest.cancelled setup awaiter...` proves that same-operation case. The `Deferred` is also cleared on completion via `invokeOnCompletion`, so I do not see a stale completed `Ready` / `NeedsSetup` being returned by the next caller.

   The remaining problem is more subtle: `setup()`, `ensureReadyForSession()`, and `restart()` all share one untyped `inFlightJob`. A caller for one operation kind can receive the result of a different operation kind, and its own block never runs. That violates the explicit setup vs start-only session-readiness semantics. See finding 1.

   `managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` is acceptable for a process-owned singleton in v1. It intentionally lives until process death. If the process dies while Termux is still executing RUN_COMMAND, the app still cannot cancel that external command; that remains a device QA risk, not a new app-side leak.

2. **Atomic `bridge.py` deploy** — mostly fixed for single deploy, not fully robust for overlapping deploys.
   The deploy command writes in the same directory and then `mv`s into place, so the final replace is atomic on normal Android userdata filesystems for same-directory rename. The `&&` chain does stop before `mv` when `base64 -d` or `python3 -m py_compile` fails. `py_compile ~/.closepaw/bridge.py.tmp` can leave an unused `__pycache__/bridge.py.<tag>.pyc`, but `python3 ~/.closepaw/bridge.py` does not consume that cache path, so it is stale clutter rather than a stale-code hazard.

   The fixed temp pathname `~/.closepaw/bridge.py.tmp` is still shared by every deploy command. If two deploy RUN_COMMANDs overlap across process death/restart, a second app process, or a future path outside this singleton, one deploy can rewrite the temp file between another deploy's `py_compile` and `mv`. The rename remains atomic, but the bytes moved are not guaranteed to be the bytes just compiled. See finding 2.

3. **`TermuxBridgeDependencies` injection seam** — clean.
   The seam is internal and narrow: command runner, health probe, Termux-installed predicate, payload loader, and manager scope. It does not leak test behavior into production call sites, and the default constructor still normalizes to `applicationContext`.

4. **`TERMUX_TIMEOUT` mapping** — fixed for RUN_COMMAND timeouts.
   `RunCommandError.Timeout` now maps to `NeedsSetupReason.TERMUX_TIMEOUT` in the shared mapper, and all manager RUN_COMMAND catches go through that mapper. Health polling / bridge readiness timeouts still map to `HEALTH_TIMEOUT`, which is the right distinction.

5. **Removed `startHealthPolling`** — fixed.
   The method, job field, lock, and interval constant are gone. Remaining references are historical docs/reviews only.

6. **`ensureReadyForSession` timeout wraps mutex acquisition** — fixed only when its own block runs.
   The timeout is now outside `mutex.withLock`, which fixes ordinary mutex contention. It does not bound a direct `ensureReadyForSession(timeoutMs)` call that joins an already-active `setup()` or `restart()` through the global in-flight job, because the timeout is inside the block that is skipped. `SessionServices.captureTermuxSnapshot()` has an outer timeout, so production session creation is partially protected; the manager contract itself is still leaky because of finding 1.

## New findings

1. **High / `app/src/main/kotlin/ai/closepaw/termux/TermuxBridgeManager.kt:100`, `app/src/main/kotlin/ai/closepaw/termux/TermuxBridgeManager.kt:109`, `app/src/main/kotlin/ai/closepaw/termux/TermuxBridgeManager.kt:114`, `app/src/main/kotlin/ai/closepaw/termux/TermuxBridgeManager.kt:130` / Global in-flight `Deferred` conflates different operation kinds.**

   `setup()`, `ensureReadyForSession()`, and `restart()` all call the same `awaitInFlight()` and share the same `inFlightJob`. When any one operation is active, a later caller awaits that active `Deferred` and never executes its own block. These operations do not have the same semantics: explicit `setup()` is allowed to probe/install/deploy/start, `ensureReadyForSession()` is intentionally start-only and must not install/deploy, and `restart()` has its own fallback behavior.

   Concrete bad case: session startup begins `ensureReadyForSession()` while the bridge is missing or outdated; before it completes, the user taps the Settings row to run explicit `setup()`. The tap joins the session-readiness `Deferred` and returns `NeedsSetup(HEALTH_TIMEOUT)` or `NeedsSetup(BRIDGE_OUTDATED)` without running probe/install/deploy. The inverse also matters: a direct `ensureReadyForSession(timeoutMs)` that joins an active setup/restart is no longer bounded by its own timeout. The production `SessionServices` outer timeout mitigates that one call site, but the manager method's contract is still not true.

   The new concurrent setup test does not catch this. It proves same-operation sharing and cancellation behavior, not cross-operation semantics.

   Fix: make in-flight state operation-specific. At minimum, an explicit setup job must only be shared with other explicit setup callers; a start-only session readiness check must not satisfy setup. If `ensureReadyForSession()` wants to observe an active setup, it should do so with its own timeout and without replacing explicit setup semantics. Add tests for `ensureReadyForSession()` racing with `setup()` in both directions, including missing-bridge and bridge-outdated cases.

2. **Medium / `app/src/main/kotlin/ai/closepaw/termux/TermuxBridgeManager.kt:41` / Atomic deploy still uses one shared temp path, so overlapping deploys can move unverified bytes.**

   The current command is atomic at the final `mv`, but not atomic across the whole write-compile-replace sequence because every deploy uses `~/.closepaw/bridge.py.tmp`. If two deploy commands overlap, command A can successfully compile the temp file, command B can then truncate or replace that same temp file, and command A can still `mv` the now-different temp path into `bridge.py`.

   The process-owned in-flight job should prevent ordinary same-process duplicate setup, but the branch explicitly claims the deploy is safe even if two paths reach deploy. That claim is not true with a fixed temp name, especially across app process death while Termux continues a RUN_COMMAND.

   Fix: use a unique temp path per deploy, compile that exact path, then rename that exact path into `bridge.py`, with a `trap` cleanup. A Termux-side lock directory or `flock` equivalent would make the deploy/install/start sequence stronger, but unique temp files are the minimum for the stated atomic-deploy claim.

## Risk assessment

- The main merge blocker is still bootstrap/readiness concurrency. The previous high was reduced, but not eliminated: cancellation no longer kills the same setup job, yet cross-operation callers can still get the wrong operation's result.
- PR integration remains a real operational risk. The merge-base diff is the expected 43-file Termux scope, while raw `git diff main..HEAD --stat` shows 240 files because `main` has advanced with browser/CDP work. Final merge/rebase verification must run on the actual integration result.
- Device QA should specifically cover: start setup, leave Settings, immediately start a new session; start a new session while tapping setup from `NeedsSetup`; bridge outdated but idle-exited; process death during deploy followed by another setup; fresh Termux bootstrap; RUN_COMMAND permission missing; allow-external-apps disabled; port conflict; long `termux_shell` cancellation.
- The bridge still relies on loopback-only security by design. That is acceptable per this feature contract, but it should remain tied to the deferred shell-security work.

## What's solid

- The first-pass consent regression is fixed: passive Settings render now does `detectInstalled()` + `healthCheck()` only, with full setup behind a tap.
- The idle-exited bridge path is materially better: the real manager test covers healthy short-circuit, deployed idle restart without install/deploy, and missing bridge returning passive `NeedsSetup`.
- The Termux dependency seam is small and internal.
- `TERMUX_TIMEOUT` is now typed and user-visible for RUN_COMMAND timeouts.
- The Python bridge remains well covered for exec semantics: workspace guard, env, output caps/refs, busy 409, timeout kill, client disconnect, pidfile takeover, port conflict, and watchdog behavior.

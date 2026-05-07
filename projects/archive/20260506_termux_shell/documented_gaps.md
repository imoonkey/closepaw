# Termux Shell — Documented Gaps Audit

**Commit range reviewed**: `5046c6b9..d97ea1c2`
**Date**: 2026-05-05

## Summary

Six gaps remain undone at HEAD. None are user-facing functional defects — the runtime
contract (bridge, tool, role wiring, settings row) is fully wired. The unaddressed
items are all explicit reviewer recommendations for non-device test coverage plus one
deliberate, but unfilled, OEM-restriction workaround. No high-severity correctness or
security gap is open beyond the deferrals the design itself listed (`/v1/cancel`, token
auth, command-risk classifier, hot-reload).

## Gaps

### [Severity: medium] No JVM tests for `TermuxRunCommandAdapter` result parsing
- **Source**: `reviews/final_review.md:20` ("Coverage gaps worth landing"), `reviews/phase6_review.md:25`
- **What was planned**: Bundle-vs-top-level fields, `err=-1` success, `err=0`, positive `err`, non-empty `errmsg`, allow-external-apps text, missing `exitCode`, stale request IDs, and cancellation unregistering the receiver.
- **What landed**: Nothing. `find app/src -iname "*runcommand*"` matches only the source file at `app/src/main/kotlin/ai/closepaw/termux/TermuxRunCommandAdapter.kt`.
- **Evidence it's still open**: No `TermuxRunCommandAdapterTest.kt` anywhere under `app/src`. The branch's QA-found `err=-1` regression (commit `cdfe52a0`) is the exact class of bug the requested suite would have caught at JVM time.
- **Suggested next step**: Add `TermuxRunCommandAdapterTest` driving `Intent.toRunCommandError`/`toRunCommandResult` over a fake `BroadcastReceiver` callback covering the seven payload shapes above.

### [Severity: medium] No Compose/presenter tests for the Termux Settings row
- **Source**: `reviews/final_review.md:22`, `reviews/phase6_review.md:24`
- **What was planned**: "Initial render must probe without setup, row tap owns setup/F-Droid fallback, toggle-off renders Disabled without changing bridge state, and setup cannot re-enter from recomposition." Plus row coverage for every status/reason string.
- **What landed**: Nothing. The existing instrumentation suite `app/src/androidTest/kotlin/ai/closepaw/qa/SettingsAgentBehaviorTest.kt` has no `termux` references and was untouched in the commit range (`git log 5046c6b9..d97ea1c2 -- <that file>` is empty).
- **Evidence it's still open**: `TermuxShellSettingsRow` at `app/src/main/kotlin/ai/closepaw/ui/settings/AgentBehaviorSettingsPage.kt:86` has only static review of the consent-fix; the LaunchedEffect / row-action matrix is not asserted anywhere.
- **Suggested next step**: Extract a small presenter (or run a Compose UI test) that asserts the row's action mapping for each `TermuxBridgeStatus` and that re-composition does not call `setup()`.

### [Severity: medium] No JVM test for invalid-UTF-8 bridge output
- **Source**: `initial/eng_plan_review_codex_v5.md:147` (F8)
- **What was planned**: "Define the v1 text contract: decode in-memory snippets as UTF-8 with replacement, keep artifact files as raw bytes, include byte counts/truncation fields. Add a host test for invalid UTF-8 output."
- **What landed**: The implementation does the right thing (`bytes(self.visible).decode(errors="replace")` at `tools/termux-bridge/closepaw_bridge.py:144`), but no test guards it. `grep -i "utf" tools/termux-bridge/tests/` returns nothing.
- **Evidence it's still open**: 18 host tests cover health/exec/timeout/caps/pidfile/disconnect/watchdog (`tests/test_closepaw_bridge.py`), none feed binary or invalid-UTF-8 to a subprocess.
- **Suggested next step**: Add a one-shot pytest that runs `printf '\\xff\\xfe\\x00'` and asserts `stdout` decodes to U+FFFD-style replacement plus the artifact file holds the raw bytes.

### [Severity: medium] OEM background restriction has no in-app remediation
- **Source**: `implementation_summary.md:78-80`, `9a6def5e revert: auto-wake Termux on FGS rejection (no observed benefit)`, `final/design.md:303` (manual setup fallback "v1 does not implement")
- **What was planned**: A typed reason plus user copy. Implementation_summary admits the auto-wake attempt was reverted with no benefit; the shipped contract is "open Termux manually."
- **What landed**: `RunCommandError.TermuxProcessNotRunning` mapped to subtitle "Termux is not running. Open Termux once, then return here and tap setup." (`AgentBehaviorSettingsPage.kt:219`). No deep-link to launch Termux from the row.
- **Evidence it's still open**: The row's `rowAction` for `TERMUX_NOT_RUNNING` falls through to the generic `manager.setup()` retry — same code path as plain `NeedsSetup` — so a user on a restrictive OEM ROM has no in-app way to wake Termux. P0110 QA explicitly hit this.
- **Suggested next step**: When the typed reason is `TERMUX_NOT_RUNNING`, change the row action to launch Termux via `PackageManager.getLaunchIntentForPackage("com.termux")`, then resume setup.

### [Severity: low] `setup()` on `NotInstalled` cannot tell "no Termux" from "no RUN_COMMAND surface"
- **Source**: `6f06c64c feat(termux): detect Play-Store Termux variant + nudge to F-Droid`, `final/design.md` Settings UI ("Not Installed — tap to install →")
- **What was planned**: Distinguish missing-package from Play-build (no `RunCommandService`) and direct each to the F-Droid install page.
- **What landed**: `TermuxInstallProbe` distinguishes `NotInstalled` vs `RunCommandUnavailable`, and `NeedsSetupReason.TERMUX_RUN_COMMAND_UNAVAILABLE` has its own row action (F-Droid). Good. But the `NotInstalled` branch at `AgentBehaviorSettingsPage.kt:107` calls `manager.setup()` first and only falls back to F-Droid if the result is again `NotInstalled` — confusing for a user who tapped a row labelled "Not Installed".
- **Evidence it's still open**: `AgentBehaviorSettingsPage.kt:107-117` — the explicit "Install Termux from F-Droid" subtitle lies about what the tap does. The tap runs setup that is guaranteed to fail, then opens the install page.
- **Suggested next step**: When status is `NotInstalled`, skip the `setup()` round-trip and open the F-Droid page directly (the install probe already proved Termux is absent).

### [Severity: low] Bridge artifact filename diverges from design
- **Source**: `reviews/phase2_review.md` finding 8
- **What was planned**: Phase 2 reviewer asked for either renaming artifacts to `<call_id>_stdout` / `<call_id>_stderr` or updating the design to bless the dotted `.txt` names.
- **What landed**: Source uses `f"{self.call_id}_{self.stream}"` (`closepaw_bridge.py:133`), which now matches the design's text. Confirmed DONE — leaving here as proof we checked, not as an open gap.
- **Evidence it's still open**: N/A — addressed.
- **Suggested next step**: N/A.

## Items checked and confirmed DONE (so we don't re-flag them later)

- Phase 1 cleartext-policy gap: debug overlay narrows to `127.0.0.1` + `10.0.2.2` + `localhost` and is documented (`network_security_config.xml` debug + comment); instrumentation test asserts both debug exceptions and base-config block (`TermuxLocalhostCleartextTest.kt`).
- Phase 1 byte-identical resource test: `ClosePawBridgeResourceTest` was upgraded post-review.
- Phase 2 env / cwd / max_output_bytes / port_in_use / disconnect / watchdog: all covered by the 18 host tests (`tests/test_closepaw_bridge.py`).
- Phase 3 high findings (deploy bytes, port_in_use surfacing, cancellation state restore, RECEIVER_NOT_EXPORTED, allow-external-apps mapping, healthCheck side effects, applicationContext): all addressed; `runWithSetupState` restores prior state on `CancellationException`, deploy uses `base64 -d`, start command captures stderr, `_state` handling is consistent.
- Phase 4 OkHttp cancellation race + ordering tests: `TermuxShellToolTest` uses `EventListener.canceled(call)` to assert real cancellation; `TurnToolPolicyTest` covers `mobile_action`/`termux_shell` mixed orderings.
- Phase 5 prompt wording + negative resolve: `WorkspaceShellPromptSection.kt` matches design text including `/sdcard/Download/`; `AgentRoleResolveTest` covers excluded-tool and unavailable cases.
- Phase 6 cold-start probe: `SessionServices.captureTermuxSnapshot` calls `ensureReadyForSession(timeoutMs)` with bounded timeout; `SessionServicesTermuxSnapshotTest` covers the four key paths.
- Final-pass blockers: consent regression (no `setup()` from `LaunchedEffect`), idempotent install (`command -v` short-circuit), idle-bridge restart-only path, atomic deploy with unique `mktemp` temp file + `py_compile` + `mv`, `TERMUX_TIMEOUT` mapping, removal of `startHealthPolling`, per-operation in-flight job map (`OperationKind.Setup/EnsureReady/Restart`).
- F-Droid vs Play-build nudge (`6f06c64c`): typed `TERMUX_RUN_COMMAND_UNAVAILABLE` reason with dedicated F-Droid copy.

The known-limitations bucket in `final/design.md:704` (no `/v1/cancel`, no token auth, fixed-at-task-start, shell-only file ops) is a deliberate v1 scope decision, not a missed deliverable.

## Codex Re-review (2026-05-05)

| Gap title | Status | Worth-it verdict | T-shirt size | One-line rationale |
| --- | --- | --- | --- | --- |
| No JVM tests for `TermuxRunCommandAdapter` result parsing | STILL OPEN | WORTH IT | S | Parser logic is real at `app/src/main/kotlin/ai/closepaw/termux/TermuxRunCommandAdapter.kt:200` and `:221`, but no adapter test remains; `c4dc3839` added `TermuxRunCommandAdapterTest.kt`, then `9a6def5e` deleted it. |
| No Compose/presenter tests for the Termux Settings row | STILL OPEN | WORTH IT | M | `TermuxShellSettingsRow` still owns probe/action/toggle behavior at `app/src/main/kotlin/ai/closepaw/ui/settings/AgentBehaviorSettingsPage.kt:86`, `:95`, `:105`, while `SettingsAgentBehaviorTest.kt:24` only covers executor dropdowns; row landed in `8425650a` with no row test follow-up. |
| No JVM test for invalid-UTF-8 bridge output | PARTIALLY DONE | WORTH IT | XS | Runtime behavior exists: raw artifact bytes at `tools/termux-bridge/closepaw_bridge.py:133` and replacement decode at `:143`; tests from `98dcf157`/`78f5957c` cover caps at `tools/termux-bridge/tests/test_closepaw_bridge.py:98`, but no invalid-UTF fixture. |
| OEM background restriction has no in-app remediation | STILL OPEN | WORTH IT | S | Typed `TERMUX_NOT_RUNNING` exists (`TermuxBridgeStatus.kt:23`, mapped at `TermuxBridgeManager.kt:322`) and copy says open Termux (`AgentBehaviorSettingsPage.kt:219`), but row action still retries `manager.setup()` at `AgentBehaviorSettingsPage.kt:118`; auto-wake `c4dc3839` was reverted by `9a6def5e`. |
| `setup()` on `NotInstalled` cannot tell "no Termux" from "no RUN_COMMAND surface" | PARTIALLY DONE | NICE-TO-HAVE | XS | Manager/probe distinction is solved (`TermuxBridgeDependencies.kt:60`, `TermuxBridgeManager.kt:258`, commit `6f06c64c`), but the `NotInstalled` row still calls `manager.setup()` before F-Droid at `AgentBehaviorSettingsPage.kt:107`. |
| Bridge artifact filename diverges from design | NOT-A-GAP | SKIP | XS | HEAD already uses `<call_id>_stdout/_stderr` at `tools/termux-bridge/closepaw_bridge.py:133`; design agrees at `projects/active/termux_shell/final/design.md:196`, tests assert it at `test_closepaw_bridge.py:113` and `:135`, fixed by `78f5957c`. |

### Recommended next batch

1. Add `TermuxRunCommandAdapter` result-parsing JVM tests.
2. Add the invalid-UTF-8 bridge pytest.
3. Add a `TERMUX_NOT_RUNNING` row action that launches Termux, then lets the user retry setup.
4. Change `NotInstalled` row tap to open F-Droid directly.

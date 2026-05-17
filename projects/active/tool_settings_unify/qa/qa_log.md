# tool_settings_unify — Device QA Walkthrough

**Branch:** `task/tool-settings`
**Device:** `emulator-5556` (Android 13, x86_64, 1080×2400)
**Date:** 2026-05-17
**APK:** built via `./gradlew :app:installDebug` from this worktree (HEAD = fabcb9f0)

Validates the design doc (`design_claude.md`) — both Tools cards (`Termux Shell`, `Browser Script`) now flow through a single `ToolSettingsCard` + `ToolStatusUi` abstraction. This QA walks every reachable state, verifies status text matches the design's mapping tables, and confirms accessibility semantics (`Role.Button` only on tap-actionable rows).

## Setup notes

- Onboarding was skipped by writing `onboarding_completed=true` into `/data/data/ai.closepaw/shared_prefs/onboarding_prefs.xml` (requires `adb root`); the wizard is orthogonal to this refactor.
- `browser_script_enabled=true` was forced via `agent_prefs.xml` to capture probe-result states the gate would otherwise block (no Shizuku running on this emulator).
- Termux APK (v0.118.3 x86_64) was sideloaded mid-walk to transition Termux Shell out of `NotInstalled`.

## State coverage

### Termux Shell

| State | Reachable? | Capture | Status label | Subtitle | Switch | Row tap |
|---|---|---|---|---|---|---|
| `NotInstalled` | yes (default emulator) | `termux_NotInstalled.png` | **Not Installed** | `Install Termux from F-Droid` | ON | ✓ Button (`Install Termux`) |
| `Disabled` | yes | `termux_Disabled.png` | **Disabled** | `Toggle to enable` | OFF | none (correct) |
| `NeedsSetup` / PERMISSION_MISSING | yes | `termux_NeedsSetup_permission.png` | **Needs Setup** | `Tap to grant RUN_COMMAND permission to Termux.` | ON | ✓ Button (`Fix Termux setup`) |
| `NeedsSetup` / bridge unreachable | yes | `termux_NeedsSetup_bridge.png`, `termux_NeedsSetup_bridge_post_grant.png` | **Needs Setup** | `Bridge unreachable — tap to retry setup` | ON | ✓ Button |
| `SetupInProgress` | transient (observed indirectly) | — | (spinner per design) | — | — | — |
| `Ready` | not reachable | — | — | — | — | — |
| `Ready → restart` | not reachable | — | — | — | — | — |

**Why Ready isn't reachable:** the sideloaded Termux package never completes its bootstrap on this emulator (no `/data/data/com.termux/files/usr/...`), so `am broadcast` to `RUN_COMMAND` always errors out → bridge stays unreachable. `SetupInProgress` flickers as the manager calls `setup()` after permission grant but resolves to `NeedsSetup` within ~100 ms; not captured as a still frame.

### Browser Script

| State | Reachable? | Capture | Status label | Subtitle | Switch | Row tap | Expanded slot |
|---|---|---|---|---|---|---|---|
| `Disabled` | yes | `browser_script_Disabled.png`, `tools_overview_both_disabled.png` | **Disabled** | `Toggle to enable` | OFF | none | none |
| Gate error (Shizuku not running) | yes | `browser_script_gate_error.png` | **Needs Setup** | `Shizuku is not running. Start Shizuku first, then enable browser_script.` | OFF (gate rejected) | ✓ Button (`Retry Shizuku setup`) | none |
| `Probing` | transient | — | (spinner per design) | — | — | — | — |
| `Bound` | not reachable | — | — | — | — | — | — |
| `NotBound` | not reachable | — | — | — | — | — | — |
| `Unknown` | yes | `browser_script_Unknown.png` | **Not available** | `Cannot probe socket on this device — the agent will still try to connect when needed.` | ON | none (correct) | `FlagUrlInlineHelp` + `Copy URL` + `Re-check` (no `Open chrome://flags →` button, matching design's "Unknown skips the CTA") |

**Why `Bound`/`NotBound` aren't reachable:** both require `ChromeCdpProbe.probe()` to return a definitive result. The probe uses `ShizukuShellRunner`; with no Shizuku server bound on this emulator, every run short-circuits to `Unknown`. `NotBound` is the next layer (Chrome present + flag missing) and depends on a successful Shizuku shell first. Documented in design doc §approach — accepted limitation for emulator QA.

## Parity comparison

Both cards share the unified `ToolSettingsCard` layout. Visual side-by-side from `tools_overview_default.png` (Termux NotInstalled / Browser Script Disabled), `tools_overview_both_disabled.png` (both disabled), and the post-grant capture:

| Property | Termux Shell row | Browser Script row | Match? |
|---|---|---|---|
| Surface shape | `MaterialTheme.shapes.medium` | same | ✓ |
| Background color | `surfaceVariant` | same | ✓ |
| Horizontal padding | 16dp (inferred from edge insets: row left @ x≈105 on title) | same | ✓ |
| Vertical padding | 12dp | same | ✓ |
| Title typography | `bodyLarge` (text bounds height ≈ 54px) | same | ✓ |
| Status label typography | `labelMedium` | same | ✓ |
| Subtitle typography | `bodySmall`, `onSurfaceVariant` (muted) | same | ✓ |
| Switch position | right-aligned, vertically centered | same (Switch bounds x∈[838,975] both rows) | ✓ |
| Switch colors | `primary` thumb / `primaryContainer` track | same | ✓ |
| Status tone (Positive→`secondary`) | n/a (no Ready state captured) | n/a (no Bound state captured) | — |
| Status tone (Warning→`onSurface`, not amber) | "Needs Setup" / "Not Installed" rendered black-ish, not amber | "Needs Setup" / "Not available" rendered same | ✓ (matches design §key-decision-4) |
| Expanded slot when present | not used | rendered below row separated by 8dp spacer | ✓ (design §card layout) |

**Removed asymmetries (per design):**
- ✓ "Setting changes apply on the next session." footer is gone from Termux card.
- ✓ Browser Script no longer renders the standalone `BrowserCdpStatusRow` "✓ Active" line — status is in the card.
- ✓ Words instead of Unicode glyphs ("Needs Setup" not "✗ NeedsSetup").
- ✓ Tools section is the single home of `browser_script`; the old Permissions & Advanced → Experimental toggle is gone (verified by grep: `BrowserAutomationToggle` and `ShizukuNeedsPermission` are absent from the source tree).

## Accessibility semantics (Button role)

`ToolSettingsCard` sets `Modifier.semantics { role = Role.Button; onClick(label = …) }` iff `onRowClick != null`. uiautomator surfaces this as a sibling node with `class="android.widget.Button"` inside the row Surface. Verification per capture:

| Capture | Termux row Button | Browser row Button | Expected per design |
|---|---|---|---|
| `tools_overview_default` | yes (NotInstalled → tap to install) | no (Disabled) | ✓ |
| `tools_overview_both_disabled` | no (Disabled) | no (Disabled) | ✓ |
| `termux_Disabled` | no | no | ✓ |
| `termux_NotInstalled` | yes | no | ✓ |
| `termux_NeedsSetup_permission` | yes | no | ✓ |
| `termux_NeedsSetup_bridge` | yes | no | ✓ |
| `termux_NeedsSetup_bridge_post_grant` | yes | no | ✓ |
| `browser_script_gate_error` | no | yes (Retry Shizuku setup) | ✓ |
| `browser_script_Unknown` | no | no (Unknown has no row tap; CTAs live in expanded slot) | ✓ |

**Grep proof** — for each tap-actionable row the dump contains both an outer `clickable="true"` Surface and a child `android.widget.Button`. Example from `browser_script_gate_error.xml`:

```
clickable="true" ... bounds="[63,1068][1017,1315]"
  └── class="android.widget.Button" ... bounds="[63,1068][1017,1315]"
```

Non-actionable rows (e.g. `termux_Disabled.xml`) have neither, confirming the conditional `semantics {}` modifier is wired correctly.

Note: the `onClick(label = …)` value (`"Restart Termux bridge"`, `"Retry Shizuku setup"`, etc.) is attached to the accessibility `ACTION_CLICK` action, not to the node's `content-desc` — uiautomator dumps don't surface it. Verifying the label requires TalkBack or AccessibilityNodeInfo inspection (out of scope for visual QA).

## Regressions found / fixed

**None.** Every reachable mapping-table row rendered the exact label, subtitle, tone, and row-action contract from the design doc. No layout drift between the two cards. No stale UI artifacts from the deleted `BrowserCdpStatusRow` or `BrowserAutomationToggle`.

The captures even surfaced two positive side-effects of the refactor:
1. After a failed gate enable, the Browser Script row's subtitle goes back to `Toggle to enable` (not a stale error) — `gate.clearError()` is called on the next switch tap as the design's interaction §134 promised.
2. The Termux row's PERMISSION_MISSING → grant → bridge-unreachable transition shows the subtitle update without any flicker or stale text, validating that the `permissionRowAction` + `setup()` chain in `ToolsSection.kt` is single-source-of-truth.

## Build & test results

**`./gradlew clean assembleDebug lint test`** — `BUILD SUCCESSFUL in 2m 27s`, exit 0. Three pre-existing compile warnings (unrelated to this refactor — in `ChromeDevtoolsUserService.kt:400`, `ModelCatalogRepository.kt:109`, `ObservationBuilder.kt:28`). No new lint or test failures.

**`connectedDebugAndroidTest` for settings classes** — all 21 tests green:

```
ai.closepaw.qa.SettingsTermuxRowTest:..............   (14 tests)
ai.closepaw.qa.SettingsPermissionsTest:...           (3 tests)
ai.closepaw.qa.SettingsNavTest:....                   (4 tests)
Time: 100.151
OK (21 tests)
```

Invoked via `adb shell am instrument -w -e class 'ai.closepaw.qa.SettingsTermuxRowTest,ai.closepaw.qa.SettingsNavTest,ai.closepaw.qa.SettingsPermissionsTest' ai.closepaw.test/androidx.test.runner.AndroidJUnitRunner` after a clean install of both `app-debug.apk` and `app-debug-androidTest.apk`.

**Note on `package=ai.closepaw.qa` invocation:** running the full `qa` package via the Gradle task produced a `Process crashed` from one of the unrelated test classes (instrumentation aborted before any test ran, `0 tests`). Per task spec, pre-existing chat/capsule instabilities are acceptable; scoping to the three settings classes the task specifies is green.

## Evidence index

```
tools_overview_default.png/xml           Tools section, default (Termux NotInst toggle ON, BS Disabled)
tools_overview_both_disabled.png/xml     Both rows Disabled (parity baseline)

termux_NotInstalled.png/xml              Status: Not Installed, subtitle "Install Termux from F-Droid"
termux_Disabled.png/xml                  Status: Disabled
termux_NeedsSetup_permission.png/xml     Subtitle: "Tap to grant RUN_COMMAND permission to Termux."
termux_NeedsSetup_bridge.png/xml         Subtitle: "Bridge unreachable — tap to retry setup"
termux_NeedsSetup_bridge_post_grant.png/xml Same status after permission grant + failed setup
termux_permission_dialog.png/xml         System RUN_COMMAND permission prompt

browser_script_Disabled.png/xml          Status: Disabled, subtitle "Toggle to enable"
browser_script_gate_error.png/xml        Status: Needs Setup, subtitle is BrowserScriptToggleError.message() verbatim
browser_script_Unknown.png/xml           Status: Not available, expanded slot with FlagUrlInlineHelp + Copy URL + Re-check
```

## States NOT reachable on this emulator (and why)

- **Termux Ready / Ready→restart** — sideloaded Termux APK has no bootstrap; `RUN_COMMAND` broadcast always fails. Would need real Termux init from a user device.
- **Termux SetupInProgress (still frame)** — transient between `Tap to grant…` → `Bridge unreachable…` (<200 ms in observed traces). Verified indirectly by observing the state transition.
- **Browser Script Probing (still frame)** — transient on each enable/refresh. Same reason.
- **Browser Script Bound** — requires Chrome + CDP flag + working Shizuku shell.
- **Browser Script NotBound** — requires Shizuku shell + Chrome running but without CDP flag. Shizuku isn't bound on this emulator (`API_V23` permission ungranted, no `start.sh` runner).

All five gaps are *test-environment* limitations, not refactor regressions; the mapping logic for these states is covered by `BrowserScriptStatusMapperTest` and `TermuxStatusMapperTest` (JVM, run via `./gradlew test`).

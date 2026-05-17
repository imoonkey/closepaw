# Tool Settings Card Unification

## Goal

Two tool toggles in Settings — **Termux Shell** and **browser_script** — currently render with visibly different cards (different shape, status conventions, typography, copy style). `browser_script` is also rendered **twice** (Agent Behavior → Tools *and* Permissions & Advanced → Experimental) with different titles and descriptions.

Outcome: one card component, one status-display convention, one entry point per tool. The settings sheet should look like it was built by the same person.

Out of scope: changing the underlying gate logic (`BrowserScriptToggleGate`, `RunCommandPermissionGate`), changing Termux state machine semantics, redesigning the Tools section's information architecture.

## Approach

**B. Extract `ToolSettingsCard` + `ToolStatusUi`.** Each tool row maps its domain state (TermuxBridgeStatus / CDP probe result + gate error) into a single `ToolStatusUi` and hands the card a switch + optional expanded slot. The card owns shape, padding, typography, status-label tone, spinner placement, and row-tap wiring.

**C. Single entry point per tool.** Delete `BrowserAutomationToggle` from `PermissionsAdvancedSettingsPage`. Agent Behavior → Tools becomes the only place to toggle `browser_script`, matching `termux_shell`'s only home.

### Key decisions

1. **Status is a value, not a layout.** Today, Termux encodes status as label+subtitle text; browser_script encodes it as a separate `BrowserCdpStatusRow` with ✓/✗/? glyphs and a different typography ladder. The unified model is a `ToolStatusUi` data class with `{label, subtitle, tone, showSpinner}`. The card renders it once, the same way, for every tool. No Unicode glyphs — words only (`Ready`, `Needs Setup`, `Not available`, `Setting up…`, `Disabled`).

2. **Gate error collapses into status — but keeps the specific message.** browser_script's `BrowserScriptToggleError` becomes a `NeedsSetup`-toned state where the **status label** is generic ("Needs Setup") and the **subtitle is the exact `BrowserScriptToggleError.message()`** ("Shizuku is not running. Start Shizuku first…", "Could not write Chrome's command-line file…", etc.). No information is lost — the user still distinguishes "go install Shizuku" from "permission denied, tap to retry" via the subtitle. No separate red-text overlay needed.

3. **NotBound keeps its explicit CTA button.** Earlier draft proposed making row-tap fire the chrome://flags deep link. Reverted: the row tap is a hidden affordance, the existing `Open chrome://flags →` button is the discoverable one, and accessibility benefits from a labeled `Button` over a `clickable` `Surface`. Card row tap is reserved for retry/restart (Termux row's `restart()`, browser row's "retry gate" after error). The CTA button + `FlagUrlInlineHelp` + Re-check stay in the expanded slot.

4. **Shape & typography normalized to Termux's pattern.** `MaterialTheme.shapes.medium` (drop hardcoded `RoundedCornerShape(12.dp)`). Title in `bodyLarge`, status label in `labelMedium`, subtitle in `bodySmall` onSurfaceVariant. Section heads keep `Fraunces` via `SettingsSection`. No icon on either card (today only one has one; removing it is the smaller diff and keeps the rows visually anchored on text).

   **Warning tone color: `onSurface`, not `tertiary`.** `SettingsWidgets.kt:232–234` already documents that amber (`tertiary`) on `surfaceVariant` is 1.77:1 contrast — hard fail. That file's `SettingsRow` keeps amber only as the small dot fill and uses `onSurface` for the label. Mirror that: `Warning` tone → label color `onSurface`, with an optional 8dp leading dot in `tertiary` if we want a visual cue. v1: no dot (simpler), `onSurface` label.

5. **Drop the "Setting changes apply on the next session." hint.** It's on Termux only, right-aligned at the bottom, and only true in some flows. If we want this universally we'd add it to the section, not the card — but for v1 just remove it.

6. **One entry point — but only for the toggle.** `PermissionsAdvancedSettingsPage` itself stays (it still owns accessibility, overlay, platform mode, debug, trace/data flows). Only the `BrowserAutomationToggle` composable and its `Experimental` section header are deleted. Tools section already gates `browser_script` through `rememberBrowserScriptToggleGate`. The Experimental-section duplicate adds nothing functionally and confuses users about where the canonical toggle lives. Update `BrowserScriptToggleGate.kt`'s top-of-file comment which currently says both surfaces share state.

7. **`ToolStatusUi` is display-only; actions live on the card, not in the status.** The status value carries label/subtitle/tone/spinner — nothing executable. Row tap actions are passed as `onRowClick` to `ToolSettingsCard`, derived per-row from the same domain state. This keeps the abstraction honest and lets a row have a status without an action (e.g. `Disabled`, `Setting up…`).

8. **Accessibility semantics.** When `onRowClick != null`, the card sets `Modifier.semantics { role = Role.Button; onClick(label = …) { … } }` so TalkBack announces it as a button with a meaningful label (e.g. "Restart Termux bridge", "Retry Shizuku setup"). Without this the current `clickable` Surface reads as a generic tap target. The Switch already has correct semantics from Material3.

9. **Remove `ShizukuNeedsPermission` dead variant.** Verified unreachable: `gateBrowserScriptEnable` only returns `Granted`, `Denied`, or `Error` from `requestShizukuPermission()`. Permission-missing always flows through one of those three. The unreachable variant in `BrowserScriptToggleError` is dead code that confuses future readers and inflates the mapping table. Delete from sealed interface + `message()`.

## Components

### New

- **`ToolSettingsCard.kt`** (`app/src/main/kotlin/ai/closepaw/ui/settings/`) — Composable card + `ToolStatusUi`/`ToolStatusTone` types. Internal to the `ui.settings` package.

### Modified

- **`ToolsSection.kt`**
  - `TermuxShellSettingsRow` rewritten to compute a `ToolStatusUi` from `TermuxBridgeStatus` + `RunCommandPermissionDisposition`, hand it to `ToolSettingsCard`. State-machine logic (which action does the row tap fire) stays in this file — just no longer interleaved with layout.
  - `BrowserScriptToolRow` rewritten the same way: gate error + CDP probe result → `ToolStatusUi`; `BrowserCdpStatusRow` collapses into the card's expanded slot (only the chrome://flags help block and Re-check button survive; the duplicated status line goes away because the card already shows the status).
  - Internal helpers (`TermuxBridgeStatus.label`, `.subtitle`, `.subtitleFor`, `permissionRowAction`, `NeedsSetupReason.toDisplayText`) stay, retargeted as inputs to the new mapping function.

- **`PermissionsAdvancedSettingsPage.kt`**
  - Delete `BrowserAutomationToggle` and its `Experimental` section.
  - Remove `browserScriptEnabled` and `onBrowserScriptEnabledChange` from the function signature.

- **`SettingsSheet.kt`** — drop the two args from the `PermissionsAdvancedSettingsPage` call at lines 173–174. Tools section's call (lines 156–157) is unchanged.

- **`BrowserScriptToggleGate.kt`** — update the file-level comment (lines 22–28) to reflect a single entry point (Agent Behavior → Tools). The gate code itself doesn't change.

### Removed

- `BrowserAutomationToggle` composable in `PermissionsAdvancedSettingsPage.kt`.
- `BrowserCdpStatusRow`'s standalone status line `StatusLine(label = "✓ Active")` etc. (the card subsumes it). The CTA button + URL helper move into the expanded slot.
- The right-aligned "Setting changes apply on the next session." footer on the Termux card.

### Untouched

- `RunCommandPermissionGate.kt`, `BrowserScriptToggleGate.kt` (logic), `TermuxBridgeStatus`, `TermuxBridgeManager`, `AppSettingsStore`, `MainActivityIntentApplier`, all tool execution paths.

## Interactions

### `ToolSettingsCard` API

```kotlin
internal data class ToolStatusUi(
    val label: String,            // "Ready" / "Needs Setup" / "Setting up…" / "Not available" / "Disabled" / "Not Installed"
    val subtitle: String,         // action-oriented one-liner
    val tone: ToolStatusTone,     // Positive | Neutral | Warning
    val showSpinner: Boolean = false,
)

internal enum class ToolStatusTone { Positive, Neutral, Warning }

@Composable
internal fun ToolSettingsCard(
    title: String,
    status: ToolStatusUi,
    switchChecked: Boolean,
    onSwitchChange: (Boolean) -> Unit,
    onRowClick: (() -> Unit)? = null,        // null = non-interactive surface
    onRowClickLabel: String? = null,         // TalkBack action label, required when onRowClick != null
    switchEnabled: Boolean = true,
    expanded: (@Composable ColumnScope.() -> Unit)? = null,
)
```

Layout (one canonical shape for both rows):

```
┌──────────────────────────────────────────────────────────┐
│  Title                       [optional spinner]  [Switch] │   bodyLarge / 20dp progress / Switch
│  Status label                                             │   labelMedium, tone-colored
│  subtitle                                                 │   bodySmall, onSurfaceVariant
│  ─ expanded slot (optional) ─                             │
│  [chrome flags help, buttons, etc.]                       │
└──────────────────────────────────────────────────────────┘
```

Tone → color:
- `Positive` → `colorScheme.secondary` (matches the existing "Enabled" affordance in `SettingsRow`)
- `Neutral` → `colorScheme.onSurface`
- `Warning` → `colorScheme.onSurface` (NOT `tertiary` — see `SettingsWidgets.kt:232–234`; amber on surfaceVariant is 1.77:1 hard fail. Tone differentiation in v1 comes from the label *word* ("Needs Setup" / "Not Installed" / "Not available"), not color. If we later want a visual cue, add a small leading dot tinted `tertiary` à la `SettingsRow`.)

### State → ToolStatusUi mapping (Termux)

| `TermuxBridgeStatus` | label | tone | spinner | onRowClick | onRowClickLabel |
|---|---|---|---|---|---|
| `Disabled` (toggle off) | "Disabled" | Neutral | – | – | – |
| `NotInstalled` | "Not Installed" | Warning | – | open F-Droid | "Install Termux" |
| `NeedsSetup(reason)` | "Needs Setup" | Warning | – | reason-specific (existing logic) | reason-specific ("Grant permission" / "Open Termux" / "Retry setup") |
| `SetupInProgress` | "Setting up…" | Neutral | yes | – | – |
| `Ready` | "Ready" | Positive | – | `restart()` | "Restart Termux bridge" |

Subtitle text comes from existing `NeedsSetupReason.toDisplayText()` / `subtitleFor(permissionDisposition)` unchanged.

### State → ToolStatusUi mapping (browser_script)

Sources: `BrowserScriptToggleGate.error`, `BrowserScriptToggleGate.pending`, CDP probe result, `enabled` pref.

| Condition | label | subtitle source | tone | spinner | onRowClick | expanded |
|---|---|---|---|---|---|---|
| Off, no error | "Disabled" | "Toggle to enable" | Neutral | – | – | – |
| Pending (gate running) | "Setting up…" | "Checking Shizuku…" | Neutral | yes | – | – |
| Gate error (any variant) | "Needs Setup" | `BrowserScriptToggleError.message()` verbatim | Warning | – | `gate.clearError(); gate.setEnabled(true)` (mirrors the Switch's tap contract) | – |
| On, probe `Probing` | "Checking…" | "Checking Chrome devtools socket…" | Neutral | yes | – | – |
| On, probe `Bound` | "Ready" | "browser_script active" | Positive | – | – | – |
| On, probe `NotBound` | "Needs Setup" | "Chrome devtools socket not exposed. Enable the flag and restart Chrome." | Warning | – | – | `Open chrome://flags →` Button + `FlagUrlInlineHelp` + `Re-check` |
| On, probe `Unknown` | "Not available" | "Cannot probe socket on this device — the agent will still try to connect when needed." | Warning | – | – | `FlagUrlInlineHelp` + `Re-check` |

NotBound's CTA stays an explicit `Button` (not row tap) for discoverability and accessibility. Row tap is reserved for retry/restart paths above.

### Entry point consolidation

Before: two routes write `browserScriptEnabled` from the UI (Tools section, Experimental section). Both go through the gate, but users see two switches and may toggle them oppositely (last write wins; the inactive surface goes stale until recompose).

After: one route. Tools section owns the toggle. Permissions & Advanced no longer references the pref. `MainActivityIntentApplier`'s programmatic path (deep links) is unchanged — it already uses `gateBrowserScriptEnable` directly, not the UI.

## Tasks

- **task-1: extract-tool-settings-card**
  - scope: `app/src/main/kotlin/ai/closepaw/ui/settings/ToolSettingsCard.kt` (new)
  - acceptance: `ToolSettingsCard`, `ToolStatusUi`, `ToolStatusTone` exist; package-internal; renders title + status label + subtitle + optional spinner + Switch + optional expanded slot; row Surface is clickable iff `onRowClick != null` AND sets `semantics { role = Role.Button; onClick(label = onRowClickLabel!!) { … } }`; shape = `MaterialTheme.shapes.medium`; tone→color uses `onSurface` for Warning labels (not `tertiary`).
  - deps: none

- **task-2: extract-status-mappers**
  - scope: `app/src/main/kotlin/ai/closepaw/ui/settings/TermuxStatusMapper.kt` (new), `BrowserScriptStatusMapper.kt` (new)
  - acceptance: pure functions `termuxStatusUi(state, permissionDisposition, enabledPref): ToolStatusUi` and `browserScriptStatusUi(enabledPref, gatePending, gateError, probeResult): Pair<ToolStatusUi, RowAction?>` extracted out of the composables; deterministic, no Compose dependency; unit-testable.
  - deps: task-1

- **task-3: unit-test-status-mappers**
  - scope: `app/src/test/kotlin/ai/closepaw/ui/settings/TermuxStatusMapperTest.kt` (new), `BrowserScriptStatusMapperTest.kt` (new)
  - acceptance: every row of both mapping tables in this design has a passing test asserting label/tone/spinner/row-action presence; tests run under `./gradlew test` (pure JVM).
  - deps: task-2

- **task-4: migrate-termux-row**
  - scope: `app/src/main/kotlin/ai/closepaw/ui/settings/ToolsSection.kt`
  - acceptance: `TermuxShellSettingsRow` uses `ToolSettingsCard` + `termuxStatusUi`; status labels remain exactly `"Ready" / "Needs Setup" / "Setting up…" / "Not Installed" / "Disabled"` (so `SettingsTermuxRowTest` passes); existing tap-action state machine preserved; right-aligned "Setting changes apply…" footer removed.
  - deps: task-2
  - verify: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.closepaw.qa.SettingsTermuxRowTest`

- **task-5: migrate-browser-script-row**
  - scope: `app/src/main/kotlin/ai/closepaw/ui/settings/ToolsSection.kt`
  - acceptance: `BrowserScriptToolRow` uses `ToolSettingsCard` + `browserScriptStatusUi`; title becomes `"Browser Script"` (Title Case; tool ID `browser_script` stays in code/policy/traces); subtitle for gate errors is `BrowserScriptToggleError.message()` verbatim (no information loss); `BrowserCdpStatusRow`'s status line gone (subsumed by card); chrome://flags CTA `Button`, `FlagUrlInlineHelp`, and Re-check button live in the expanded slot (NOT row tap); CDP probe lifecycle (`LaunchedEffect(refreshTick)`) preserved.
  - deps: task-2

- **task-6: drop-duplicate-entry-and-dead-error**
  - scope: `app/src/main/kotlin/ai/closepaw/ui/settings/PermissionsAdvancedSettingsPage.kt`, `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsSheet.kt`, `app/src/main/kotlin/ai/closepaw/ui/settings/BrowserScriptToggleGate.kt`, `app/src/test/kotlin/ai/closepaw/ui/settings/BrowserScriptToggleGateTest.kt`
  - acceptance: `BrowserAutomationToggle` + `Experimental` section deleted; `PermissionsAdvancedSettingsPage` signature no longer takes `browserScriptEnabled` / `onBrowserScriptEnabledChange`; `SettingsSheet` call site updated; rest of the page (accessibility, overlay, platform mode, debug, trace/data) untouched; `BrowserScriptToggleError.ShizukuNeedsPermission` variant + its `message()` arm removed (dead code); `BrowserScriptToggleGate.kt` top-of-file comment updated to reflect single UI surface; stale comments in `BrowserScriptToggleGateTest.kt` (line ~13) updated; `grep -r "BrowserAutomationToggle\|ShizukuNeedsPermission"` returns empty.
  - deps: none (can run parallel with task-1..5)

- **task-7: fix-stale-test-helpers**
  - scope: `app/src/androidTest/kotlin/ai/closepaw/qa/QaSettingsHelpers.kt`, `app/src/androidTest/kotlin/ai/closepaw/qa/SettingsNavTest.kt`
  - acceptance: `SettingsNavTest` no longer asserts on the obsolete "Max Turns" marker for the Agent Behavior sub-page (lines 37, 73–84) — replace with `"Tools"` or `"Termux Shell"` which are stable post-refactor; `QaSettingsHelpers.kt:112` and any other helper that still references the deleted Experimental toggle is updated.
  - deps: task-4, task-5, task-6 (because the new stable markers depend on those landing)

- **task-8: update-docs**
  - scope: `doc/dev/development.md`, `doc/main/app/settings.md`, `doc/main/infra/browser.md`
  - acceptance: references to the old `Permissions & Advanced → Experimental → Browser automation` location (development.md:155, settings.md:150, browser.md:153) updated to `Agent Behavior → Tools → Browser Script`; no remaining references to the removed location (`grep -rn "Browser automation\|Experimental.*browser_script"` clean for `doc/`).
  - deps: task-6

- **task-9: verify**
  - scope: full build + targeted tests
  - acceptance: `./gradlew clean assembleDebug lint test` green; `connectedDebugAndroidTest` for `SettingsTermuxRowTest`, `SettingsNavTest`, `SettingsPermissionsTest` all green; `BrowserScriptToggleGateTest` (JVM) green; manual visual check that both cards in the Tools section look identical (same shape, same status typography, same switch placement); manual TalkBack pass on Termux "Restart" / `NotBound` "Open chrome://flags" semantics.
  - deps: task-1..8

## Trade-offs

**A. Skinny refactor (visual-only alignment).** Just change `RoundedCornerShape(12.dp)` → `MaterialTheme.shapes.medium`, normalize title casing, leave two separate composables. *Rejected:* doesn't address the deeper inconsistency (status conventions, glyphs vs. words, error-overlay placement), and leaves the duplicate entry. We'd be back here in a month.

**B+C (chosen).** Card extraction + single entry. Right size: the abstraction is one struct + one composable, no class hierarchy, no theme indirection. Both call sites become short and obviously parallel. The gate logic stays unchanged so the risk surface is purely UI.

**D. Full settings DSL rewrite.** Define a declarative DSL for every settings row across the app. *Rejected:* the rest of the settings sheet is fine; this would be a multi-week refactor for marginal gain.

**Why drop the "next session" footer instead of adding it to browser_script.** It's an asymmetric truth: Termux's bridge takes effect on the next agent run, but browser_script's enable flips the tool in the next session too. Both are true. Either both rows should have the hint or neither should. For v1 we drop it; if users ask, we add it as a section-level note in Tools — one line, not per-card.

**Why no icon on the cards.** Today only `BrowserAutomationToggle` (the one we're deleting) has an icon. The surviving rows have none. Adding icons would mean choosing two — extra design work and arguable value when the title already speaks. Punt unless asked.

**Why Title Case for `browser_script` → "Browser Script" in the UI.** Consistent with "Termux Shell." The snake_case `browser_script` is the tool's API name; that's what shows up in agent traces and the policy file. UI labels speak human.

## Notes for `/update-tasks`

The Tasks section above is ready to feed the task graph. After approval, run `/update-tasks` against this doc, then `/implement` (or `/orchestrate` if parallelizing task-1+task-4).

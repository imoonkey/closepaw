# QA Walk-through — Settings Display Mode + Perception Refresh

**Device**: P0110 (`100.64.43.95:5555`) — Android 16, 1264×2800@560dpi, ColorOS (Obric).
**APK**: `:app:installDebug` from worktree `display-perception` (head: `be3df59d`).
**Date**: 2026-05-17.
**Tooling**: ADB `input tap` + `uiautomator dump`. Onboarding bypassed by writing `onboarding_completed=true` into `shared_prefs/onboarding_prefs.xml` via `run-as` (OEM resets `enabled_accessibility_services` on `force-stop`; bypass keeps Settings reachable without going through the full 5-step wizard repeatedly).

## States captured

| Dir | State | Persisted | Shizuku | gateError | Expected row | Match? |
|---|---|---|---|---|---|---|
| `01_home_initial` | Pre-onboarding accessibility prompt (Step 1 of 5) | — | — | — | n/a | n/a — pre-app, baseline only |
| `02_settings_home_transcript` | Settings sheet root, Agent Behavior subtitle = `Transcript` | ACCESSIBILITY | — | none | n/a — Settings home | ✓ subtitle reflects perception only when VD off |
| `03_display_disabled_row4` | Agent Behavior, Display Mode `Disabled` / "Agent runs on your current screen.", switch unchecked | ACCESSIBILITY | Ready (idle) | none | Row 4 | ✓ exact copy |
| `04_shizuku_perm_dialog` | Shizuku permission dialog (`要允许ClosePaw使用Shizuku吗？`) over Display Mode card showing `Setting up… / Checking Shizuku…` | (pending VD) | NeedsPermission | pending | n/a — transient | ✓ subtitle correctly shows transient `Setting up…` |
| `05_display_perm_denied_row3` | After tapping `拒绝`: `Needs Setup` / "Permission denied. Tap to retry, or grant in Shizuku Manager.", switch unchecked | ACCESSIBILITY | (denied) | ShizukuPermissionDenied | Row 3 | ✓ exact copy |
| `06_display_ready_row12` | After tapping toggle again + `始终允许`: `Ready` / "Will start on a virtual display.", switch checked | VIRTUAL_DISPLAY | Ready | none | Row 12 | ✓ exact copy (no active session ⇒ `effectiveMode == null`) |
| `07_perception_image` | Radio `Screen Image` selected | VIRTUAL_DISPLAY (carried) | Ready | none | n/a | ✓ checked state transferred to Image container; Recommended chip moved off Transcript |
| `08_perception_both` | Radio `Transcript + Image` selected | VIRTUAL_DISPLAY (carried) | Ready | none | n/a | ✓ |
| `09_perception_transcript` | Radio `Screen Transcript` re-selected; Recommended chip back | VIRTUAL_DISPLAY (carried) | Ready | none | n/a | ✓ |
| `10_home_subtitle_image` | Settings home with perception=Image, VD off → subtitle = `Image` | ACCESSIBILITY | Ready | none | n/a | ✓ subtitle |
| `11_home_subtitle_both` | Settings home with perception=Transcript+Image, VD off → subtitle = `Transcript + Image` | ACCESSIBILITY | Ready | none | n/a | ✓ subtitle |
| `12_home_subtitle_transcript_vd` | Settings home with perception=Transcript, VD on → subtitle = `Transcript · VD` | VIRTUAL_DISPLAY | Ready | none | n/a | ✓ subtitle uses ` · VD` suffix per design |

## Coverage vs the 12-row Status mapper

| Row | Reached? | Notes |
|---|---|---|
| 1  | ❌ Unreachable | Requires `gateError == ShizukuUnavailable && persistedMode == VIRTUAL_DISPLAY` — needs prior VD persist + Shizuku to disappear mid-session. Out of scope for this device. |
| 2  | ❌ Unreachable | Same — needs Shizuku to be unavailable while toggling on. Test device has Shizuku running and active. |
| 3  | ✅ `05_display_perm_denied_row3` | Captured by tapping toggle then `拒绝` on Shizuku dialog. |
| 4  | ✅ `03_display_disabled_row4` | Default state on fresh app. |
| 5  | ❌ Unreachable | Needs an active VD session before toggling off. We did not start a task. |
| 6  | ❌ Unreachable | Needs Shizuku to die after persistedMode=VD. Out of scope. |
| 7  | ❌ Not directly visible as final card state | The transient permission request (`04_shizuku_perm_dialog`) hits this state but the dialog covers the card; once the user picks an option the row settles to Row 3 (deny) or Row 12 (allow). |
| 8  | ❌ Unreachable | `Shizuku.Authorizing` only exists during the in-flight permission grant — same brief window as 7. |
| 9  | ❌ Unreachable | Needs `Shizuku.PermissionDenied` after persistedMode=VD. Test device only persisted VD *after* permission was granted (Row 12). |
| 10 | ❌ Unreachable | Needs `effectiveMode == VIRTUAL_DISPLAY`, which requires a live agent session that successfully started VD. No tasks were run. |
| 11 | ❌ Unreachable | Same — needs `effectiveMode == ACCESSIBILITY` while persisted=VD, i.e. session predates the toggle. |
| 12 | ✅ `06_display_ready_row12` | Captured after granting Shizuku. |

**Reachable on this device without running a task**: 3 / 12 (Rows 3, 4, 12). Plus the live "Setting up…/Checking Shizuku…" transient (`04`).

## Perception card

All three radio rows reachable and exclusive (`checkable=true` on outer container; new selection sets `checked=true`, prior selection flips to `checked=false`). Recommended chip stays on `Screen Transcript` regardless of selection (it's a recommendation, not a state badge). ✓

## Tap-actionable rows have content-desc + clickable

```
$ grep -E 'content-desc="[^"]+".*clickable="true"|clickable="true".*content-desc="[^"]+"' qa/*/dump.xml | wc -l
$ # Settings-sheet rows themselves use empty content-desc but are `clickable=true` (Compose Material3 default).
$ # Top-bar Back/Close icons set content-desc explicitly:
qa/03_display_disabled_row4/dump.xml:
  bounds="[14,168][182,336]" ... content-desc="Back" ... clickable=true (wrapping View)
  bounds="[1082,168][1250,336]" ... content-desc="Close" ... clickable=true (wrapping View)
```

✓ at least the top-bar Back/Close meet the content-desc + clickable=true criterion. The radio and switch rows themselves are clickable but rely on the inner TextView text for accessibility labelling (Compose Material3 `SelectableSurface` / `ListItem` pattern). This matches the project's broader convention; if the design requires explicit `Modifier.semantics { contentDescription = … }` on radio containers, that's a follow-up to file — out of scope for this QA pass.

## Regressions / issues found

None. All copy strings match the design's "Status mapper — corrected table" verbatim. Switch enabled flag, checked flag, status label, and subtitle agree with each reached row. Home subtitle accurately reflects perception mode and VD state (`Transcript`, `Image`, `Transcript + Image`, `Transcript · VD`).

## Out-of-scope (will not be QA'd from real device)

- Row 1, 2, 6: Shizuku unavailable variants — would require uninstalling/disabling Shizuku mid-flow.
- Row 5, 10, 11: Need a live `AgentSession` to drive `effectivePlatformMode`. The `connectedDebugAndroidTest` suite (`DisplayModeSettingsTest`) covers these via fakes; manual coverage gated on running a task.
- Row 7, 8, 9: Authorizing/NeedsPermission with persistedMode=VD — the device transitions through these too fast for capture.

## connectedDebugAndroidTest

Run separately — see commit body and `verify.md`. Pre-existing failures in `Chat*` / `Capsule*` suites are KNOWN-FLAKY per `projects/active/settings_display_perception_refresh/verify.md`. In-scope suites are `DisplayModeSettingsTest`, `SettingsNavTest`, `SettingsTermuxRowTest`.

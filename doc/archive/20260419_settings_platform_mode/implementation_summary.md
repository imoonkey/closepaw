# settings_platform_mode — Implementation Summary

Date: 2026-04-19
Commit: 44b6cb83

## What was implemented

User-facing surface for the previously-hidden Platform Mode setting and the Shizuku capability that gates Virtual Display.

- New **Display Mode** section in `PermissionsAdvancedSettingsPage` (between Permissions and Debug): 2-option selector (Accessibility / Virtual Display) + reactive Shizuku status row with Grant / Learn-more affordances. VD option disabled when Shizuku status != Ready.
- `AgentService.effectivePlatformMode: StateFlow<PlatformMode?>` published from the actually-constructed `AndroidPlatform.mode` (not from persisted intent). Null until the first session bootstraps; collected by `MainActivityContent` and threaded into Settings + Home subtitle. Home subtitle gains a ` · A11y` / ` · VD` chip iff effective mode is non-null.
- `ShizukuClient.addRequestPermissionResultListener` / `remove…` wrappers (proxied via `ShizukuRuntimeGateway`) so the Compose layer never imports `Shizuku.*`. `rememberShizukuStatus` uses `produceState` driven by ON_RESUME + permission-result + binder-dead listeners — no polling.
- `AndroidPlatform.mode` promoted to abstract member; `AccessibilityPlatform`, `VirtualDisplayPlatform`, and test fakes override.
- Instrumented `DisplayModeSettingsTest` (3 tests, all PASS): selector callback, VD-disabled-when-Shizuku-Unavailable, Home-subtitle-chip-vs-effective-mode.

## Key decisions

- **Persisted vs. effective mode kept separate.** Persisted `platformMode` is intent for the next session; effective is what the runtime actually built. UI tells the truth in both: selector reflects persisted; subtitle chip reflects effective. Binder-death mid-VD-session does not retro-snap the selector.
- **No app-tier global override toggle.** Explicitly rejected — `assets/security/app_tiers.json` is policy that must not be user-mutable. Documented in `design_claude.md`.
- **No new SharedPreferences keys.** Reused existing `AppSettingsState.updatePlatformMode` / `agent_prefs.platform_mode`.
- **Coupled Shizuku status to the VD option** rather than a free-floating row, so "why is this disabled?" has one answer.
- For unit/instrumented testability of the section without a live Shizuku, extracted a stateless `DisplayModeSection(... status: ShizukuStatus, ...)` overload — production overload signature preserved.

## Verification

- `./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin` → SUCCESS.
- `./gradlew :app:lintDebug` → SUCCESS, no new errors.
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.closepaw.qa.DisplayModeSettingsTest` → 3/3 PASS.
- Real-device QA (EP0110MZ0BC101266W, Shizuku Ready): selector toggles A11y ↔ VD both directions and persists; intent path `--es platform_mode VIRTUAL_DISPLAY` still updates persisted pref; Home subtitle chip appears after first session.

## Links

- Design: [`design_claude.md`](design_claude.md)
- Code review: [`code_review_codex.md`](code_review_codex.md), [`review_codex.md`](review_codex.md)
- QA report (instrumented + real-device matrix + later capsule-bug investigation): [`qa_report.md`](qa_report.md)

## Follow-ups (not in this milestone)

The QA pass uncovered and then disproved a separate "in-app capsule missing in VD" report on HEAD `f4a699b9`; details captured in `qa_report.md` under "Root cause confirmation" — no production fix required.

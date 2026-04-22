# UXFB-Followup2 — IME scope (onboarding + settings)

Codex HIGH on commit 08ae6378: `windowSoftInputMode=adjustNothing` is
activity-wide, but compensating `Modifier.imePadding()` only landed in
`ChatScreen`. Onboarding API-key step and Settings API-key sheet were left
hidden behind the keyboard.

## Changes

- `OnboardingShell.kt` — added `Modifier.imePadding()` to the outer
  `BoxWithConstraints`. Because `BoxWithConstraints.maxHeight` shrinks once
  imePadding consumes the IME insets, the existing `maxHeight < 480.dp`
  scroll-fallback also fires when the keyboard is up, so the body
  scrolls if it would otherwise overflow.
- `SettingsSheet.kt` — added `Modifier.imePadding()` to the outer
  `Column` modifier chain (alongside the existing system-bar paddings)
  so the bottom-sheet content (incl. API-key field) floats above IME.

Manifest, ChatScreen, and `MainActivityContent` are untouched.

## Verification

- `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL
- `./gradlew :app:test` — BUILD SUCCESSFUL
- `scripts/setup.sh` — installed + permissions restored
- Manual device check (Settings → LLM & Authentication → API Key tap):
  field stays fully visible above the IME (see
  `qa-screenshots/settings_ime.png`).

## Onboarding live test

Skipped — this device is past onboarding and the flow is not reachable
without re-installing/clearing data. Code change is structurally
identical to `ChatScreen`/`SettingsSheet` (outer container
`Modifier.imePadding()`); the BoxWithConstraints heuristic also picks up
the IME-shrunk height for the scroll fallback.

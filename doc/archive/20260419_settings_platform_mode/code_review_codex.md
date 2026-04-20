# Review: settings_platform_mode

## Critical
None.

## High
1. `effectivePlatformMode` is a latch, not state. `observeExternalSession()` writes `_effectivePlatformMode` once, but neither the `sessionCleared` callback nor `onDestroy()` ever resets it to `null` (`app/src/main/kotlin/ai/closepaw/app/AgentService.kt:51`, `app/src/main/kotlin/ai/closepaw/app/AgentService.kt:73`, `app/src/main/kotlin/ai/closepaw/app/AgentService.kt:103`, `app/src/main/kotlin/ai/closepaw/app/AgentService.kt:236`). After a session ends, Home still shows the last mode chip and the selector still prefers dead-session state over persisted intent. That directly violates the design invariant that `null` means "no platform instantiated."

2. The API change broke the QA harness outright. `SettingsSheet` and `PermissionsAdvancedSettingsPage` now require `platformMode`, `effectivePlatformMode`, and `onPlatformModeChange`, but `QaSettingsHelpers` still calls them without those args (`app/src/androidTest/kotlin/ai/closepaw/qa/QaSettingsHelpers.kt:66`, `app/src/androidTest/kotlin/ai/closepaw/qa/QaSettingsHelpers.kt:124`). `./gradlew :app:compileDebugAndroidTestKotlin` fails, so task 6 from the design doc is not just missing; the androidTest source set does not compile anymore.

## Medium
1. `AndroidPlatform.mode` defaults to `ACCESSIBILITY` instead of being mandatory (`app/src/main/kotlin/ai/closepaw/platform/AndroidPlatform.kt:24`). The whole reason this milestone exists is to stop lying about effective mode. Making the property opt-out means the next platform implementation or fake that forgets to override it will silently report the wrong thing again.

## Low
None.

## Notes
- The main wiring is otherwise on spec: `MainActivity` passes `session.getServices().platform.mode`, `DisplayModeSection` sits between Permissions and Debug, `MainActivityIntentApplier` is untouched, and no Shizuku flow was pushed through the activity.
- `./gradlew :app:compileDebugKotlin` passes.
- `./gradlew :app:compileDebugAndroidTestKotlin` fails with missing-parameter errors in `QaSettingsHelpers`.

CHANGES_REQUESTED

## Re-review
- H1 resolved: `_effectivePlatformMode.value = null` is now set in both the `sessionCleared` callback and `onDestroy()` (`app/src/main/kotlin/ai/closepaw/app/AgentService.kt:73`, `app/src/main/kotlin/ai/closepaw/app/AgentService.kt:240`).
- H2 resolved: `QaSettingsHelpers` now passes `platformMode`, `effectivePlatformMode`, and `onPlatformModeChange` to both `SettingsSheet` and `PermissionsAdvancedSettingsPage` (`app/src/androidTest/kotlin/ai/closepaw/qa/QaSettingsHelpers.kt:67`, `app/src/androidTest/kotlin/ai/closepaw/qa/QaSettingsHelpers.kt:127`).
- M1 resolved: `AndroidPlatform.mode` is abstract, and the concrete implementations/fakes now override it (`app/src/main/kotlin/ai/closepaw/platform/AndroidPlatform.kt:24`, `app/src/main/kotlin/ai/closepaw/platform/AccessibilityPlatform.kt:51`, `app/src/main/kotlin/ai/closepaw/platform/virtualdisplay/VirtualDisplayPlatform.kt:40`).
- Verified `./gradlew :app:compileDebugKotlin` and `./gradlew :app:compileDebugAndroidTestKotlin` both pass.

APPROVE

# AndroidAgent QA Test Framework Design

## Goal

Add a deterministic QA test layer for `androidagent` that covers:

- app-owned UI flows in the Compose shell
- service wiring and permission-dependent behavior
- critical debug/bootstrap paths already used by developers
- emulator-based CI gating

without making pull requests depend on live OpenAI calls, local model downloads, or AndroidWorld-style benchmark variance.

## Current State

- The repo already has strong unit coverage in `app/src/test/kotlin/...`.
- There is no `app/src/androidTest/...` suite yet.
- `app/build.gradle.kts` configures unit tests, but not an instrumentation runner or `androidTest` dependencies.
- The app is Compose-first at the activity layer, but it also depends on:
  - an exported `AccessibilityService`
  - overlay permission
  - system Settings flows
  - debug launch intents (`EXTRA_FRESH_SESSION`, `EXTRA_GOAL`, etc.)
  - debug action execution via `ACTION_DEBUG_EXEC`
- Existing scripts already know how to install the app and grant overlay/accessibility through ADB: `scripts/setup.sh`, `scripts/debug-run.sh`, `scripts/action-test.sh`.

The real problem is not “pick one mobile UI test tool”. The real problem is separating deterministic app QA from nondeterministic agent-quality eval.

## Decision

Use one primary QA stack inside the Android project:

- **Adopt `Compose Test`** for app-owned screens and semantics-based assertions.
- **Adopt `UI Automator`** for system Settings, permission flows, and device-level verification outside the app process.
- **Keep `Espresso` only as underlying Android test plumbing**, not as a separate authoring style.
- **Do not adopt `Kaspresso` initially.**
- **Do not adopt `Maestro` initially.**
- **Do not adopt `Appium`.**

Keep the existing layers distinct:

- `app/src/test` remains the fast logic/unit layer.
- `app/src/androidTest` becomes the deterministic app QA layer.
- `scripts/debug-run.sh` and `scripts/action-test.sh` stay as developer tools.
- `scripts/eval_parallel.sh` remains the agent-quality benchmark layer, not PR-gating QA.

## Framework Choice

| Framework | Decision | Use in this repo | Why |
| --- | --- | --- | --- |
| Compose Test | Adopt | Main in-app QA API | The app shell is Compose-based, so this is the shortest path to stable selectors and deterministic assertions. |
| UI Automator | Adopt | System/external UI and device setup verification | Needed for accessibility settings, overlay/system UI, and cross-app boundaries. |
| Espresso | Limited | Only where Compose test or AndroidX internals need it | The project is not View-first. A separate Espresso page-object layer would be redundant. |
| Kaspresso | Reject for initial rollout | None | It adds another DSL on top of APIs we already need directly. This repo should stay Kotlin-native and simple. |
| Maestro | Reject for initial rollout | None | Good black-box smoke tooling, but weak for seeded internal state, fake LLM injection, and Kotlin-side test helpers. It would create a second E2E system. |
| Appium | Reject | None | Wrong trade-off for a single Android/Kotlin repo. Extra server/protocol complexity buys nothing here. |

## Approach

### 1. Make `androidTest` the QA home

All deterministic QA lives under `app/src/androidTest`. Do not create a separate test app, external YAML suite, or second runner unless we prove the first stack is insufficient.

### 2. Stub LLM behavior instead of calling real models

Most QA scenarios should not depend on cloud auth, network, model latency, or local model download state.

Use a **debug-only scripted LLM override**:

- add `QaRuntimeOverrides` in `app/src/debug/kotlin/...`
- inject a scripted `LLMClient` into `SessionLlmBootstrapper` when the override is set
- build it on top of the existing `LLMClientFactory.forTest(...)` seam

Important: **do not add a new production `LLMBackendType`** just for QA. That would leak test concerns into runtime config and checkpoint schemas. A debug override is simpler and cleaner.

### 3. Use one canonical device bootstrap path

For most tests:

- clear app data
- seed onboarding/settings state directly
- enable overlay/accessibility with shell commands
- launch the debug app with explicit intent extras

Only dedicated permission-flow tests should click through real Settings UI. Everything else should start from a prepared device state.

This keeps the suite fast and turns permission setup into a canonical precondition instead of repeating brittle setup steps in every test.

### 4. Split QA by responsibility, not by tool

- **Compose scenarios** verify app-owned behavior.
- **System scenarios** verify Settings/permission/service/device boundaries.
- **Action-path scenarios** reuse the existing debug action hook for deterministic low-level smoke.

Do not organize the suite around “Espresso tests” vs “UI Automator tests”. The scenario owns the flow; the test uses the smallest API needed.

## Test Runtime Design

### Deterministic QA State Machine

`CLEAN_APP`
-> clear package data, clear session history/memory

`SEEDED_STATE`
-> write onboarding/settings/session preconditions

`DEVICE_READY`
-> grant overlay, enable accessibility service, verify shell-visible prerequisites

`APP_LAUNCHED`
-> start `MainActivity` with explicit debug extras when needed

`FLOW_RUNNING`
-> Compose assertions and optional UI Automator steps

`ARTIFACTS_COLLECTED`
-> on failure or completion, capture screenshot/logcat/device artifacts

Transitions:

- Most tests go `CLEAN_APP -> SEEDED_STATE -> DEVICE_READY -> APP_LAUNCHED`.
- Permission onboarding tests intentionally go through real Settings UI before returning to the app.
- Any failure still transitions to `ARTIFACTS_COLLECTED`.

## Components

### Gradle and runner setup

Update `app/build.gradle.kts`:

- set `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`
- run QA against the existing `debug` variant; do not add a new `qa` flavor
- add `androidTestImplementation` for:
  - `androidx.test.ext:junit`
  - `androidx.test:runner`
  - `androidx.test:rules`
  - `androidx.test.uiautomator:uiautomator`
  - `androidx.compose.ui:ui-test-junit4` using the existing Compose BOM
- add `androidTestUtil("androidx.test:orchestrator")`
- enable Android Test Orchestrator with `clearPackageData=true`

Why Orchestrator:

- per-test isolation matters because the app persists onboarding, session history, allow-lists, and memory
- it removes state bleed between tests without hand-written cleanup in every class

### Debug-only QA helpers

Add in `app/src/debug/kotlin/com/moonkey/androidagent/qa/`:

- `QaRuntimeOverrides.kt`
  - scripted LLM override
  - optional per-test flags for runtime behavior that production code must read
- `ScriptedLlmClient.kt`
  - deterministic fake client for session/UI smoke flows

Add in `app/src/androidTest/kotlin/com/moonkey/androidagent/qa/base/`:

- `QaStateSeeder.kt`
  - wraps `OnboardingStore`, `AppSettingsStore`, session storage cleanup, memory cleanup
- `QaShell.kt`
  - shell helpers for `settings put secure`, `appops set`, force-stop, clear data
- `QaLaunchIntents.kt`
  - typed helpers around `MainActivity` debug extras

This keeps runtime hooks in `debug`, test harness code in `androidTest`, and production code out of the middle.

### Compose selectors

The current UI mostly relies on visible text and some content descriptions. That is not enough for stable QA.

Add a small stable selector surface in main code:

- `app/src/main/kotlin/com/moonkey/androidagent/ui/testtags/QaTags.kt`
- apply `Modifier.testTag(...)` only to high-value nodes:
  - onboarding shell actions
  - permission repair card
  - chat composer and send action
  - session drawer controls
  - settings open/close and major toggles
  - external-goal confirmation dialog

Rule: use tags for app-owned controls; use text/content descriptions only when that label is genuinely the contract.

### Instrumentation test layout

```text
app/src/androidTest/kotlin/com/moonkey/androidagent/qa/
  annotations/
    Smoke.kt
    Nightly.kt
    ManualDevice.kt
  base/
    QaSmokeRule.kt
    QaArtifactsRule.kt
    QaHarness.kt
  robots/
    ChatRobot.kt
    OnboardingRobot.kt
    SettingsRobot.kt
    SystemSettingsRobot.kt
  smoke/
    AppLaunchSmokeTest.kt
    FreshSessionIntentSmokeTest.kt
    SettingsSheetSmokeTest.kt
  permissions/
    AccessibilityEnableFlowTest.kt
    OverlayPermissionFlowTest.kt
    PermissionRepairCardTest.kt
  service/
    AgentServiceLifecycleTest.kt
    SessionResumeAfterRelaunchTest.kt
  action/
    DebugActionReceiverSmokeTest.kt
  nightly/
    VirtualDisplayManualSmokeTest.kt
```

### Example test shape

```kotlin
@RunWith(AndroidJUnit4::class)
@Smoke
class FreshSessionIntentSmokeTest {

    @get:Rule
    val qa = QaSmokeRule()

    @Test
    fun fresh_session_goal_bootstraps_without_onboarding() {
        qa.resetApp()
        qa.seed.onboardingCompleted()
        qa.device.enableOverlay()
        qa.device.enableAccessibilityService()
        qa.runtime.useScriptedLlm(LlmScripts.completeImmediately())

        qa.launch.freshSession(goal = "Open Settings")

        qa.chat.assertConversationVisible()
        qa.chat.assertGoalSubmitted("Open Settings")
        qa.service.assertConnected()
    }
}
```

The point is not the exact API spelling. The point is the shape:

- one harness
- explicit seed/setup
- no live model dependency
- assertions on app state first, system state second

### Artifact capture

Add a `QaArtifactsRule` that on failure:

- captures a full-device screenshot via `UiDevice`
- dumps logcat
- saves current activity/package info
- pulls app files that matter when present:
  - trace artifacts
  - session history
  - debug action output

Write artifacts under `debug-output/qa/<timestamp>/<test-name>/` to align with the repo’s existing debug-output convention.

## CI Wiring

### PR gate

Add `.github/workflows/qa.yml` with two required jobs:

1. `unit`
   - run `./gradlew testDebugUnitTest`

2. `android-smoke`
   - boot one emulator
   - run `./gradlew connectedDebugAndroidTest` with the `@Smoke` annotation filter
   - upload:
     - `app/build/reports/androidTests/connected/`
     - `app/build/outputs/androidTest-results/`
     - `debug-output/qa/` if present

Use an emulator PR lane first. Do not gate PRs on physical devices, Shizuku, or AndroidWorld.

### Nightly lane

Add a scheduled or manually triggered lane for:

- full instrumentation suite excluding `@ManualDevice`
- optional longer system-permission flows
- optional existing benchmark smoke such as `scripts/eval_parallel.sh eval/config/aw_subset_smoke.txt`

This keeps app QA and agent benchmarking in the same CI surface while not pretending they are the same signal.

### Manual/self-hosted device lane

Mark hardware- or OEM-sensitive flows `@ManualDevice`:

- virtual display / Shizuku
- OEM-specific overlay behavior
- anything that depends on real-device quirks

These should run only on a self-hosted runner or a developer-attached device, not on every PR.

## Integration Plan

### Local commands

Keep local execution simple:

- unit: `./gradlew testDebugUnitTest`
- smoke instrumentation: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.annotation=com.moonkey.androidagent.qa.annotations.Smoke`
- nightly/full instrumentation: same task with `notAnnotation` or package filters as needed

If needed, add a thin wrapper like `scripts/qa/run_smoke.sh`, but only after the Gradle path works cleanly.

### Reuse of existing repo seams

- Reuse `MainActivity` debug extras for fresh-session launch and goal dispatch.
- Reuse `OnboardingStore` and `AppSettingsStore` through a test seeder instead of clicking setup in every test.
- Reuse `ACTION_DEBUG_EXEC` for direct action smoke.
- Keep `scripts/setup.sh` and `scripts/debug-run.sh` as human-facing tools, not the basis of instrumentation itself.

## What Goes Where

| Concern | Layer |
| --- | --- |
| Session logic, policy, history, prompt assembly | Existing `app/src/test` unit tests |
| Onboarding shell, settings sheet, chat shell, permission repair UI | Compose Test in `app/src/androidTest` |
| Accessibility/overlay enablement, Settings round-trip, service-visible behavior | UI Automator-backed `androidTest` scenarios |
| Raw action execution smoke | `androidTest` reusing `ACTION_DEBUG_EXEC` |
| Agent competence across third-party apps/tasks | Existing AndroidWorld/eval scripts |

## Migration / Rollout Strategy

### Phase 1: Infrastructure

- instrumentation runner + dependencies
- Orchestrator
- `QaRuntimeOverrides`
- `QaStateSeeder`
- `QaArtifactsRule`
- initial `QaTags`

Deliverable: one passing emulator smoke test in CI.

### Phase 2: Stable app-shell smoke

Add `@Smoke` coverage for:

- clean launch shows onboarding on fresh install
- seeded launch bypasses onboarding
- settings sheet opens and closes
- external goal debug launch creates a session shell
- permission repair card appears when expected

Deliverable: PR-gating smoke suite with 5-8 deterministic tests.

### Phase 3: Service and system flows

Add UI Automator/system coverage for:

- accessibility service enablement and app return
- overlay grant path
- app relaunch/session resume smoke
- debug action execution smoke

Deliverable: non-flaky emulator system suite plus failure artifacts.

### Phase 4: Nightly/manual expansion

Add:

- longer permission/onboarding journeys
- virtual display manual smoke
- optional benchmark smoke on the existing eval worker

Deliverable: broader confidence without slowing PR feedback.

## Trade-offs

### Why not Maestro now

Maestro is attractive for black-box smoke, but this repo needs:

- seeded internal app state
- scripted fake LLM behavior
- Kotlin-side helpers around SharedPreferences and session files
- direct reuse of existing debug seams

That all fits naturally in `androidTest`, not in a separate YAML stack.

If later we want a tiny release-candidate black-box suite for non-engineers, add a handful of Maestro flows then. Do not make it the foundation.

### Why not Kaspresso

Kaspresso would wrap the exact APIs we already need. The cost is another abstraction layer, another DSL, and another debugging surface. This repo already has enough custom moving parts.

### Why not merge this with AndroidWorld eval

Because they answer different questions:

- QA: “did our app shell and service wiring regress?”
- Eval: “can the agent solve tasks across apps?”

Treating eval as QA would make CI slow, flaky, and hard to diagnose.

## Tasks

### `qa-gradle-baseline`

- Scope: `app/build.gradle.kts`, `.github/workflows/qa.yml`
- Acceptance criteria:
  - instrumentation runner configured
  - Orchestrator enabled
  - smoke suite runnable on one emulator in CI
- Dependencies: none

### `qa-debug-harness`

- Scope: `app/src/debug/kotlin/com/moonkey/androidagent/qa/**`, `app/src/main/kotlin/com/moonkey/androidagent/session/**`
- Acceptance criteria:
  - tests can inject scripted LLM behavior without adding a production backend enum
  - tests can seed onboarding/settings/session state through app code
- Dependencies: `qa-gradle-baseline`

### `qa-compose-selectors`

- Scope: `app/src/main/kotlin/com/moonkey/androidagent/ui/**`
- Acceptance criteria:
  - stable `testTag` coverage exists for key app-owned controls
  - smoke tests do not depend primarily on rendered text
- Dependencies: `qa-gradle-baseline`

### `qa-smoke-suite`

- Scope: `app/src/androidTest/kotlin/com/moonkey/androidagent/qa/smoke/**`, `.../base/**`, `.../robots/**`
- Acceptance criteria:
  - 5-8 deterministic `@Smoke` tests pass on emulator
  - failures produce screenshots and logcat
- Dependencies: `qa-debug-harness`, `qa-compose-selectors`

### `qa-system-suite`

- Scope: `app/src/androidTest/kotlin/com/moonkey/androidagent/qa/permissions/**`, `.../service/**`, `.../action/**`
- Acceptance criteria:
  - accessibility/overlay/system flows are covered without live model dependency
  - `ACTION_DEBUG_EXEC` smoke is automated
- Dependencies: `qa-debug-harness`

### `qa-nightly-lanes`

- Scope: `.github/workflows/qa.yml`, optional `scripts/qa/**`
- Acceptance criteria:
  - PR smoke and nightly/manual lanes are separated
  - manual-device tests are excluded from PR gating
  - existing eval smoke can be added without coupling it to the smoke suite
- Dependencies: `qa-smoke-suite`, `qa-system-suite`

## Recommendation

Adopt **Compose Test + UI Automator in `app/src/androidTest`**, backed by a **debug-only scripted LLM override** and **seeded device/app state**. Keep PR CI limited to deterministic emulator smoke, and keep AndroidWorld eval as a separate benchmark signal.

That is the simplest setup that actually matches this repo.

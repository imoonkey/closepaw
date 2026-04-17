# AndroidAgent QA Test Framework

Status: aligned draft v2

This document is the current self-contained aligned design for QA testing in `androidagent`.

Maintenance rule:

- When an open question is resolved, update this document first.
- After the design text reflects the resolved decision, remove or revise the corresponding item in the final `Open Questions` section.

## Goal

Add a deterministic QA layer for `androidagent` that validates:

- app-owned UI flows in the Compose shell
- permission and service setup flows
- debug/bootstrap paths the team already uses
- emulator-based CI smoke coverage

without conflating UI regression testing with agent-quality evals, live LLM behavior, or exploratory UX debugging.

## Scope

This QA layer is for repeatable regression checks of the Android app and its device-boundary behavior.

It is separate from, and does not replace:

- `app/src/test` JVM unit tests
- `eval/` AndroidWorld-style agent benchmarks
- `/ux-visual-debug` exploratory/manual UX verification
- ad hoc debugging through `scripts/debug-run.sh` and `scripts/action-test.sh`

Explicitly out of scope for the initial rollout:

- snapshot / golden-image testing (Paparazzi, Roborazzi, shot)
- tests that make live LLM calls (cloud or local Leap)
- cross-app agent-quality scenarios — those remain in `eval/`
- Maestro or any second E2E stack in the PR gate (see Open Questions for a possible later release-only lane)

## Aligned Decisions

The current consensus is:

- The first QA implementation should use a **single deterministic Android test runtime** inside the project.
- That runtime should live in `app/src/androidTest`.
- The initial stack should use:
  - **Compose UI Test** for app-owned UI
  - **UI Automator** for system Settings, permissions, and out-of-process UI
  - **AndroidX instrumentation runner + Android Test Orchestrator** for execution and isolation
- `Kaspresso` and `Appium` are out of scope for this project.
- `Espresso` is not a separate authoring model; it is only incidental AndroidX plumbing where needed.
- The QA layer must stub or script LLM behavior; live model calls are not part of deterministic QA.
- Stable `testTag` selectors are the contract for app-owned UI.
- PR CI should gate on deterministic emulator smoke only.
- Hardware-sensitive or environment-sensitive flows must be separated from required PR checks.

## Initial QA Architecture

### Testing layers

```text
app/src/test
  -> fast JVM unit tests for logic, policy, session state, formatting, storage

app/src/androidTest
  -> deterministic device/emulator QA for app UI, permissions, service wiring, debug seams

eval/
  -> agent-quality benchmark layer across third-party apps and tasks
```

The first aligned rollout adds the missing middle layer: deterministic `androidTest` QA.

### Organize by scenario, not by tool

The suite should be structured by behavior:

- **Compose scenarios**
  - onboarding shell
  - settings sheet/pages
  - chat shell
  - permission repair card
- **System scenarios**
  - overlay permission
  - accessibility enablement
  - return from Settings to app
  - service-visible behavior
- **Debug seam scenarios**
  - fresh-session launch via debug intent extras
  - direct action execution via `ACTION_DEBUG_EXEC`

Tool choice follows the scenario:

- use Compose Test for app-owned UI
- use UI Automator when the flow crosses the app boundary or system UI

## Deterministic Runtime Model

The initial QA runtime should turn setup and permissions into canonical preconditions.

### Test state machine

```text
CLEAN_APP
  -> clear package data and runtime residue

SEEDED_STATE
  -> seed onboarding/settings/session prerequisites directly

DEVICE_READY
  -> apply shell/device prerequisites
     (overlay appops, accessibility enabled, service visible)

APP_LAUNCHED
  -> launch MainActivity with explicit debug extras when required

FLOW_RUNNING
  -> run Compose assertions and optional UI Automator steps

ARTIFACTS_COLLECTED
  -> on failure or completion, save screenshot/logcat/relevant app files
```

Most tests should start from `CLEAN_APP -> SEEDED_STATE -> DEVICE_READY -> APP_LAUNCHED`.

Only tests whose purpose is to verify the actual Settings journey should click through setup manually.

## Repo Integration

### Gradle and instrumentation

Update `app/build.gradle.kts` to:

- set `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`
- run QA against the existing `debug` variant
- add `androidTestImplementation` for:
  - `androidx.test.ext:junit`
  - `androidx.test:runner`
  - `androidx.test:rules`
  - `androidx.test.uiautomator:uiautomator`
  - `androidx.compose.ui:ui-test-junit4` using the existing Compose BOM
- add `androidTestUtil("androidx.test:orchestrator")`
- enable Android Test Orchestrator with `clearPackageData=true`
- set `testOptions.animationsDisabled = true` (Compose + UI Automator both flake under default animator durations)

Orchestrator is part of the aligned base. This app persists onboarding state, settings, allow-lists, session history, and memory. Per-test state isolation is not optional.

`testTag` compiles to a lightweight `SemanticsModifier` and is retained in release builds; no R8 keep rule or debug-only strip is required.

### Directory layout

```text
app/src/androidTest/kotlin/com/moonkey/androidagent/qa/
  annotations/
    Smoke.kt
    Nightly.kt
    ManualDevice.kt
  base/
    QaHarness.kt
    QaSmokeRule.kt
    QaArtifactsRule.kt
    QaStateSeeder.kt
    QaShell.kt
    QaLaunchIntents.kt
  fixtures/
    LlmScripts.kt
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

app/src/debug/kotlin/com/moonkey/androidagent/qa/
  QaRuntimeOverrides.kt
  ScriptedLlmClient.kt

app/src/main/kotlin/com/moonkey/androidagent/ui/testtags/
  QaTags.kt

.github/workflows/
  qa.yml
```

### Debug/runtime seams to reuse

The aligned design reuses code that already exists in this repo:

- `MainActivity` debug extras for fresh-session bootstrap and goal dispatch
- `OnboardingStore` and `AppSettingsStore` for seeded state
- `ACTION_DEBUG_EXEC` for deterministic action-path smoke
- existing `debug-output/` convention for artifacts

The QA harness should exploit those seams before introducing any second external framework.

## LLM Isolation

Deterministic QA cannot depend on live OpenAI calls or local model download behavior.

The aligned base uses a **debug-only scripted runtime override**:

- test code selects a scripted response plan
- debug-only runtime glue injects a scripted `LLMClient`
- the implementation should reuse the existing `LLMClientFactory.forTest(...)` seam or an equivalent runtime override path

Important constraint:

- do **not** add a new production `LLMBackendType` or other production runtime mode just for QA

That keeps test concerns out of checkpoint schemas and production config.

### Fixture contract

Scripted LLM behavior is expressed as **Kotlin code**, not JSON or other external data:

- `app/src/androidTest/kotlin/com/moonkey/androidagent/qa/fixtures/LlmScripts.kt` exposes named factories (e.g. `LlmScripts.completeImmediately()`, `LlmScripts.oneToolCallThenFinish(...)`).
- Each factory returns an `LLMClient` implementation (or a driver consumed by `ScriptedLlmClient`) that describes the *intended interaction pattern* — how many turns, which tool calls, which finish reason — not byte-exact prompt/response payloads.
- Tests opt in per test via `qa.runtime.useScriptedLlm(LlmScripts.xxx())`; no global state, no file-system fixtures.

Why Kotlin over JSON:

- type-safety against the `LLMClient` / tool-call interfaces — schema drift surfaces as a compile error, not a silent CI pass
- IDE refactors follow naturally when internal interfaces move
- no capture-and-refresh workflow to maintain; scripts describe intent, so they only change when the interaction semantics change

Scripts must not encode real OpenAI response envelopes. If a test needs to assert on wire-format handling, it belongs in `app/src/test` unit tests, not in QA.

## Selectors

The current UI has content descriptions in several places, but not enough stable selectors for QA.

Add a small explicit selector surface in production UI code:

- `onboarding` shell actions
- permission repair card
- chat composer and send action
- session drawer controls
- settings open/close and major toggles
- external-goal confirmation dialog

Rule:

- use `testTag` for app-owned controls that QA must target repeatedly
- use visible text or content descriptions only when that label is genuinely the user-facing contract

## Artifact Capture

Add a `QaArtifactsRule` that, on failure:

- captures a full-device screenshot via `UiDevice`
- dumps logcat
- records current package/activity information
- saves relevant app files when present:
  - trace artifacts
  - session history
  - debug action output

Artifacts should go under:

```text
debug-output/qa/<timestamp>/<test-name>/
```

This matches the repo’s existing `debug-output/` convention.

## CI Wiring

### PR-required jobs

Add `.github/workflows/qa.yml` with two required jobs:

1. `unit`
   - run `./gradlew testDebugUnitTest`

2. `android-smoke`
   - boot one emulator (API 33, `google_apis` image, x86_64) via `ReactiveCircus/android-emulator-runner`
   - run `./gradlew connectedDebugAndroidTest` filtered to `@Smoke`
   - upload instrumentation reports and `debug-output/qa/` artifacts when present

The initial PR gate stays on the debug variant and on deterministic smoke only. The emulator image is pinned so flake investigations can compare against a stable baseline; upgrades to newer API levels happen as explicit, reviewed changes.

### Lane-promotion threshold

A non-required lane is promoted to required PR gate when all of the following hold over its last 50 CI runs:

- green on at least 49 of 50 runs (≥ 98% pass rate)
- no individual test exceeds a 2% flake rate (retry-to-green counts as a flake)
- no run requires operator intervention (re-run, device reboot, emulator reset)

This is the single canonical bar. It applies to every optional lane (full instrumentation, permission/system, manual-device, any future release-smoke lane). If the bar is unmet, the lane stays optional.

### Optional lanes

Add non-required lanes after the base is green:

- full instrumentation suite excluding `@ManualDevice`
- longer permission/system flows
- manual/self-hosted device flows for Shizuku, virtual display, and OEM-sensitive behavior

Any lane that depends on hardware quirks, Shizuku, or non-standard device setup must stay out of required PR checks until the lane meets the **lane-promotion threshold** above.

## Release Validation

The repo’s current development guidance already distinguishes debug QA from release validation:

- day-to-day work uses debug
- release builds are for shipping, release smoke, and R8/resource-shrink validation
- before publishing a release, the team should install the signed release APK and run at least one real end-to-end LLM tool-call flow

The aligned first draft keeps that release validation requirement, but does **not** yet make an automated release-smoke framework part of the initial QA stack.

## Local Execution

The base local commands should work without wrapper scripts:

- `./gradlew testDebugUnitTest`
- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.annotation=com.moonkey.androidagent.qa.annotations.Smoke`

Thin wrapper scripts can be added later if they simplify common flows, but Gradle should remain the canonical entry point for the initial rollout.

## Rollout Plan

### Phase 1: Baseline

- instrumentation runner
- Android Test Orchestrator
- `androidTest` skeleton
- minimal `qa.yml` running one smoke test on emulator

### Phase 2: Deterministic harness

- scripted LLM override + `LlmScripts` fixtures
- state seeder
- shell/device helper layer
- debug launch helpers
- initial `QaTags`

### Phase 3: PR smoke coverage

- clean launch / onboarding state coverage
- seeded launch bypass
- settings sheet smoke
- permission repair card
- debug action smoke

### Phase 4: CI expansion and docs

- expand `qa.yml` with full PR `unit` + `android-smoke` gate and optional lanes
- artifact upload
- contributor docs for running QA from clone to green

### Phase 5: broader lanes

- full instrumentation lane
- manual-device lane
- any future automated release-smoke lane, if approved

## Tasks

### `qa-gradle-baseline`

- Scope: `app/build.gradle.kts`, `.github/workflows/qa.yml` (minimal form: one smoke job)
- Acceptance criteria:
  - instrumentation runner configured
  - Orchestrator enabled, `animationsDisabled = true`
  - one smoke test runs on emulator in CI
- Dependencies: none

### `qa-debug-harness`

- Scope: `app/src/debug/kotlin/com/moonkey/androidagent/qa/**`, `app/src/androidTest/kotlin/com/moonkey/androidagent/qa/base/**`, `app/src/androidTest/kotlin/com/moonkey/androidagent/qa/fixtures/LlmScripts.kt`
- Acceptance criteria:
  - tests can inject scripted LLM behavior without adding a production backend enum
  - tests can seed onboarding/settings/session state directly
  - tests can launch the app through typed debug helpers
  - `LlmScripts` exposes at least one factory consumed by the first smoke test
- Dependencies: `qa-gradle-baseline`

### `qa-compose-selectors`

- Scope: `app/src/main/kotlin/com/moonkey/androidagent/ui/**`
- Acceptance criteria:
  - stable `testTag` coverage exists for high-value app-owned controls
  - smoke tests do not depend primarily on visible text
- Dependencies: `qa-gradle-baseline`

### `qa-smoke-suite`

- Scope: `app/src/androidTest/kotlin/com/moonkey/androidagent/qa/smoke/**`, `.../robots/**`
- Acceptance criteria:
  - deterministic `@Smoke` tests pass on emulator
  - failures produce screenshots and logcat artifacts
- Dependencies: `qa-debug-harness`, `qa-compose-selectors`

### `qa-system-suite`

- Scope: `app/src/androidTest/kotlin/com/moonkey/androidagent/qa/permissions/**`, `.../service/**`, `.../action/**`
- Acceptance criteria:
  - overlay/accessibility/system-boundary flows are covered without live LLM dependency
  - `ACTION_DEBUG_EXEC` smoke is automated
- Dependencies: `qa-debug-harness`

### `qa-ci`

- Scope: `.github/workflows/qa.yml` (expansion of the minimal form from `qa-gradle-baseline`)
- Acceptance criteria:
  - `unit` and `android-smoke` run on PRs
  - optional lanes are separated from required PR gates
  - lane-promotion threshold is documented in the workflow file or adjacent docs
- Dependencies: `qa-smoke-suite`, `qa-system-suite`

### `qa-docs`

- Scope: `doc/dev/qa.md`, workflow docs, any local QA readme
- Acceptance criteria:
  - a contributor can go from clone to a green local smoke run using the docs
  - CI lane purpose and local commands are documented
- Dependencies: `qa-ci`

## Open Questions

1. Should the project add an automated **black-box release-smoke lane** after the deterministic `androidTest` stack is green, or keep release validation manual as described in `doc/dev/development.md`? Consensus: not in the PR gate either way. The only live question is whether a release-tag-triggered lane gets automated at all.
2. If a later release-smoke lane is added, should **Maestro** be the tool, and should its scope be limited to release-only risks (R8 / resource-shrink / signed-APK behavior) or expanded to a small set of broader user journeys? Consensus: if such a lane exists, its scope should stay narrow and release-focused. Tool choice currently leans toward Maestro because it can exercise a signed release APK black-box, but the team has not committed to automating that lane yet.

# QA Test Framework Design — androidagent

## Goal

Add a QA test layer that validates **user-facing behavior** of the Android Agent app — onboarding, permission setup, chat UX, settings flows, capsule/overlay — with tests that are cheap to write, stable across refactors, and runnable in CI.

Scope out:
- Unit tests (already covered: JUnit 4 + MockK + Truth in `app/src/test/`).
- Agent cognition / LLM quality (covered by `eval/` Python harness + `/autotune`).
- Ad-hoc visual debugging (covered by `/ux-visual-debug`).

What is missing: a repeatable **UI / E2E QA layer** that asserts the app works end-to-end on a device, independent of the LLM.

## Framework Selection

**Two-tier adoption:**

1. **Maestro** → primary E2E framework for user journeys.
2. **Jetpack Compose UI Test** (instrumented, `androidTest/`) → component-level UI tests for non-trivial composables.

**Why Maestro as primary**
- The app is accessibility-heavy and depends on system surfaces (a11y settings, permission dialogs, overlays over other apps). Maestro runs black-box through UI Automator + view hierarchy, so it handles system UI the way real users do — Espresso alone cannot.
- YAML flows (no Kotlin boilerplate) make it trivial for QA or non-Android devs to author/maintain tests. Matches our in-repo pattern of declarative configs (`eval/config/*.yaml`).
- Built-in retry + auto-wait for UI stability removes the flakiness tax that plain UI Automator suites suffer.
- CI artifacts (screenshots, screen recordings, JUnit reports) drop in without custom glue.
- Cross-platform story is irrelevant today (Android-only), but costs us nothing.

**Why Compose UI Test as second tier (not primary)**
- Good for asserting composable logic (state → rendering) on a single screen in isolation. Fast, deterministic, hermetic.
- But it cannot span activities-plus-system-UI, cannot exercise the accessibility service, and requires test rigs (fake ViewModels, DI swaps). We use it sparingly, only where component logic is non-trivial.

**Rejected:**
- **Espresso-only** — insufficient for cross-app / system-UI flows; Compose UI Test supersedes it for Compose.
- **Kaspresso** — a Kotlin DSL wrapper over Espresso + UI Automator. Adds a dependency and a DSL to learn, but the wins (Page Object, auto-retry) are already in Maestro + Compose Test. Don't add a third layer.
- **Appium** — heavy (WebDriver server + client), slower, no cross-platform need. Operational cost not justified.
- **UI Automator directly** — Maestro sits on top of it; writing raw UI Automator gives up Maestro's DX and reporting for no benefit.

## Directory Layout

```
androidagent/
├── qa/                          # NEW — Maestro flows (top-level, not inside app/)
│   ├── flows/
│   │   ├── onboarding/
│   │   │   ├── first_launch.yaml
│   │   │   └── grant_accessibility.yaml
│   │   ├── settings/
│   │   │   ├── set_openai_key.yaml
│   │   │   └── toggle_local_backend.yaml
│   │   ├── chat/
│   │   │   └── send_prompt_no_tools.yaml   # stubbed/offline run
│   │   └── smoke.yaml                       # top-level suite: runs the above
│   ├── helpers/
│   │   ├── reset_app_state.yaml            # clear data, reinstall perms
│   │   └── bypass_onboarding.yaml
│   ├── config.yaml                         # tags, env vars, executionOrder
│   └── README.md
│
├── app/src/androidTest/kotlin/com/moonkey/androidagent/   # NEW — Compose UI tests
│   └── ui/
│       ├── chat/ChatScreenTest.kt
│       ├── settings/SettingsHomePageTest.kt
│       └── onboarding/OnboardingFlowTest.kt
│
└── scripts/
    ├── qa.sh                    # NEW — wraps `maestro test qa/flows/...`
    └── setup.sh                 # existing — already primes a11y perms, reused
```

**Rationale**
- Maestro flows live at repo root (`qa/`), not under `app/src/`, because they are not Kotlin source and not Gradle-built. This mirrors how `eval/` sits outside `app/`.
- `androidTest/` is the first time this directory is introduced; it slots into the standard AGP layout so `./gradlew connectedDebugAndroidTest` wires up for free.

## Integration Plan

### Gradle wiring (Compose UI Test)

Add to `app/build.gradle.kts`:

```kotlin
defaultConfig {
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    // debugImplementation "androidx.compose.ui:ui-test-manifest" is already present (line 103).
}
```

No new Gradle plugin. `./gradlew connectedDebugAndroidTest` runs the suite against a connected device.

### Maestro wiring (no Gradle)

- Install: `curl -Ls "https://get.maestro.mobile.dev" | bash` (documented in `qa/README.md`).
- Run: `maestro test qa/flows/smoke.yaml`.
- `scripts/qa.sh` wraps this: builds debug APK, installs via existing `scripts/setup.sh` (so a11y perms are granted), then executes Maestro flows with consistent env vars.

### Test fixtures — agent/LLM isolation

UI/E2E tests must be **deterministic**; LLM calls are not. We isolate the agent via a debug-only **fake backend** toggle:

- `BuildConfig.QA_FAKE_LLM` (new `buildConfigField`, debug-only, opt-in via `-PqaFakeLlm=true`).
- When true, the LLM client factory returns a scripted fake that replays canned responses from `app/src/main/assets/qa_fixtures/*.json`.
- Maestro flows that exercise chat pass `-PqaFakeLlm=true` in `scripts/qa.sh`.

This turns the agent's non-determinism into a canonical case (deterministic fixture replay) rather than special-casing retries in every flow.

## Example Test Structure

**Maestro — `qa/flows/settings/set_openai_key.yaml`:**

```yaml
appId: com.moonkey.androidagent
tags: [smoke, settings]
---
- launchApp:
    clearState: true
- runFlow: ../../helpers/bypass_onboarding.yaml
- tapOn: "Settings"
- tapOn: "LLM Auth"
- tapOn:
    id: "openai_api_key_field"
- inputText: "sk-test-fixture-key"
- tapOn: "Save"
- assertVisible: "Saved"
- assertVisible:
    text: "sk-test-..."
    id: "openai_api_key_masked"
```

**Compose UI Test — `ChatScreenTest.kt`:**

```kotlin
@RunWith(AndroidJUnit4::class)
class ChatScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun userMessage_rendersInTranscript() {
        compose.setContent { ChatScreen(viewModel = FakeChatViewModel()) }
        compose.onNodeWithTag("chat_input").performTextInput("hello")
        compose.onNodeWithTag("send_button").performClick()
        compose.onNodeWithText("hello").assertIsDisplayed()
    }
}
```

**TestTag convention:** every composable targeted by QA gets a stable `Modifier.testTag("<screen>_<element>")`. These tags are identifiers, not accessibility labels, so they don't leak into production UX. This is the single contract between prod code and QA — enforced by lint rule in a later phase if needed.

## CI Wiring

No CI exists today (`.github/` is absent). Introduce it as part of this work.

**`.github/workflows/qa.yml`** — two jobs:

1. **`unit-and-compose`** (fast, every PR):
   - Ubuntu runner, JDK 17.
   - `./gradlew test` (existing unit tests).
   - `./gradlew connectedDebugAndroidTest` via `ReactiveCircus/android-emulator-runner@v2` (API 33 emulator).
   - ~8–12 min budget.

2. **`maestro-smoke`** (slower, main + nightly):
   - Same emulator action, then `scripts/qa.sh --suite smoke`.
   - Uploads `qa/.maestro/` run artifacts (screenshots, recordings, JUnit XML).
   - Optional: **Maestro Cloud** (`maestro cloud --apk app-debug.apk qa/flows/`) if team opts into SaaS — skips the emulator entirely.

PR gate: only `unit-and-compose` blocks merges. Maestro runs post-merge first to shake out flakiness, gates PRs once stable (tracked as a follow-up task).

## Rollout & Migration Strategy

Five phases, each independently mergeable. No phase depends on future-phase work.

| Phase | Deliverable | Exit criterion |
|---|---|---|
| 1. Scaffold | `qa/` dir, `scripts/qa.sh`, Gradle deps, `androidTest/` skeleton, `QA_FAKE_LLM` flag, one hello-world Maestro flow + one Compose test | `./gradlew connectedDebugAndroidTest` green; `maestro test qa/flows/smoke.yaml` green locally |
| 2. Smoke coverage | Maestro flows for: first-launch onboarding, a11y permission grant, set OpenAI key, send a chat message (fake LLM) | 4 flows passing; total runtime < 3 min on a warm emulator |
| 3. CI | `.github/workflows/qa.yml` with `unit-and-compose` as required PR check | One PR blocked by a deliberately broken test, then unblocked |
| 4. Component depth | Compose UI tests for `ChatScreen`, `SettingsHomePage`, `OnboardingFlow` — focus on state transitions, not pixel layout | ≥ 10 Compose test cases; coverage report noted in `doc/dev/` |
| 5. Journey depth | Maestro flows for settings sub-pages, capsule/overlay, error/retry paths; promote `maestro-smoke` job to required | Maestro flake rate < 2% across 20 consecutive CI runs; job promoted to required |

**Migration note:** nothing to migrate. No existing UI tests. We add greenfield.

**Human-in-the-loop vs. automated:** `/ux-visual-debug` stays as the **exploratory** tool (iterating on UX, reproducing user reports). Maestro is the **regression** tool (flows we've already validated and want to keep green). They are complementary, not redundant.

## Trade-offs & Alternatives

- **Maestro YAML vs. Kotlin tests for E2E.** YAML sacrifices type safety and IDE navigation. We accept this because (a) E2E flows are short and flat, (b) lower barrier for QA contributors, (c) black-box behavior is more stable across refactors than anything rooted in Compose node trees, (d) the concerns Kotlin would give us (reuse, helpers) are covered by Maestro's `runFlow` + env vars.
- **Fake LLM vs. recorded-replay vs. live.** We pick fake (scripted) because it's the simplest and keeps flows fast. Recorded-replay (capture real responses, replay in tests) is tempting but adds a capture/refresh workflow we don't need yet. Live LLM in UI tests is a non-starter — flaky, slow, and conflates agent quality with UI quality.
- **Why not reuse the Python `eval/` harness for UI QA?** `eval/` drives the agent via ADB + accessibility traces to measure *LLM decisions*. It doesn't assert on UI state, doesn't exercise onboarding/permissions/settings, and breaks if the UI changes mid-run. Wrong tool for the job.
- **`testTag` footprint in prod.** Small (`Modifier.testTag` compiles to a no-op `SemanticsModifier` in release). Worth it — cheapest stable locator.

## Tasks

Feeds `/update-tasks`.

1. **qa-scaffold** — scope: `app/build.gradle.kts`, `qa/**`, `scripts/qa.sh`, `app/src/androidTest/**`. Accept: Gradle syncs, `connectedDebugAndroidTest` runs an empty Compose test, `maestro test qa/flows/smoke.yaml` runs a hello-world flow. Deps: none.
2. **qa-fake-llm-flag** — scope: `app/build.gradle.kts`, LLM client factory, `app/src/main/assets/qa_fixtures/`. Accept: with `-PqaFakeLlm=true`, a chat message returns a scripted response; default builds untouched. Deps: none.
3. **qa-testtags** — scope: `app/src/main/kotlin/com/moonkey/androidagent/ui/{chat,settings,onboarding}/**`. Accept: every element targeted by phase-2 flows has a stable `testTag`. Deps: none.
4. **qa-smoke-flows** — scope: `qa/flows/{onboarding,settings,chat}/**`, `qa/helpers/**`. Accept: 4 flows listed in phase 2, all pass locally. Deps: qa-scaffold, qa-fake-llm-flag, qa-testtags.
5. **qa-ci** — scope: `.github/workflows/qa.yml`. Accept: `unit-and-compose` job runs on PR; one deliberately broken test blocks a PR. Deps: qa-scaffold, qa-smoke-flows.
6. **qa-compose-tests** — scope: `app/src/androidTest/kotlin/**`. Accept: ≥10 Compose UI test cases covering `ChatScreen`, `SettingsHomePage`, `OnboardingFlow`. Deps: qa-testtags.
7. **qa-journey-flows** — scope: `qa/flows/**`. Accept: flows for settings sub-pages, capsule/overlay, error paths; `maestro-smoke` CI job promoted to required after flake rate < 2% over 20 runs. Deps: qa-smoke-flows, qa-ci.
8. **qa-docs** — scope: `doc/dev/qa.md`, `qa/README.md`, update `CLAUDE.md`. Accept: contributor can go from clone to green QA run via docs alone. Deps: qa-scaffold.

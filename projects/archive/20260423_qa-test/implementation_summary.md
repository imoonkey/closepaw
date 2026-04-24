# QA Test — Implementation Summary

Status: bootstrap complete. behavior-guard layer in place across Chat, SmartCapsule, Settings.

## What was implemented

45 instrumented Compose tests under `app/src/androidTest/kotlin/ai/closepaw/qa/`:

| Batch | Area | Tests | Files |
|---|---|---|---|
| 0 | Harness | 1 | `SanityTest.kt` |
| 1 | Chat | 16 | `ChatEmptyStateTest`, `ChatHeaderTest`, `ChatBubbleAlignmentTest`, `ChatThinkingStateTest`, `ChatStreamingCursorTest`, `ActionCardStateIconTest`, `ActionCardExpandTest` |
| 2 | SmartCapsule | 15 | `CapsuleRenderingTest`, `CapsuleInputTest`, `CapsuleApprovalTest`, `CapsuleLifecycleTest`, `QaCapsuleHelpers` |
| 3 | Settings | 13 | `SettingsNavTest`, `SettingsLlmAuthTest`, `SettingsAgentBehaviorTest`, `SettingsPermissionsTest`, `QaSettingsHelpers`, `SettingsTestFixtures` |

Gradle baseline (`app/build.gradle.kts`):
- `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`
- `testOptions.animationsDisabled = true`
- `androidTestImplementation`: Compose BOM, `androidx.test.ext:junit`, `androidx.compose.ui:ui-test-junit4`

Production touchpoints (5 testTag additions):
- `ui/chat/components/ThinkingIndicator.kt` — `qa-thinking-indicator`
- `ui/chat/components/MessageBubble.kt` — `qa-user-bubble`, `qa-agent-bubble`
- `ui/capsule/surface/SmartCapsuleSurfaceParts.kt` — `qa-capsule-input`
- `ui/settings/PermissionsAdvancedSettingsPage.kt` — clear-traces dialog anchor
- `ui/settings/LlmAuthSettingsPage.kt` — `qa-executor-model-dropdown` (Sign-In + API-Key Pro branches)

## Key decisions

- **Behavior-guard, not bug-driven.** Bootstrap proactively guards behavior inventory; bugs add point guards later. Both modes produce the same kind of test.
- **Single deterministic runtime.** Compose UI Test + UI Automator if needed. No Maestro, Kaspresso, Appium.
- **Flat layout.** All tests in one directory, files grouped by area. No Robot pattern, no base classes, no annotations.
- **`org.junit.Assert` only.** Kotlin built-in `assert(...)` is a no-op without `-ea` and silently passes — banned for verdicts.
- **`testTag` only when needed.** Prefer text and contentDescription; add tag in production source only when those aren't stable selectors.
- **No CI yet.** Local `connectedDebugAndroidTest` is the canonical run path. CI lane comes later when the suite is stable enough to gate PRs.

## Review history

3 codex review rounds caught real regressions hidden in initially-green tests:

- **Batch 1** — Kotlin built-in `assert(...)` used for C2/C4/C5/C6 verdicts (silently no-op); fixed in `21e34233`.
- **Batch 2** — K11 used a test-local `LaunchedEffect { delay(3000); mode = Hidden }` instead of exercising production `CapsuleStateHolder.scheduleAutoHide`; rewritten to drive the real holder. Plus K1 weakened, K8 vacuous (`assertTextContains("")`). Fixes landed in working tree but were swept into a concurrent archive commit and then reverted by another session — re-applied in `5086b02d`.
- **Batch 3** — S9 false-positive: "Executor Model" section title in `LlmAuthSettingsPage.kt` made the test pass even when the dropdown disappeared, plus the "Basic hides it" half was missing. S11 only asserted dialog body text, not dialog semantics. Fixed in `ffae2baa`; second review pass APPROVE.

## Verification

```bash
adb devices
./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.package=ai.closepaw.qa
```

→ 45 tests green on device `EP0110MZ0BC101266W` (API 33).

## Deferred / not done

- **C12/C13 chat scroll FAB** — needs full `ChatScreen` + lazy scroll state simulation. Defer until either we hit FAB-related bug or another scroll-y component lands and shares the cost.
- **`QaSettingsHelpers.kt` slim-down** — 145 lines is bigger than KISS's "extract on 3rd repetition" suggests. Pure cleanup, not blocking.

## Next steps

- Behavior-guard growth is event-driven from here:
  - new UI behavior → reviewer asks for a test in the PR.
  - bug report → add a guard following the bug-stage workflow in `final/cn/design_kiss.md`.
- If the suite reaches ~100 tests with consistent green, consider promoting to a required CI lane (criteria in original `final/design.md` Lane Promotion Threshold section).

## References

- Design: `final/cn/design_kiss.md` (KISS framing) and `final/cn/design.md` (full aligned design).
- Bootstrap test list per area: `final/cn/bootstrap_plan.md`.
- Original input: `qa_test.md` (framework selection rationale).
- Two-agent design discussion: `discussion/`, `initial/`.

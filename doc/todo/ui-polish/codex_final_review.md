# Codex Final Review — `task/polish-impl` vs `d6b95153`

Reviewed:

- `git log --stat d6b95153..HEAD`
- `git diff d6b95153..HEAD`
- `doc/todo/ui-polish/polish_report.md`
- `doc/archive/20260420_frontend-ui-revamp/aligned/design_aligned.md`
- `doc/archive/20260420_frontend-ui-revamp/eng-design/track-a/final/design_aligned.md`
- `doc/archive/20260420_frontend-ui-revamp/motion-spec.md`

Verification:

- `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin` — passed

## Per-commit verdicts

| Commit | Verdict | Rationale |
| --- | --- | --- |
| `8a027442` | APPROVE | Doc-only diagnosis; matches commit scope and does not affect runtime behavior. |
| `d23537e8` | APPROVE | Correctly mirrors the existing stop/approval bridge pattern so chat-side Done clears capsule waiting state without touching reducer logic. |
| `65c20ea2` | APPROVE | Scoped `ON_RESUME` refresh for the repair banner; low-risk lifecycle change and build/tests still pass. |
| `aa8ee97d` | APPROVE | Serif identity pass is presentation-only and reuses existing type tokens instead of adding new ones. |
| `68150070` | REQUEST-CHANGES | The onboarding/settings/drawer swaps are good, but `ThinkingIndicator` misses motion spec section 4 on cadence, order, and tint. |
| `d730ab07` | REQUEST-CHANGES | CTA color cleanup is fine, but the `verticalScroll` wrapper likely breaks the existing bottom-pinned CTA invariant because the step bodies still rely on `Spacer(weight(1f))`. |
| `43c2a61f` | REQUEST-CHANGES | Banner/drawer/Local-tab changes are good, but the chat timestamp fix hard-pins `Locale.US` instead of using the app/display locale. |

## Per-finding spec-fidelity check

| Finding | Status | Check |
| --- | --- | --- |
| `#1` Onboarding chapter titles | PASS | `OnboardingShell` now renders the title with Fraunces at `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingShell.kt:99-103`. |
| `#2` Onboarding progress | PASS | The progress control is now 5 paws, each paw reuses `R.drawable.ic_paw`, and no new vector/drawable asset was added (`OnboardingShell.kt:116-133`). |
| `#3` Onboarding primary CTA color | PASS | The Moss overrides were removed from the satisfied/continue states, so onboarding primary CTAs now consistently fall back to the Claw-backed primary button style (`OnboardingSteps.kt:102-114`, `318-341`). |
| `#4` Onboarding landscape scrollability | FAIL | The new scroll path fixes clipping, but it does so by putting the whole shell inside a vertical scroll container (`OnboardingShell.kt:57-68`) while the step bodies still depend on `Spacer(weight(1f))` for bottom-pinned CTAs (`OnboardingSteps.kt:95`, plus the other step-body weight spacers). In Compose, that breaks the finite-height assumption those weights rely on. |
| `#6` Empty-state question typography | PASS | The empty-state question already resolves through `closePaw.serifItalic`, which matches the Fraunces-italic requirement (`EmptyState.kt:64-68`). |
| `#7` Empty-state title + paw watermark | PASS | The app name is now Fraunces and the paw watermark is materially larger/stronger (`EmptyState.kt:45-59`). |
| `#8` Thinking indicator | FAIL | `PawToeSequence` animates 5 phases over 900ms (`ThinkingIndicator.kt:48-59`) and starts with the pad active, while motion spec section 4 requires a 4-phase `toe → toe → toe → pad` sequence at `225/450/675/900ms`. It also tints the indicator with `primary`/Claw (`ThinkingIndicator.kt:39-40`) even though the spec calls for Ink alpha changes. |
| `#10` Trace ↔ Final hairline | PASS | `FinalSeparator()` renders a 1dp divider using `outlineVariant` (`MessageBubble.kt:261-269`), and `outlineVariant` is mapped to `InkGhost` in the theme (`ui/theme/Theme.kt:46-47`). |
| `#12` Setup Issue banner typography | PASS | Header is tracked-caps `labelSmall` and the body copy is `closePaw.monoBody`, which matches D1 section 6.5 (`PermissionRepairCard.kt:55-58`, `103-106`). |
| `#14` Timestamp locale | PARTIAL | The Chinese AM/PM leak is suppressed, but the fix is a hard `Locale.US` pin at `MessageBubble.kt:458-466`. That is heavier than necessary; the more targeted fix is to use the app/display locale or the platform time-format API. |
| `#15` Settings section heads | PASS | Settings title, sub-page title, and section headers now use Fraunces (`SettingsWidgets.kt:51-55`, `180-185`, `308-314`). |
| `#16` Settings trailing arrow | PASS | The row affordance is now a mono-styled text arrow, not a Material chevron (`SettingsWidgets.kt:279-283`). |
| `#18` Local tab | PASS | The Local tab is now explicitly disabled and dimmed rather than looking tappable-but-broken (`LlmAuthSettingsPage.kt:157-174`). |
| `#20` Navigation drawer ledger | PASS | Session timestamps and the settings subtitle now use `monoSmall`, the new-entry affordance is Claw-accented on border/icon/text, and delete icons are de-emphasized (`NavigationDrawer.kt:191-218`, `299-318`, `385-389`). |
| `B-1` Done in chat clears waiting state | PASS | The chat path now calls `CapsuleStateHolder.onUserResponseSent(...)` before forwarding to the view model (`MainActivityContent.kt:38-45`, `CapsuleBinding.kt:18-25`, `ChatScreen.kt:153-156`). |
| `B-2` Setup Issue banner refresh on resume | PASS | `MainActivity` now re-derives `repairModel` on `Lifecycle.Event.ON_RESUME`, which addresses the stale-banner-on-return bug (`MainActivity.kt:212-221`). |
| `INV-1` Capsule overlay diagnosis | DOCUMENTED | `doc/todo/ui-polish/capsule_investigation.md` is a useful diagnosis document. This branch does not attempt a runtime fix, which is consistent with the commit scope. |

## Cross-cutting observations

- Regression risk:
  - `ChatScreen.kt` only adds the user-response capsule bridge (`153-156`); it does not touch `ChatEventReducer`, row-state transitions, or the `89977c14` collapse/final-block logic.
  - `MessageBubble.kt` changes in this branch are presentation-only except for the timestamp formatter pin.
  - `MainActivity.kt:212-221` is a small lifecycle observer; it does not interfere with session startup, onboarding completion, or service binding.
  - `ThinkingIndicator` kept the existing QA tag (`ThinkingIndicator.kt:35`) and the surrounding agent-row semantics are unchanged (`MessageBubble.kt:132-147`). There is no dedicated `liveRegion` semantics before or after this rewrite, so I do not see an accessibility regression here, but I also do not see a new live-region behavior being added.
- Token discipline:
  - Good. No theme-token files were modified: `ui/theme/Type.kt`, `ui/theme/Tokens.kt`, `ui/theme/Theme.kt`, and `ui/theme/Motion.kt` are unchanged in `d6b95153..HEAD`.
  - The branch reuses existing `Fraunces`, `serifItalic`, `monoBody`, `monoSmall`, and `ClosePawMotion.Breath` instead of adding new typography or motion surface area.
- Test coverage:
  - No new tests were added for the `d23537e8` chat-to-capsule bridge or the `65c20ea2` resume refresh.
  - `app/src/androidTest/kotlin/ai/closepaw/qa/ChatThinkingStateTest.kt:23-45` only checks indicator presence/absence, so the cadence/order/tint drift in `ThinkingIndicator` would not be caught.
  - `app/src/androidTest/kotlin/ai/closepaw/qa/SettingsLlmAuthTest.kt:93-95` still assumes the Local tab is clickable. If the disabled-tab behavior is kept, that test should be updated to assert disabled state instead.

## Final merge recommendation

`FIX-FIRST`

### Specific files and changes before merge

1. `app/src/main/kotlin/ai/closepaw/ui/chat/components/ThinkingIndicator.kt:48-90`
   - Rework the sequence to match motion spec section 4 exactly: 4 phases over 900ms, `toe → toe → toe → pad`, `225ms` phase boundaries, and Ink alpha changes rather than Claw tint.

2. `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingShell.kt:57-68`
   - Replace the outer `verticalScroll` approach with a layout that keeps finite height for the step body on tall screens. The current structure makes the `Spacer(weight(1f))` pattern in `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingSteps.kt` unreliable, so CTAs will stop pinning to the bottom.

3. `app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:458-466`
   - Replace the `Locale.US` formatter pin with app/display-locale time formatting (`DateFormat.getTimeFormat(...)` or equivalent app-locale resolution) so the fix stays localized to formatting rather than forcing US English.

## Round 2 re-review

Reviewed `4a44053e..HEAD`:

- `4d0e1168` `fix(ui): paw-toe thinking indicator cadence and tint per motion spec §4 (#8)`
- `8069e5fc` `fix(onboarding): preserve bottom-pin while allowing scroll on short heights (#4)`
- `784b8b2e` `fix(ui): timestamp uses app DateFormat instead of hard Locale.US pin (#14)`
- `89096886` `test(qa): SettingsLlmAuthTest reflects disabled Local tab (#18)`

| Finding | Verdict | Verification |
| --- | --- | --- |
| `#8` ThinkingIndicator | RESOLVED | `app/src/main/kotlin/ai/closepaw/ui/chat/components/ThinkingIndicator.kt:47-91` now animates four phases over `ClosePawMotion.Breath` (`app/src/main/kotlin/ai/closepaw/ui/theme/Motion.kt:10-16` = `900ms`), with draw order `toe₁ → toe₂ → toe₃ → pad`, cumulative `30% → 100%` alpha via `index <= active`, and `onSurface` tint instead of `primary`. `Theme.kt` maps `onSurface` to Ink. |
| `#4` Onboarding scroll | RESOLVED | `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingShell.kt:58-72` now uses two layout modes: `<480.dp` height stays scrollable, while taller screens switch back to a finite-height shell via `fillMaxHeight()` with no outer scroll modifier. That restores the existing step-body `Spacer(weight(1f))` bottom-pin behavior on tall screens (for example `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingSteps.kt:95`) while still allowing short heights to scroll. |
| `#14` Timestamp | RESOLVED | `app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:458-462` removes the `Locale.US` pin and uses `android.text.format.DateFormat.getTimeFormat(context)` via `LocalContext.current`, which is app/device-locale aware. |
| `#18` Settings LLM auth test | RESOLVED | `app/src/androidTest/kotlin/ai/closepaw/qa/SettingsLlmAuthTest.kt:94-98` no longer clicks `Local`; it asserts disabled state instead, matching `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:157-174`, where the Local tab is explicitly `enabled = false`. |

Verification:

- `./gradlew :app:assembleDebug` — passed
- `./gradlew :app:testDebugUnitTest` — passed
- `./gradlew :app:compileDebugAndroidTestKotlin` — passed

### Updated final merge recommendation

`MERGE`

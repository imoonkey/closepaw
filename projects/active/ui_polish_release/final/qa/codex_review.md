# Codex Review: ui-polish-integration -> main

## Summary
Independent static review found one P0 blocker and one P1 correctness gap: the capsule approval state still uses a single non-wrapping required-action row that will not fit the 360dp / fontScale 1.5 target, and overlay voice failures are routed into a default no-op feedback sink. The main settings/memory/drawer fixes are mostly aligned, but several P2 items claimed by the design/task text remain incomplete.

## Critical (must fix before merge)
1. `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleControlBar.kt:121` - `ActionButtonCluster` is still a single `Row` with `Arrangement.spacedBy(...)`, and `WaitingForApproval` renders three text+icon buttons through that same non-wrapping path at `CapsuleControlBar.kt:126`, `CapsuleControlBar.kt:155`, and `CapsuleControlBar.kt:167`. On a 360dp main-app surface, `smartCapsuleHostPadding()` already removes 32dp horizontally and the capsule body removes another 32dp at `SmartCapsuleSurface.kt:135`, leaving about 296dp for all three approval buttons before fontScale 1.5 expansion. This misses P0-2/D1's no-clipping gate. Minimal fix: for `requiredActionsOnly`, use a wrapping `FlowRow` or stack the approval actions into two rows/vertical buttons while keeping nav hidden.

## High (should fix before merge)
1. `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleInputBar.kt:76` - `LocalVoiceFeedback` defaults to `{}`, and overlay composition never overrides it. Main chat provides a `SettingsAlertCard` sink at `ChatScreen.kt:248` and `ChatScreen.kt:255`, but `CapsuleOverlayHost.kt:138` calls `SmartCapsuleSurface(... voice = voiceDeps)` with no `CompositionLocalProvider`. As a result, recognizer and permission messages emitted at `CapsuleInputBar.kt:141`, `CapsuleInputBar.kt:158`, `CapsuleInputBar.kt:224`, and `CapsuleInputBar.kt:232` are visible in main app but silently dropped in overlay. Minimal fix: provide `LocalVoiceFeedback` in `CapsuleOverlayHost`, either as an overlay-visible alert or a `Toast` fallback.

## Medium (worth addressing)
1. `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingSteps.kt:203` - the onboarding provider picker remains a fixed horizontal `Row`, with each provider rendered as a `FilterChip` at `OnboardingSteps.kt:208`. P2-4 explicitly called for provider/auth choices to wrap under large font; this code still has no wrap path at 360dp / fontScale 1.5. Minimal fix: replace the fixed row with `FlowRow` for the provider choices.
2. `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/ActionVisualizerCompose.kt:56` - D6 says P2-7 ships reduced-motion compliance only, but the visualizer still starts an unconditional `withFrameMillis` loop while items exist and interpolates expanding markers at `ActionVisualizerCompose.kt:70`. Minimal fix: read `ClosePawMotion.reducedMotion()` and render static/final markers without the frame loop when reduced motion is enabled.
3. `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthDiscoveryUi.kt:195` - P2-8 remains incomplete: picker rows use bare `clickable` without selectable/radio semantics or explicit 48dp minimum, and the refresh metadata remains in the same row as the button at `LlmAuthDiscoveryUi.kt:247`. Minimal fix: use `selectable(role = Role.RadioButton)` plus selected/state semantics for model rows, add a 48dp minimum target, and let refresh metadata wrap below the button on narrow width.

## Low (nice-to-have)
None.

## Acceptance criteria gaps
| Item | Result | Notes |
| --- | --- | --- |
| P0-1 / D7 Onboarding completion shell | PASS | `WizardStep.Complete` routes through `OnboardingShell`; duplicate complete title is removed. |
| P0-2 / D1 Capsule attention controls | GAP | Nav is omitted from rendered attention layout, but the required approval actions remain one non-wrapping row; see Critical finding. |
| P1-1 Interactive target and semantics sweep | PASS | Scoped surfaces have roles/labels or Material semantics; the remaining blocker is the P0-2 required-action layout, not missing semantics. |
| P1-2 Capsule status row semantics parity | PASS | `CapsuleStatusLine` adds `Role.Button` and a stable "Capsule status" label when clickable. |
| P1-3 / D2 Paper grain strength and layering | PASS | Strength is `0.015f`; Chat and Settings use sibling background boxes behind content. |
| P1-4 App Access memory create failure/full editor | PASS | Create failure uses `SettingsAlertCard`; per-app full editor route is wired; inline `Open` remains disabled while dirty. |
| P1-5 Unbounded memory editor fills page | PASS | Page surface and unbounded editor use `weight(1f)`/`fillMaxSize()` so the field fills remaining height. |
| P1-6 OTHER provider validation | PASS | Base URL and custom model fields use validator errors via `isError` and `supportingText`. |
| P1-7 / D4 Voice feedback | GAP | Main chat uses `SettingsAlertCard`, but overlay feedback drops into the no-op default; see High finding. |
| P1-8 Session drawer active/corrupted state | PASS | Active/corrupted treatments exist; corrupted row has normal open removed while delete remains available. |
| P1-9 Destructive clear actions | PASS | `deleteRecursively()` result is checked; failure returns `Failed` and does not set `Cleared`. |
| P2-1 Loading/empty/error states | PASS | App Access and licenses now render explicit loading/empty/error cards. |
| P2-2 Settings card consistency | GAP | Debug/trace rows still use hand-rolled surfaces; no obvious regression, but the item is not completed. |
| P2-3 / D3 Shape token cleanup/user bubble outline | PASS | Theme shapes replace the targeted literals, and user bubble has the 1dp Ink alpha outline. |
| P2-4 Onboarding typography/wrapping/paper | GAP | Fraunces title landed, but provider choices still do not wrap; see Medium finding. |
| P2-5 Empty-state hero paw responsive check | PASS | Deferred open question; no code change was required without a screenshot repro. |
| P2-6 Status color contrast snapshots | PASS | Deferred open question; no token change required without failing snapshots. |
| P2-7 / D6 Reduced-motion visualizer | GAP | `ActionVisualizerCompose` still runs frame animation without reduced-motion branching. |
| P2-8 LLM discovery picker targets/wrapping | GAP | Picker rows and refresh row still miss the requested selectable/48dp/wrapping treatment. |
| P2-9 Island/capsule marquee consistency | PASS | Deferred pending screenshot check; changed code did not worsen this path. |
| P2-10 Message and ledger edge cases | PASS | Conditional item; no repro evidence in this diff. |
| P2-11 Overlay inset helper | PASS | Not touched by this PR and not required by a resolved decision. |
| P2-12 Capsule and alert polish | PASS | Consideration item only; no blocking regression found. |
| P2-13 Local tab warning tone | PASS | Explicitly deferred; `LOCAL_TAB_ENABLED = false`, so users cannot reach it. |
| D1 Capsule attention layout | GAP | Same as P0-2: nav is hidden, but required action row still cannot meet the narrow-width no-clipping gate. |
| D2 Paper grain modifier semantics | PASS | `paperGrain` remains overlay; call sites place it as a background sibling. |
| D3 User bubble material | PASS | 1dp Ink alpha outline is present. |
| D4 Failure/warning feedback surface | GAP | Settings and main chat paths use `SettingsAlertCard`; overlay voice path has no visible fallback. |
| D5 App Access filters | PASS | Filter row uses `SingleChoiceSegmentedButtonRow`. |
| D6 Action visualizer reduced-motion scope | GAP | No reduced-motion implementation in `ActionVisualizerCompose`. |
| D7 Onboarding shell scrolling | PASS | `OnboardingShell` uses a simple always-scroll model with `imePadding()`. |

## Recommendation
BLOCK
The P0 capsule attention layout still misses the release-blocker acceptance gate, and P1-7 is incomplete for overlay users. Fix those before merge; the P2 gaps can be handled in the same follow-up or explicitly moved out of this release scope.

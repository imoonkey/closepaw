# UI/UX Quality Improvement Plan (Codex)

## Goal
Raise the `ui/` module to a stricter standard without a redesign:
- one clear state owner per feature
- no composition-time side effects
- predictable overlay behavior
- chat behavior that respects streaming and user position
- better accessibility and theme consistency

## Principles
- Fix correctness and state ownership before visual polish.
- Prefer smaller, explicit state models over more remembered local state.
- Keep renderer composables dumb; keep orchestration outside them.
- Preserve the existing visual language unless a bug requires a behavior change.

## Phase 1: Compose Correctness In Shared Surfaces
Priority: `P0`

Targets:
- `app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurface.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/onboarding/OnboardingScreen.kt`

Tasks:
- Remove all state writes from `SmartCapsuleSurface` composition.
- Stop updating `previousModeState` from inside `remember`.
- Replace composition-time input clearing with an effect or caller-owned state transition.
- Key the onboarding effect collector to `effects`, not `Unit`.

Expected outcome:
- Shared capsule rendering becomes deterministic across app and overlay.
- Recomposition stops carrying hidden behavioral side effects.

Verification:
- Add Compose tests for capsule transitions:
  - `Hidden -> WaitingForInput` clears input exactly once.
  - `WaitingForInput -> Running` does not re-clear unrelated draft text.
- Manually verify no repeated focus/input glitches in overlay input mode.

## Phase 2: Hoist Settings Navigation And Auth State
Priority: `P0`

Targets:
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsSheet.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/LlmAuthSettingsPage.kt`

Tasks:
- Introduce explicit settings navigation/auth state instead of implicit remembered state.
- Make page, tab, and provider selection either:
  - fully hoisted from the parent, or
  - local but correctly keyed and `rememberSaveable`.
- Remove “initialize once from external state” patterns for `selectedTab` and `selectedProvider`.

Expected outcome:
- The settings UI reflects the real backend/auth state after external changes.
- Rotation and recreation no longer reset users to the wrong place.

Verification:
- Test matrix:
  - switch between OAuth/API key/local
  - change selected model externally, reopen settings
  - rotate while on a nested settings page
  - return from OAuth and confirm the shown tab is correct

## Phase 3: Fix Chat Scroll Behavior And Simplify Chat State
Priority: `P1`

Targets:
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatEventReducer.kt`

Tasks:
- Replace “scroll on `messages.size` change” with an explicit bottom-stickiness policy.
- Track whether the user is near the bottom before auto-scrolling.
- Scroll on last-message growth when the user is already following the conversation.
- As a follow-up simplification, move toward one chat UI state model instead of mixing:
  - `StateFlow<ChatUiState>`
  - `SnapshotStateList<ChatMessage>`
  - `StringBuilder`
  - manual locking

Expected outcome:
- Streaming replies stay visible when the user expects them to.
- The UI stops yanking users away from older history.
- Chat state becomes easier to test and reason about.

Verification:
- Manual scenarios:
  - long streaming response that exceeds one screen
  - action cards added mid-stream
  - user scrolls up while the agent continues
- Add reducer/screen tests around scroll-trigger conditions.

## Phase 4: Unify Capsule/Overlay State Ownership
Priority: `P1`

Targets:
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/CapsuleOverlayHost.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/IslandOverlayHost.kt`

Tasks:
- Define one canonical capsule UI state contract.
- Keep `CapsuleStateHolder` as the single owner for mode/context/platform/island-relevant state.
- Remove duplicate render-input flows from `CapsuleOverlayHost`.
- Keep hosts focused on:
  - showing/hiding windows
  - focusability
  - touchability
  - pass-through behavior

Expected outcome:
- Main-app capsule, overlay capsule, and island derive from the same source.
- The host layer becomes simpler and less bug-prone.

Verification:
- Manual matrix:
  - main app
  - accessibility overlay
  - viewer open/close
  - background/island transitions
  - waiting-for-input / waiting-for-approval / done / error

## Phase 5: Accessibility Hardening
Priority: `P1`

Targets:
- `app/src/main/kotlin/com/moonkey/androidagent/ui/onboarding/OnboardingShell.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurfaceParts.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/StatusIslandCompose.kt`
- representative clickable rows in settings/navigation/chat

Tasks:
- Replace icon-with-`clickable` patterns with proper button primitives.
- Add `contentDescription` to every icon-only action.
- Add semantics/roles to custom clickable surfaces where Material defaults are bypassed.
- Audit touch targets for back, nav, delete, and overlay controls.

Expected outcome:
- TalkBack and switch-access behavior improves materially.
- The app stops relying on visual-only affordances.

Verification:
- TalkBack pass on:
  - onboarding
  - settings
  - chat header/drawer
  - capsule overlay and island

## Phase 6: Theme And Token Cleanup
Priority: `P2`

Targets:
- `app/src/main/kotlin/com/moonkey/androidagent/ui/theme/Color.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/theme/Shape.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/StatusIslandCompose.kt`

Tasks:
- Delete unused color and shape tokens.
- Make island/overlay surfaces consume canonical theme tokens instead of hardcoded colors.
- Standardize which tokens are public and which are implementation detail.

Expected outcome:
- Theme decisions become easier to maintain.
- Dark/light consistency improves.

Verification:
- Screenshot compare for chat, settings, onboarding, and overlay in light and dark themes.

## Phase 7: Decompose Large UI Files
Priority: `P2`

Targets:
- `app/src/main/kotlin/com/moonkey/androidagent/ui/onboarding/OnboardingSteps.kt`

Tasks:
- Split into:
  - `PermissionStepContent`
  - `ApiKeyStepContent`
  - `DemoStepContent`
  - `CompleteStepContent`
  - shared onboarding primitives/copy
- Keep each file under the project size guideline where practical.

Expected outcome:
- Lower change risk in onboarding.
- Cleaner ownership of step-specific behavior and copy.

Verification:
- No functional change in onboarding flow.
- Simpler previews/tests per step.

## Phase 8: State Preservation And Resource Hygiene
Priority: `P3`

Targets:
- settings, onboarding, capsule input, chat leaf state

Tasks:
- Add `rememberSaveable` where local state should survive config changes.
- Start extracting user-facing strings into resources.
- Standardize time formatting utilities and remove ad-hoc `SimpleDateFormat` use.

Expected outcome:
- Better resilience to activity recreation.
- Cleaner path to localization and consistent copy.

Verification:
- Rotation/recreation checks on settings, onboarding, and chat.

## Suggested Execution Order
1. Phase 1
2. Phase 2
3. Phase 3
4. Phase 4
5. Phase 5
6. Phase 6
7. Phase 7
8. Phase 8

## Notes
- Do not start with a theme pass. The current biggest problems are state correctness and ownership, not colors.
- The first two phases should happen before any new capsule/settings feature work.
- If implementation effort needs to be minimized, Phase 1 plus Phase 2 deliver the highest immediate quality gain.

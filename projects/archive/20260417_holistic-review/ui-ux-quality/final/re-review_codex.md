# UI/UX Plan Re-review Against Current Code

Reviewed current source against `doc/todo/holistic-review/ui-ux-quality/final/improvement_plan.md` written on 2026-04-08. Code is the source of truth.

## Phase 1
Verdict: STILL_REAL

Evidence
- `app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurface.kt:65-81` still mutates local state during composition:

```kt
var inputText by remember { mutableStateOf("") }
val previousModeState = remember { mutableStateOf<CapsuleMode?>(null) }
val renderSpec = remember(mode, isStopPending, resolvedPreviousMode, transientThought) {
    val baseSpec = CapsuleRenderSpec.from(mode, resolvedPreviousMode, isStopPending)
    previousModeState.value = mode
    ...
}
if (renderSpec.row3?.clearInput == true && inputText.isNotEmpty()) {
    inputText = ""
}
```

- `app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/SmartCapsuleCompose.kt:41-56` still does not pass `previousMode`, so the in-app path still relies on the fallback state inside `SmartCapsuleSurface`.
- `app/src/main/kotlin/com/moonkey/androidagent/ui/onboarding/OnboardingScreen.kt:45-48` still uses `LaunchedEffect(Unit)` for the effect collector.
- `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:56-57` shows `effects` is currently a stable property, so the onboarding collector key issue is real but much less urgent than the capsule composition writes.

Recommended changes to the plan
- Keep the first three capsule tasks. They are still the highest-ROI part of the whole plan.
- Modify the implementation approach: remove `previousModeState` from `SmartCapsuleSurface` entirely; make callers provide previous mode or move input clearing into a keyed effect on mode transition.
- Reprioritize the onboarding `LaunchedEffect(effects)` cleanup downward. It is worth fixing, but it is not a P0 blocker today.

New issues found during the review
- The in-app capsule path is missing `previousMode` plumbing entirely. Fixing only the overlay call site would leave the main-app path inconsistent.

## Phase 2
Verdict: STILL_REAL

Evidence
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsSheet.kt:70` still keeps page selection in local `remember` state:

```kt
var settingsPage by remember { mutableStateOf(SettingsPage.HOME) }
```

- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/LlmAuthSettingsPage.kt:72-79` initializes `selectedTab` once from external state and then lets it drift.
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/LlmAuthSettingsPage.kt:88-112` tab taps still immediately fire `onBackendChange` and `onAuthMethodChange`.
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/LlmAuthSettingsPage.kt:225-229` and `239-250` do the same for API-key provider selection: exploratory UI state and committed backend/model mutations are still coupled.

Recommended changes to the plan
- Keep the phase. The plan still matches the code.
- Prefer hoisted or `rememberSaveable` page/tab/provider state over one-time `remember` initialization.
- Keep the “separate exploratory selection from committed mutation” task exactly as written.
- Reprioritize slightly: still high priority, but Phase 1 and Phase 3 are more immediately user-visible.

New issues found during the review
- None beyond the original plan.

## Phase 3
Verdict: STILL_REAL

Evidence
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt:191-196` still auto-scrolls only on `messages.size`:

```kt
LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) {
        listState.animateScrollToItem(messages.size - 1)
    }
}
```

- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatEventReducer.kt:68-71` updates the last agent message in place while streaming.
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatEventReducer.kt:95-98` appends action cards to the last agent message without increasing `messages.size`.
- That means long deltas and mid-stream action cards can grow off-screen without triggering the current scroll effect.
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/components/MessageBubble.kt:188-190` still creates a `SimpleDateFormat` per call.
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/components/ActionCard.kt:171-184` still wraps `CircularProgressIndicator` in an extra infinite rotation animation.
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:108-132` still keeps chat state split across `MutableStateFlow`, `SnapshotStateList`, `StringBuilder`, and a lock.

Recommended changes to the plan
- Keep the scroll/following/FAB work. The suggested approach is still the right one.
- Keep the `SimpleDateFormat` cleanup, but fold it into this phase instead of Phase 8.
- Keep chat-state simplification focused on scroll/follow behavior and last-message growth. Do not turn this into decomposition-for-its-own-sake.

New issues found during the review
- `ActionState.Executing` appears to have UI but no live producer in the reducer path. `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatEventReducer.kt:84-98` and `101-129` only emit `Proposed`, `Success`, or `Failed`. If this area is touched, either start emitting `Executing` or delete the dead branch.

## Phase 3.5
Verdict: STILL_REAL

Evidence
- `app/src/main/kotlin/com/moonkey/androidagent/ui/navigation/NavigationDrawer.kt:127-130` wires deletion directly.
- `app/src/main/kotlin/com/moonkey/androidagent/ui/navigation/NavigationDrawer.kt:274-283` still deletes from a single tap with no confirmation dialog.

Recommended changes to the plan
- Keep the phase.
- Reprioritize upward by ROI: it is a tiny change with immediate protection against destructive mistakes.

New issues found during the review
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/PermissionsAdvancedSettingsPage.kt:208-227` has two more destructive actions, `Clear Traces` and `Clear Session History`, that also execute immediately with no confirmation and no failure feedback. This same safeguard should cover those buttons too.

## Phase 4
Verdict: PARTIALLY_DONE

Evidence
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt:44-58` and `95-101` already centralize `mode`, `context`, `platformMode`, and `turnPhase`.
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/IslandOverlayHost.kt:53-57` already renders from `stateHolder.mode` and `stateHolder.turnPhase`.
- But `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/CapsuleOverlayHost.kt:73-76` still keeps its own `capsuleContext`, `platformMode`, and `hasIsland` flows.
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/CapsuleOverlayHost.kt:213-221` still exposes `updateNavContext(...)` to mutate those local flows.
- `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:376-383` still writes the same render inputs into both `stateHolder` and `capsuleManager`, so ownership is still duplicated.

Recommended changes to the plan
- Keep the phase, but narrow it.
- Unify render-driving `context` / `platformMode` / `hasIsland` state.
- Do not force host-only concerns like focusability, touch passthrough, or interaction locks into `CapsuleStateHolder`; those are still correctly host-owned.
- Lower the priority relative to Phases 1, 2, 3, and 3.5 because the groundwork is already in place.

New issues found during the review
- `CapsuleStateHolder.context` currently reads like canonical state, but the overlay host is not actually consuming it. The API is more unified than the implementation.

## Phase 5
Verdict: PARTIALLY_DONE

Evidence
- `app/src/main/kotlin/com/moonkey/androidagent/ui/onboarding/OnboardingShell.kt:54-61` still uses an icon with raw `clickable` at `24.dp` for the back action.
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/StatusIslandCompose.kt:30-43` still uses a clickable `Surface` with no explicit role or action label, and only `defaultMinSize(minHeight = 32.dp)`.
- `app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurfaceParts.kt:255-266` uses a custom `NavIconButton` with `contentDescription = null` for icon-only capsule navigation actions.
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsWidgets.kt:199-203` and `253-257` still use custom clickable surfaces for settings rows with no explicit role.
- Some broad items are already fixed/stale:
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/components/ChatHeader.kt:57-90` already uses proper `IconButton`s with descriptions.
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/navigation/NavigationDrawer.kt:171-176` and `274-283` already use `IconButton`s with descriptions for close/delete.

Recommended changes to the plan
- Keep the phase, but modify the approach.
- Fix icon-only actions first: onboarding back, status island tap target, capsule nav buttons.
- For full-width rows, do not replace everything with button primitives. Adding `role = Role.Button` and clear semantics is usually the simpler, higher-ROI fix.
- Keep the phase behind the scroll/settings correctness work, but ahead of theme-token cleanup.

New issues found during the review
- `StatusIslandCompose` is both an accessibility issue and a theming issue; fixing it once should cover both Phase 5 and Phase 6 concerns.

## Phase 6
Verdict: LOW_ROI

Evidence
- `app/src/main/kotlin/com/moonkey/androidagent/ui/theme/Color.kt:12-59` still contains the old non-`Chat*` token set.
- `app/src/main/kotlin/com/moonkey/androidagent/ui/theme/Theme.kt:22-127` uses the `Chat*` token set, not the old general tokens.
- `app/src/main/kotlin/com/moonkey/androidagent/ui/theme/Shape.kt:58-83` still defines `CapsuleShape`, `PillShape`, `CardShape`, `InputShape`, and `SheetShape`; repo-wide search found no usages outside `Shape.kt`.
- The only clearly user-visible problem in this phase is `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/StatusIslandCompose.kt:35` and `55`, which still hardcode light colors.

Recommended changes to the plan
- Drop the dedicated token-purge phase from the UI/UX plan.
- Keep one narrow follow-up: make `StatusIslandCompose` use theme colors and shapes instead of `Color.White` and `Color(0xFF171717)`.
- Treat dead token/shape deletion as opportunistic cleanup when touching theme files, not as scheduled UX work.

New issues found during the review
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/IslandOverlayHost.kt:47-50` still uses a hardcoded fallback blue dot as well.

## Phase 7
Verdict: LOW_ROI

Evidence
- `app/src/main/kotlin/com/moonkey/androidagent/ui/onboarding/OnboardingSteps.kt` is still 735 lines.
- The file is already internally segmented into step-specific composables at lines `62`, `148`, `450`, `541`, `594`, `627`, `644`, `665`, and `715`.

Recommended changes to the plan
- Reprioritize this well below the real UX bugs.
- If onboarding work is touched again, split the file then. As standalone work, this is mostly mechanical cleanup with little user-facing value.
- If the team wants to enforce the `< 400 lines/file` rule consistently, track that as codebase maintenance rather than UI/UX improvement.

New issues found during the review
- The more important onboarding issue is not file size: `app/src/main/kotlin/com/moonkey/androidagent/ui/onboarding/OnboardingShell.kt:44-49` has no scroll or IME handling, and the API-key step in `app/src/main/kotlin/com/moonkey/androidagent/ui/onboarding/OnboardingSteps.kt:172-231` and `362-445` is not scrollable. On smaller devices or with the keyboard open, the bottom CTA can be pushed off-screen.

## Phase 8
Verdict: LOW_ROI

Evidence
- There is still no `rememberSaveable` usage anywhere under `ui/` from repo-wide search.
- The real saveability gaps are already concentrated in Phase 2 files: `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsSheet.kt:70` and `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/LlmAuthSettingsPage.kt:72-79` and `226-229`.
- Other local `remember` state exists, but much of it is intentionally ephemeral: `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/components/ActionCard.kt:63`, `app/src/main/kotlin/com/moonkey/androidagent/ui/onboarding/OnboardingSteps.kt:162`, and `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/PermissionsAdvancedSettingsPage.kt:150-151`.
- `PerceptionMode` is still stringly typed across app, activity, settings, and session plumbing:
  - `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:64`, `139-142`, `270-271`
  - `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsState.kt:33`, `201-203`
  - `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:620-626`
  - `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityIntentPayload.kt:150-160`
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsWidgets.kt:327-330`
  - `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsHomePage.kt:97-100`
- Duplicate version display is still present in `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsHomePage.kt:64-66` and `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/PermissionsAdvancedSettingsPage.kt:128-129`.
- `SimpleDateFormat` is no longer a broad UI pattern; the only user-facing UI usage left is `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/components/MessageBubble.kt:188-190`, while `app/src/main/kotlin/com/moonkey/androidagent/ui/session/TimeUtils.kt:13-64` already standardized session-list formatting with `DateTimeFormatter`.

Recommended changes to the plan
- Break this phase apart instead of keeping it as one bucket.
- Fold the real `rememberSaveable` work into Phase 2.
- Fold the `SimpleDateFormat` fix into Phase 3.
- Keep “remove duplicate version display” as a tiny opportunistic cleanup.
- Drop “extract user-facing strings into resources” from this UI/UX plan unless localization is an active goal.
- Lower or drop the full `PerceptionMode` enum migration from this plan; it is a cross-layer type-safety cleanup, not an urgent UX fix.

New issues found during the review
- None beyond the need to split this phase into smaller, more honest tasks.

## Cross-cutting Call
- Keep and prioritize: Phase 1, Phase 2, Phase 3, Phase 3.5.
- Keep but narrow: Phase 4, Phase 5.
- Drop or deflate as standalone phases: Phase 6, Phase 7, Phase 8.
- Add new work not in the original plan:
  - confirmation for destructive storage-clear actions in settings
  - onboarding API-key step scroll and IME handling

# UI/UX Code Quality Review (Codex)

## Scope
- Reviewed: `app/src/main/kotlin/com/moonkey/androidagent/ui/**`
- Angle: Compose stability/recomposition, ViewModel patterns, side effects, overlay architecture, capsule state machine, theme consistency, accessibility, KISS
- Excluded: other `doc/todo/` design docs

## Method
- Read all files in the `ui/` module.
- Cross-checked state ownership, side effects, and renderer boundaries across chat, overlay/capsule, settings, onboarding, theme, navigation, and viewer paths.
- Sanity check: `./gradlew :app:compileDebugKotlin` passed on 2026-04-08.

## Executive Summary
The module has a solid visual foundation and a few strong architectural choices:
- `CapsuleMode` is a clear state-machine core.
- `SmartCapsuleSurface` is shared between in-app and overlay rendering.
- Chat messages model interleaved text/actions cleanly.
- Settings already has reusable row/dropdown primitives.

The main quality problem is state ownership drift. Several important screens still mix source-of-truth state, remembered local state, and UI-only side effects in ways that are easy to desync and hard to reason about. The biggest risks are in the shared capsule surface, settings navigation/auth flows, chat scrolling, and overlay host ownership.

Recommendation: `CHANGES_REQUESTED` before further UI expansion. The codebase is fixable without a rewrite, but the current patterns will compound if left in place.

## What Is Working
- The capsule state model is conceptually strong: `CapsuleMode` is explicit and avoids boolean soup (`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/model/CapsuleMode.kt`).
- Shared rendering between app and overlay is the right direction (`app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/SmartCapsuleCompose.kt`, `app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurface.kt`).
- Chat action rendering is modular and readable (`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/model/ChatMessage.kt`, `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/components/ActionCard.kt`).
- Overlay window setup is usefully wrapped in a small host abstraction (`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/OverlayComposeHost.kt`).
- The theme is visually coherent at the Material 3 layer (`app/src/main/kotlin/com/moonkey/androidagent/ui/theme/Theme.kt`).

## High Severity Findings

### 1. `SmartCapsuleSurface` mutates state during composition
Evidence:
- `app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurface.kt:67-74`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurface.kt:79-80`

Problems:
- `previousModeState.value = mode` is written from inside the `remember(...)` calculation.
- `inputText = ""` is written directly during composition when `clearInput` is true.

Why this matters:
- This breaks Compose’s “render should be side-effect free” model.
- It makes recomposition order part of the behavior.
- The bug is amplified because this surface is used in both the main app and the overlay path.

Direction:
- Keep `CapsuleRenderSpec.from(...)` pure.
- Hoist previous-mode tracking outside the composable or update it from `LaunchedEffect`.
- Clear input from an effect or by replacing the state holder, not by writing during composition.

### 2. Settings page/tab/provider state can drift away from real app state
Evidence:
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/SettingsSheet.kt:68`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/LlmAuthSettingsPage.kt:72-79`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/settings/LlmAuthSettingsPage.kt:226-229`

Problems:
- `settingsPage`, `selectedTab`, and `selectedProvider` are locally remembered UI state.
- `selectedTab` and `selectedProvider` are seeded from external inputs once, then stop tracking those inputs.
- None of this is `rememberSaveable`.

Why this matters:
- External state changes can leave the UI showing the wrong tab/provider.
- Rotation or activity recreation drops the user back to default settings navigation.
- The settings sheet is acting as state owner and renderer at the same time.

Direction:
- Hoist settings navigation/auth selection into explicit state.
- If state remains local, key it correctly and use `rememberSaveable` where user intent should survive config changes.

### 3. Chat auto-scroll behavior is incorrect for streaming conversations
Evidence:
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt:189-195`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatEventReducer.kt:64-72`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatEventReducer.kt:95-99`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatEventReducer.kt:101-129`

Problems:
- The list auto-scrolls only when `messages.size` changes.
- Streaming text and action-card updates mutate the last message without changing list size.
- New messages always animate to bottom, even if the user is reading older history.

Why this matters:
- Long streaming answers can grow below the fold without the viewport following them.
- The UI also pulls the user away from older content too aggressively.
- This is a core chat UX regression, not a cosmetic preference.

Direction:
- Make scroll behavior explicit: “stick to bottom” only when the user is already near bottom or after a local send.
- Trigger scroll on last-item growth, not just item-count changes.

### 4. Overlay capsule ownership is split across multiple state stores
Evidence:
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt:44-67`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/CapsuleOverlayHost.kt:73-78`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/CapsuleOverlayHost.kt:111-117`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/CapsuleOverlayHost.kt:213-220`

Problems:
- `CapsuleStateHolder` owns `mode`, `context`, `platformMode`, and other core flows.
- `CapsuleOverlayHost` also owns its own `capsuleContext`, `platformMode`, `hasIsland`, `inputFocused`, and interaction flags.
- The code comments claim “single source of truth,” but the renderer path is not actually consuming one state object.

Why this matters:
- Main-app and overlay renderers can diverge.
- The host layer is carrying both UI rendering inputs and window-management concerns.
- Future capsule changes will need synchronized edits in multiple places.

Direction:
- Keep one explicit capsule UI state owner.
- Keep overlay hosts responsible for window params, focusability, and touchability only.

### 5. Accessibility semantics are inconsistent in important controls
Evidence:
- `app/src/main/kotlin/com/moonkey/androidagent/ui/onboarding/OnboardingShell.kt:55-60`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurfaceParts.kt:256-265`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/StatusIslandCompose.kt:30-33`

Problems:
- The onboarding back affordance is an `Icon` with `clickable`, not an `IconButton`.
- Capsule nav buttons are icon-only buttons with `contentDescription = null`.
- The status island is a custom clickable surface with no explicit role/semantic naming.

Why this matters:
- Touch targets and screen-reader affordances are inconsistent.
- This is especially important in a project that already depends on Android accessibility concepts.

Direction:
- Replace ad-hoc icon clickables with proper button primitives.
- Give every icon-only action an explicit label.
- Add semantics to custom clickable containers where Material components are not used.

## Medium Severity Findings

### 6. `OnboardingSteps.kt` is too large and mixes multiple responsibilities
Evidence:
- `app/src/main/kotlin/com/moonkey/androidagent/ui/onboarding/OnboardingSteps.kt` is 735 lines.

Problems:
- One file contains permission flow, API key flow, OAuth/manual branching, demo flow, completion flow, shared buttons, shared cards, and copy.
- This violates the project’s `400` line guidance and makes the onboarding path expensive to change safely.

Direction:
- Split by step plus shared primitives.
- Keep copy, step-specific actions, and shared widgets separated.

### 7. Theme consistency is weaker than it looks
Evidence:
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/StatusIslandCompose.kt:35`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/compose/StatusIslandCompose.kt:55`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/theme/Color.kt:12-59`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/theme/Shape.kt:58-83`

Problems:
- `StatusIslandCompose` hardcodes white/dark colors instead of using theme tokens.
- `Color.kt` carries two token vocabularies; the non-`Chat*` set appears unused.
- `Shape.kt` exports several extra shapes that are not referenced.

Why this matters:
- The overlay path can visually drift from the rest of the app.
- Dead tokens increase design-system noise and make it harder to know what is canonical.

Direction:
- Remove dead tokens.
- Make overlay surfaces consume the same design tokens as the rest of the UI.

### 8. State preservation is almost absent across the module
Evidence:
- `rememberSaveable` usage in `ui/` is zero.

Problems:
- Settings page/tab/provider selection, capsule input text, password visibility, and other local user state all reset on configuration changes.

Direction:
- Define a clear rule:
  - Use hoisted state when it affects business logic.
  - Use `rememberSaveable` when it is local but user-visible and worth preserving.

### 9. There are a few avoidable lifecycle/effect mismatches
Evidence:
- `app/src/main/kotlin/com/moonkey/androidagent/ui/onboarding/OnboardingScreen.kt:46-47`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt:65-71`

Problems:
- `LaunchedEffect(Unit)` collects `effects` forever, even if the flow instance changes.
- `ChatScreen` reaches into `AgentService.instance` directly and builds fallback flows inside composition.

Direction:
- Key effects to the actual dependency.
- Pass service-backed state through an explicit boundary instead of letting the screen discover it from a singleton.

## Low Severity Findings

### 10. Strings and time formatting are still implementation-centric
Evidence:
- `app/src/main/kotlin/com/moonkey/androidagent/ui/session/TimeUtils.kt:16-17`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/components/MessageBubble.kt:188-190`
- `stringResource(...)` usage in `ui/` is zero.

Problems:
- Dates use `Locale.US` in session utilities.
- Bubble timestamps allocate `SimpleDateFormat` on demand.
- UI strings are hardcoded throughout the module.

Direction:
- Move user-facing copy into resources.
- Standardize time formatting through one utility path.

## Recommendation
`CHANGES_REQUESTED`

The module is not in bad shape overall, but the current Compose/state patterns are uneven in the most reused surfaces. Fix state ownership and composition discipline first, then accessibility/theme consistency, then file decomposition.

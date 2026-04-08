# UI/UX Code Quality Review — Final (Claude + Codex Aligned)

**Base**: Codex design with Claude additions
**Date**: 2026-04-08
**Scope**: `app/src/main/kotlin/com/moonkey/androidagent/ui/`

---

## Executive Summary

The UI module has a solid visual foundation and several strong architectural choices (CapsuleMode state machine, shared SmartCapsuleSurface, modular chat action rendering). The primary quality problem is **state ownership drift**: several important screens mix source-of-truth state, remembered local state, and UI-only side effects in ways that desync and compound.

**Verdict**: `CHANGES_REQUESTED` before further UI expansion. The codebase is fixable without a rewrite.

---

## What Is Working

- **CapsuleMode** is a clear, explicit state machine that avoids boolean soup
- **SmartCapsuleSurface** shared between app and overlay — right direction
- **Chat action rendering** is modular and readable (ChatMessage model, ActionCard)
- **Overlay window setup** is cleanly wrapped in OverlayComposeHost
- **Material 3 theme** is visually coherent at the surface level
- **Settings navigation** has clean 3-level hub with animated transitions
- **WaitingForApproval** provides 4 distinct approval scopes with correct conditional rendering
- **Stop button** disabled state ("Stopping...") prevents double-tap
- **Supplement confirmation** differentiates message based on agent turn state

---

## High Severity Findings

### H1. SmartCapsuleSurface mutates state during composition
**Files**: `capsule/surface/SmartCapsuleSurface.kt:67-74, :79-80`

`previousModeState.value = mode` is written inside `remember(...)`. `inputText = ""` is written during composition when `clearInput` is true. This breaks Compose's "render should be side-effect free" model and makes behavior depend on recomposition order. Amplified because the surface is shared by app and overlay.

**Fix**: Keep CapsuleRenderSpec.from() pure. Hoist previous-mode tracking outside composition or update via LaunchedEffect. Clear input from an effect, not during composition.

### H2. Settings page/tab/provider state drifts from real app state
**Files**: `settings/SettingsSheet.kt:68`, `settings/LlmAuthSettingsPage.kt:72-79, :226-229`

`settingsPage`, `selectedTab`, `selectedProvider` are locally remembered and seeded from external inputs once, then stop tracking. None use `rememberSaveable`. Additionally, tab switching triggers immediate `onBackendChange`/`onAuthMethodChange` — exploring tabs mutates backend/auth state.

**Fix**: Hoist settings navigation/auth selection into explicit state. Separate exploratory tab/provider selection from committed backend/auth mutations. Use `rememberSaveable` where local state should survive config changes.

### H3. Chat auto-scroll is incorrect for streaming conversations
**Files**: `chat/ChatScreen.kt:189-195`, `chat/ChatEventReducer.kt:64-72, :95-129`

Auto-scroll triggers only on `messages.size` change. Streaming text and action-card updates mutate the last message without changing list size — content grows below the fold without the viewport following. Conversely, new messages always animate to bottom even if the user is reading history.

**Fix**: Implement explicit "stick to bottom" policy. Track whether user is near bottom. Scroll on last-item growth when user is following. Show "scroll to bottom" FAB when new content arrives and user has scrolled up.

### H4. Overlay capsule ownership is split across multiple state stores
**Files**: `overlay/CapsuleStateHolder.kt:44-67`, `overlay/compose/CapsuleOverlayHost.kt:73-78, :111-117, :213-220`

CapsuleStateHolder owns `mode`, `context`, `platformMode`. CapsuleOverlayHost also owns `capsuleContext`, `platformMode`, `hasIsland`, `inputFocused`, `interactionLocked`. Comments claim "single source of truth" but the renderer path consumes split state.

**Fix**: One canonical capsule UI state contract. CapsuleStateHolder is the single owner. Hosts responsible only for window params, focusability, and touchability.

### H5. Accessibility semantics are inconsistent
**Files**: `onboarding/OnboardingShell.kt:55-60`, `capsule/surface/SmartCapsuleSurfaceParts.kt:256-265`, `overlay/compose/StatusIslandCompose.kt:30-33`

Onboarding back affordance is Icon+clickable (not IconButton — missing 48dp touch target). Capsule nav buttons have `contentDescription = null`. Status island is a custom clickable with no role/semantic naming.

**Fix**: Replace ad-hoc icon clickables with button primitives. Give every icon-only action an explicit label. Add semantics to custom clickable containers.

---

## Medium Severity Findings

### M6. OnboardingSteps.kt exceeds size guideline
**File**: `onboarding/OnboardingSteps.kt` — 735 lines. Contains permission flow, API key flow, OAuth/manual branching, demo flow, completion flow, shared buttons, shared cards, and copy. Violates the project's 400-line guidance.

### M7. Theme consistency is weaker than it looks
**Files**: `overlay/compose/StatusIslandCompose.kt:35, :55`, `theme/Color.kt:12-59`, `theme/Shape.kt:58-83`

StatusIslandCompose hardcodes white/dark colors instead of theme tokens. Color.kt carries two parallel token vocabularies — the non-`Chat*` set appears unused. Shape.kt exports unreferenced shapes.

### M8. State preservation is absent across the module
Zero `rememberSaveable` usage in `ui/`. Settings page/tab/provider, capsule input, password visibility all reset on configuration changes.

### M9. Lifecycle/effect mismatches
**Files**: `onboarding/OnboardingScreen.kt:46-47`, `chat/ChatScreen.kt:65-71`

`LaunchedEffect(Unit)` collects effects forever even if the flow changes. ChatScreen reaches into `AgentService.instance` directly.

### M10. SettingsSheet has 38 parameters
**File**: `settings/SettingsSheet.kt:30-67`. Maintainability ceiling — any new setting requires editing this signature plus every call site.

### M11. CapsuleOverlayHost has 12 nullable callback properties
**File**: `overlay/compose/CapsuleOverlayHost.kt:52-62`. Missing assignment is a silent no-op.

### M12. Session delete has no confirmation
**File**: `navigation/NavigationDrawer.kt:274-284`. Delete button directly calls `onDelete` — accidental deletion is irreversible. Small (32dp) touch target increases risk.

### M13. MessageBubble allocates SimpleDateFormat per recomposition
**File**: `chat/components/MessageBubble.kt:188`. `SimpleDateFormat` is not thread-safe and creates GC pressure during streaming.

### M14. ActionStatusIcon has redundant double rotation
**File**: `chat/components/ActionCard.kt:170-187`. Custom `infiniteTransition` rotation wraps an already-animating `CircularProgressIndicator`.

---

## Low Severity Findings

### L15. Strings and time formatting are implementation-centric
`Locale.US` in session utilities. Zero `stringResource()` usage. User-facing strings hardcoded.

### L16. PerceptionMode uses raw strings
**File**: `settings/SettingsWidgets.kt:327-331`. Raw string matching in multiple places risks typos.

### L17. Version string shown twice
**Files**: `settings/SettingsHomePage.kt:64-70`, `settings/PermissionsAdvancedSettingsPage.kt:108-114`.

---

## Gaps in Both Reviews

1. No performance profiling (no layout inspector or recomposition count data)
2. No dark mode visual testing
3. No tablet/foldable consideration

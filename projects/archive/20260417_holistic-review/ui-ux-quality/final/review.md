# UI/UX Code Quality Review — v2

**Base**: Codex design with Claude additions
**Original**: 2026-04-08 | **Re-reviewed**: 2026-04-15 (Claude + Codex independent re-review)
**Scope**: `app/src/main/kotlin/com/moonkey/androidagent/ui/`

---

## Executive Summary

The UI module has a solid visual foundation and several strong architectural choices (CapsuleMode state machine, shared SmartCapsuleSurface, modular chat action rendering). The primary quality problem is **state ownership drift**: several important screens mix source-of-truth state, remembered local state, and UI-only side effects in ways that desync and compound.

Only 3 commits (7 lines) touched ui/ since the original review. All core findings still hold.

**Verdict**: `CHANGES_REQUESTED` before further UI expansion. Fixable without a rewrite.

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
- **ChatHeader, NavigationDrawer** already use proper IconButtons with descriptions

---

## High Severity Findings

### H1. SmartCapsuleSurface mutates state during composition
**Files**: `capsule/surface/SmartCapsuleSurface.kt:65-81`
**Status**: STILL_REAL — highest-ROI fix in the plan

`previousModeState.value = mode` is written inside `remember(...)`. `inputText = ""` is written during composition when `clearInput` is true. This breaks Compose's "render should be side-effect free" model and makes behavior depend on recomposition order. Amplified because the surface is shared by app and overlay.

Additionally, `SmartCapsuleCompose.kt` (in-app path) doesn't pass `previousMode` at all, relying entirely on the internal fallback that depends on the composition-time write. Both callers must be fixed together.

**Fix**: Remove `previousModeState` from SmartCapsuleSurface entirely. Make callers provide previous mode. Move input clearing into a keyed effect on mode transition.

### H2. Settings page/tab/provider state drifts from real app state
**Files**: `settings/SettingsSheet.kt:70`, `settings/LlmAuthSettingsPage.kt:72-79, :88-112, :225-250`
**Status**: STILL_REAL

`settingsPage`, `selectedTab`, `selectedProvider` are locally remembered and seeded from external inputs once, then stop tracking. None use `rememberSaveable`. Tab switching triggers immediate `onBackendChange`/`onAuthMethodChange` — exploring tabs mutates backend/auth state. Same coupling exists for API-key provider selection.

**Fix**: Hoist settings navigation/auth selection into explicit state. Separate exploratory tab/provider selection from committed backend/auth mutations. Use `rememberSaveable` where local state should survive config changes.

### H3. Chat auto-scroll is incorrect for streaming conversations
**Files**: `chat/ChatScreen.kt:191-196`, `chat/ChatEventReducer.kt:68-71, :95-98`
**Status**: STILL_REAL

Auto-scroll triggers only on `messages.size` change. Streaming text and action-card updates mutate the last message in place without changing list size — content grows below the fold without the viewport following. Conversely, new messages always animate to bottom even if the user is reading history.

**Fix**: Implement explicit "stick to bottom" policy. Track whether user is near bottom. Scroll on last-item growth when user is following. Show "scroll to bottom" FAB when new content arrives and user has scrolled up.

---

## Medium Severity Findings

### M4. Destructive actions lack confirmation
**Files**: `navigation/NavigationDrawer.kt:274-283`, `settings/PermissionsAdvancedSettingsPage.kt:208-227`
**Status**: STILL_REAL — tiny fix, high ROI

Session delete button calls `onDelete` directly with no confirmation dialog (32dp touch target). Settings has two more destructive actions — "Clear Traces" and "Clear Session History" — that also execute immediately with no confirmation and no failure feedback.

### M5. Overlay capsule state ownership is partially split
**Files**: `overlay/compose/CapsuleOverlayHost.kt:73-76, :213-221`, `app/ServiceOverlayController.kt:376-383`
**Status**: PARTIALLY_DONE — groundwork exists, narrow remaining gap

CapsuleStateHolder correctly centralizes mode, context, platformMode, turnPhase. IslandOverlayHost already reads from stateHolder. But CapsuleOverlayHost still keeps its own `capsuleContext`, `platformMode`, `hasIsland` flows and exposes `updateNavContext()` for local mutation. ServiceOverlayController writes same inputs to both stateHolder and capsuleManager.

Host-only concerns (focusability, touch passthrough, interaction locks) are correctly host-owned and should stay.

### M6. Accessibility gaps in 3 specific controls
**Files**: `onboarding/OnboardingShell.kt:54-61`, `overlay/compose/StatusIslandCompose.kt:30-43`, `capsule/surface/SmartCapsuleSurfaceParts.kt:255-266`
**Status**: PARTIALLY_DONE — chat/drawer already fixed, 3 controls remain

- Onboarding back: Icon+clickable at 24dp, no IconButton (missing 48dp touch target)
- Status island: Clickable Surface with no role, no label, hardcoded colors
- Capsule nav: NavIconButton with `contentDescription = null`

### M7. MessageBubble allocates SimpleDateFormat per recomposition
**File**: `chat/components/MessageBubble.kt:188-190`
**Status**: STILL_REAL

`SimpleDateFormat` is not thread-safe and creates GC pressure during streaming. `TimeUtils.kt` already has a thread-safe `DateTimeFormatter` pattern.

### M8. ActionStatusIcon has redundant double rotation
**File**: `chat/components/ActionCard.kt:171-184`
**Status**: STILL_REAL

Custom `infiniteTransition` rotation wraps an already-animating `CircularProgressIndicator`.

---

## Low Severity / Opportunistic

### L9. Onboarding effect keying
**File**: `onboarding/OnboardingScreen.kt:46`

`LaunchedEffect(Unit)` collects effects forever. However, `effects` is a stable `SharedFlow` property on the ViewModel, so this is lower-risk than originally assessed. Fix when touching onboarding.

### L10. Onboarding scroll/IME handling *(new)*
**Files**: `onboarding/OnboardingShell.kt:44-49`, `onboarding/OnboardingSteps.kt:172-231, :362-445`

API-key step is not scrollable. On smaller devices or with keyboard open, the bottom CTA can be pushed off-screen. More important than the file decomposition issue (735 lines).

### L11. ActionState.Executing dead branch *(new)*
**File**: `chat/ChatEventReducer.kt:84-98, :101-129`

`ActionState.Executing` has UI rendering but no live producer in the reducer path — only `Proposed`, `Success`, and `Failed` are emitted. Either start emitting `Executing` or delete the dead branch.

### L12. Color.kt dual token sets
**File**: `theme/Color.kt:12-59`

Old "general" token set (Background, Surface, Primary, etc.) still exists alongside canonical `Chat*` set. Not referenced by theme or composables. Delete opportunistically when touching theme files.

### L13. PerceptionMode raw strings
**Files**: `settings/SettingsWidgets.kt:327-330`, `app/AppSettingsStore.kt:64`, `app/AppSettingsState.kt:33`, `app/MainActivity.kt:620-626`, +3 more

Spans ~6 files across app/activity/settings layers. Cross-cutting type-safety cleanup, not a UI-specific fix.

### L14. Duplicate version display
**Files**: `settings/SettingsHomePage.kt:64-66`, `settings/PermissionsAdvancedSettingsPage.kt:128-129`

### L15. OnboardingSteps.kt size
**File**: `onboarding/OnboardingSteps.kt` — 735 lines

Already internally segmented into step-specific composables. Split opportunistically when onboarding is actively modified.

---

## Dropped from Original Review

| Original Finding | Reason Dropped |
|-----------------|----------------|
| M8 (State preservation absent) | Real gap is in settings (absorbed into H2). Most other `remember` is intentionally ephemeral. |
| M10 (SettingsSheet 38 params) | Maintainability concern, not a UX bug. Address during settings restructure if needed. |
| M11 (CapsuleOverlayHost 12 callbacks) | Absorbed into M5 overlay state narrowing. |
| L15 (Strings/time formatting) | SimpleDateFormat absorbed into M7. String resources not worth doing without localization goal. |
| Phase 6 (Token cleanup as standalone) | Dead tokens aren't hurting users. StatusIsland theming absorbed into M6 a11y fix. |
| Phase 7 (File decomposition as standalone) | 735 lines but well-segmented. Mechanical cleanup with no user-facing value. |
| Phase 8 (State preservation as standalone) | Broken apart — rememberSaveable into H2, SimpleDateFormat into M7, rest is optional. |

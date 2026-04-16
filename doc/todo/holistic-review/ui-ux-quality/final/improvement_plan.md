# UI/UX Quality Improvement Plan — v2

**Original**: 2026-04-08 | **Updated**: 2026-04-16 (post Claude + Codex re-review)
**Principle**: Fix state ownership and composition correctness before polish. Reduction over addition.

---

## Phase 1: Compose Correctness in Shared Surfaces
**Priority**: P0

**Targets**:
- `capsule/surface/SmartCapsuleSurface.kt`
- `capsule/SmartCapsuleCompose.kt`

**Tasks**:
- Remove `previousModeState` from SmartCapsuleSurface entirely — callers must provide previous mode
- Move input clearing from composition-time write into a keyed effect on mode transition
- Plumb `previousMode` through `SmartCapsuleCompose.kt` (in-app path currently missing it)

**Verification**:
- Compose tests for capsule transitions: Hidden→WaitingForInput clears input exactly once; WaitingForInput→Running does not re-clear unrelated draft text
- Manual verify: no repeated focus/input glitches in overlay input mode
- Verify both in-app and overlay paths behave identically

---

## Phase 2: Hoist Settings Navigation and Auth State
**Priority**: P0

**Targets**:
- `settings/SettingsSheet.kt`
- `settings/LlmAuthSettingsPage.kt`

**Tasks**:
- Hoist or correctly key settings navigation state with `rememberSaveable` (page, tab, provider)
- Remove "initialize once from external state" patterns for `selectedTab` and `selectedProvider`
- Separate exploratory tab/provider selection from committed backend/auth mutations — tab taps should not immediately fire `onBackendChange`/`onAuthMethodChange`
- Same for API-key provider selection (lines 225-250)

**Verification**:
- Switch between OAuth/API key/local
- Change selected model externally, reopen settings — shown tab/provider is correct
- Rotate while on a nested settings page — state preserved
- Return from OAuth — correct tab shown

---

## Phase 3: Fix Chat Scroll and Chat Cleanup
**Priority**: P1

**Targets**:
- `chat/ChatScreen.kt`
- `chat/components/MessageBubble.kt`
- `chat/components/ActionCard.kt`

**Tasks**:
- Replace "scroll on messages.size change" with explicit bottom-stickiness policy
- Track whether user is near bottom before auto-scrolling
- Scroll on last-message growth when user is following the conversation
- Show "scroll to bottom" FAB when user has scrolled up and new content arrives
- Replace `SimpleDateFormat` in MessageBubble with top-level `DateTimeFormatter` (thread-safe, matches TimeUtils.kt pattern)
- Remove redundant `infiniteTransition` rotation around `CircularProgressIndicator` in ActionCard

**Verification**:
- Long streaming response exceeding one screen — viewport follows
- Action cards added mid-stream — viewport follows
- User scrolls up while agent continues — not yanked back, FAB appears

---

## Phase 4: Destructive Action Confirmation
**Priority**: P1

**Targets**:
- `navigation/NavigationDrawer.kt`
- `settings/PermissionsAdvancedSettingsPage.kt`

**Tasks**:
- Add confirmation dialog before session deletion in NavigationDrawer
- Add confirmation dialog before "Clear Traces" and "Clear Session History" in settings

**Verification**:
- Tap delete → dialog → Cancel → no change
- Tap delete → dialog → Confirm → action executes
- Same for Clear Traces and Clear Session History

**Estimated effort**: ~15 lines per dialog.

---

## Phase 5: Accessibility — 3 Targeted Fixes
**Priority**: P2

**Targets**:
- `onboarding/OnboardingShell.kt`
- `overlay/compose/StatusIslandCompose.kt`
- `capsule/surface/SmartCapsuleSurfaceParts.kt`

**Tasks**:
- Onboarding back: Replace Icon+clickable with `IconButton` (48dp touch target)
- Status island: Add `role = Role.Button`, add content label, replace hardcoded colors with theme tokens
- Capsule nav: Add `contentDescription` to NavIconButton

**Verification**:
- TalkBack pass on onboarding, capsule overlay, and island

---

## Phase 6: Narrow Overlay State Unification
**Priority**: P2

**Targets**:
- `overlay/compose/CapsuleOverlayHost.kt`
- `app/ServiceOverlayController.kt`

**Tasks**:
- Remove duplicate `capsuleContext`, `platformMode`, `hasIsland` flows from CapsuleOverlayHost — read from CapsuleStateHolder instead
- Remove `updateNavContext()` — mutations go through CapsuleStateHolder
- Update ServiceOverlayController to stop writing same inputs to both stateHolder and capsuleManager
- Leave host-only concerns (focusability, touchability, interaction locks) in the host

**Verification**:
- Manual matrix: main app, accessibility overlay, viewer open/close, background/island transitions, all capsule modes

---

## Opportunistic (do when touching these files)

| Item | Trigger | Effort |
|------|---------|--------|
| Onboarding `LaunchedEffect(Unit)` → `LaunchedEffect(effects)` | Touching onboarding | Trivial |
| Onboarding scroll/IME — API-key step not scrollable on small screens | Touching onboarding | Small |
| `ActionState.Executing` dead branch — no producer emits it | Touching chat reducer | Trivial |
| Delete unused "general" color tokens in Color.kt | Touching theme | Trivial |
| Delete unreferenced shapes in Shape.kt | Touching theme | Trivial |
| Remove duplicate version display in settings | Touching settings | Trivial |
| Split OnboardingSteps.kt (735 lines) | Major onboarding changes | Medium |
| PerceptionMode raw strings → typed enum (~6 files cross-layer) | Type-safety initiative | Medium |

---

## Non-Recommendations

| Item | Reason |
|------|--------|
| Don't introduce overlay-specific dark-mode work | Keep overlays contrast-first; theming addressed in Phase 5 StatusIsland fix |
| Don't remove AppWindowInsets wrapper | Documentation value at zero runtime cost |
| Don't hoist ActionCard expand state to ViewModel | Ephemeral UI state correctly scoped to composition |
| Don't decompose ChatViewModel before simplifying chat state | Class boundaries are fine; problem is split state representation, scoped in Phase 3 |
| Don't add more settings animations | AnimatedContent slide transitions already adequate |
| Don't extract strings to resources | Not worth doing without active localization goal |

---

## Execution Summary

```
Phase 1 (P0) ─── Capsule composition correctness + in-app caller
Phase 2 (P0) ─── Settings state hoisting + rememberSaveable
                    ↓
Phase 3 (P1) ─── Chat scroll/follow + SimpleDateFormat + double rotation
Phase 4 (P1) ─── Destructive action confirmation (session delete + settings clear)
                    ↓
Phase 5 (P2) ─── 3 targeted a11y fixes
Phase 6 (P2) ─── Narrow overlay state unification
```

**Critical path**: Phases 1 and 2 before any new capsule or settings feature work.

**Minimum viable improvement**: Phases 1 + 2 for highest immediate quality gain.

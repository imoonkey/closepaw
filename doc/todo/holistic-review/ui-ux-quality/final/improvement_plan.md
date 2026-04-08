# UI/UX Quality Improvement Plan — Final (Claude + Codex Aligned)

**Base**: Codex phasing with Claude tactical additions
**Principle**: Fix state ownership and composition correctness before polish. Reduction over addition.

---

## Phase 1: Compose Correctness in Shared Surfaces
**Priority**: P0

**Targets**:
- `capsule/surface/SmartCapsuleSurface.kt`
- `onboarding/OnboardingScreen.kt`

**Tasks**:
- Remove all state writes from SmartCapsuleSurface composition
- Stop updating `previousModeState` from inside `remember`
- Replace composition-time input clearing with an effect or caller-owned state transition
- Key the onboarding effect collector to `effects`, not `Unit`

**Verification**:
- Compose tests for capsule transitions: Hidden→WaitingForInput clears input exactly once; WaitingForInput→Running does not re-clear unrelated draft text
- Manual verify: no repeated focus/input glitches in overlay input mode

---

## Phase 2: Hoist Settings Navigation and Auth State
**Priority**: P0

**Targets**:
- `settings/SettingsSheet.kt`
- `settings/LlmAuthSettingsPage.kt`

**Tasks**:
- Introduce explicit settings navigation/auth state (hoist or correctly key with `rememberSaveable`)
- Remove "initialize once from external state" patterns for `selectedTab` and `selectedProvider`
- Separate exploratory tab/provider selection from committed backend/auth mutations (tab taps should not immediately trigger `onBackendChange`/`onAuthMethodChange`)

**Verification**:
- Switch between OAuth/API key/local
- Change selected model externally, reopen settings — shown tab/provider is correct
- Rotate while on a nested settings page — state preserved
- Return from OAuth — correct tab shown

---

## Phase 3: Fix Chat Scroll Behavior and Simplify Chat State
**Priority**: P1

**Targets**:
- `chat/ChatScreen.kt`
- `chat/ChatViewModel.kt`
- `chat/ChatEventReducer.kt`
- `chat/components/MessageBubble.kt`
- `chat/components/ActionCard.kt`

**Tasks**:
- Replace "scroll on messages.size change" with explicit bottom-stickiness policy
- Track whether user is near bottom before auto-scrolling
- Scroll on last-message growth when user is following the conversation
- Show "scroll to bottom" FAB when user has scrolled up and new content arrives
- Replace `SimpleDateFormat` in MessageBubble with top-level `DateTimeFormatter` (thread-safe)
- Remove redundant `infiniteTransition` rotation around `CircularProgressIndicator` in ActionCard

**Verification**:
- Long streaming response exceeding one screen — viewport follows
- Action cards added mid-stream — viewport follows
- User scrolls up while agent continues — not yanked back, FAB appears
- Reducer/screen tests around scroll-trigger conditions

---

## Phase 3.5: Session Delete Confirmation
**Priority**: P1

**Target**: `navigation/NavigationDrawer.kt`

**Tasks**:
- Add confirmation dialog before session deletion
- AlertDialog: "Delete this session? This cannot be undone." with [Cancel] [Delete]

**Verification**:
- Tap delete → dialog appears → Cancel returns to list
- Tap delete → dialog appears → Delete removes session
- No accidental deletions from mis-taps

**Estimated effort**: ~15 lines added.

---

## Phase 4: Unify Capsule/Overlay State Ownership
**Priority**: P1

**Targets**:
- `overlay/CapsuleStateHolder.kt`
- `overlay/compose/CapsuleOverlayHost.kt`
- `overlay/compose/IslandOverlayHost.kt`

**Tasks**:
- Define one canonical capsule UI state contract
- Keep CapsuleStateHolder as the single owner for mode/context/platform/island state
- Remove duplicate render-input flows from CapsuleOverlayHost
- Hosts responsible only for: showing/hiding windows, focusability, touchability, pass-through

**Verification**:
- Manual matrix: main app, accessibility overlay, viewer open/close, background/island transitions, all capsule modes (waiting-for-input, waiting-for-approval, running, done, error)

---

## Phase 5: Accessibility Hardening
**Priority**: P1

**Targets**:
- `onboarding/OnboardingShell.kt`
- `capsule/surface/SmartCapsuleSurfaceParts.kt`
- `overlay/compose/StatusIslandCompose.kt`
- Settings/navigation/chat clickable rows

**Tasks**:
- Replace icon-with-`clickable` patterns with proper button primitives (e.g., `IconButton`)
- Add `contentDescription` to every icon-only action
- Add semantics/roles to custom clickable surfaces
- Audit touch targets for back, nav, delete, and overlay controls

**Verification**:
- TalkBack pass on: onboarding, settings, chat header/drawer, capsule overlay, and island

---

## Phase 6: Theme and Token Cleanup
**Priority**: P2

**Targets**:
- `theme/Color.kt`
- `theme/Shape.kt`
- `overlay/compose/StatusIslandCompose.kt`

**Tasks**:
- Remove the unused "general" color token set (lines ~12-59 in Color.kt). The `Chat*` variants are canonical.
- Delete unreferenced shape tokens
- Make island/overlay surfaces consume canonical theme tokens instead of hardcoded colors
- Standardize which tokens are public vs implementation detail

**Verification**:
- Screenshot compare for chat, settings, onboarding, and overlay in light and dark themes

---

## Phase 7: Decompose Large UI Files
**Priority**: P2

**Target**: `onboarding/OnboardingSteps.kt` (735 lines)

**Tasks**:
- Split into: PermissionStepContent, ApiKeyStepContent, DemoStepContent, CompleteStepContent, shared onboarding primitives/copy
- Keep each file under 400 lines

**Verification**:
- No functional change in onboarding flow
- Simpler previews/tests per step

---

## Phase 8: State Preservation and Resource Hygiene
**Priority**: P3

**Targets**: Settings, onboarding, capsule input, chat leaf state

**Tasks**:
- Add `rememberSaveable` where local state should survive config changes
- Convert `PerceptionMode` raw strings to a typed enum
- Remove duplicate version display (keep only on SettingsHomePage)
- Start extracting user-facing strings into resources
- Standardize time formatting utilities and remove ad-hoc `SimpleDateFormat` use

**Verification**:
- Rotation/recreation checks on settings, onboarding, and chat

---

## Non-Recommendations

These are explicitly **not** part of this improvement plan:

| Item | Reason |
|------|--------|
| Don't introduce separate overlay-specific dark-mode work as a priority | Keep overlays contrast-first; move them toward canonical tokens via Phase 6 instead |
| Don't remove AppWindowInsets wrapper | Adds documentation value at zero runtime cost |
| Don't hoist ActionCard expand state to ViewModel | Ephemeral UI state correctly scoped to composition |
| Don't do further ChatViewModel decomposition-for-its-own-sake before simplifying chat state ownership | Class boundaries are fine; the problem is the split state representation (StateFlow + SnapshotStateList + StringBuilder + lock), addressed in Phase 3 |
| Don't add more settings animations | AnimatedContent slide transitions already present and adequate |

---

## Execution Summary

```
Phase 1 (P0) ─── Compose correctness in shared surfaces
Phase 2 (P0) ─── Settings state ownership
                    ↓
Phase 3 (P1) ─── Chat scroll + chat cleanup
Phase 3.5 (P1) ── Session delete confirmation
Phase 4 (P1) ─── Overlay state unification
Phase 5 (P1) ─── Accessibility hardening
                    ↓
Phase 6 (P2) ─── Theme token cleanup
Phase 7 (P2) ─── File decomposition
                    ↓
Phase 8 (P3) ─── State preservation + resource hygiene
```

**Critical path**: Phases 1 and 2 should complete before any new capsule or settings feature work.

**Minimum viable improvement**: Phases 1 + 2 deliver the highest immediate quality gain if implementation effort must be minimized.

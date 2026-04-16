# UI/UX Plan Re-review Against Current Code (Claude)

Reviewed current source against `doc/todo/holistic-review/ui-ux-quality/final/improvement_plan.md` (written 2026-04-08). Only 3 commits touched ui/ since then (7 lines total). Nearly all findings still hold.

## Phase 1: Compose Correctness in Shared Surfaces
**Verdict**: STILL_REAL

- `SmartCapsuleSurface.kt:65-81`: `previousModeState.value = mode` inside `remember` and `inputText = ""` during composition are still present. This is the most important fix.
- `OnboardingScreen.kt:46`: `LaunchedEffect(Unit)` still used. However, `effects` is a stable `SharedFlow` property on the ViewModel, so this is lower-risk than it appears. Downgrade to P1.
- **New**: `SmartCapsuleCompose.kt` (in-app path) doesn't pass `previousMode` at all, relying on the internal fallback that depends on the composition-time write. Fix must include this caller.

**Plan change**: Keep P0 for capsule tasks. Downgrade onboarding effect keying to P1 (nice-to-have).

## Phase 2: Hoist Settings Navigation and Auth State
**Verdict**: STILL_REAL

- `SettingsSheet.kt:70`: `remember { mutableStateOf(SettingsPage.HOME) }` — not saveable.
- `LlmAuthSettingsPage.kt:72-79`: `selectedTab` initialized once, drifts from external state.
- `LlmAuthSettingsPage.kt:88-112`: Tab taps immediately fire `onBackendChange`/`onAuthMethodChange`.
- `LlmAuthSettingsPage.kt:225-250`: Same coupling for API-key provider selection.

**Plan change**: Keep as-is. Still P0. Fold rememberSaveable from Phase 8 into this phase for settings state.

## Phase 3: Fix Chat Scroll and Chat Cleanup
**Verdict**: STILL_REAL

- `ChatScreen.kt:191-196`: Auto-scroll keyed only to `messages.size`. Streaming growth in last message doesn't trigger scroll.
- `ChatEventReducer.kt:68-71, 95-98`: Confirms in-place mutation without size change.
- `MessageBubble.kt:188-190`: `SimpleDateFormat` per call, still present.
- `ActionCard.kt:171-184`: Double rotation still present.
- `ChatViewModel.kt:108-132`: State still split across StateFlow + SnapshotStateList + StringBuilder + lock.

**Plan change**: Keep as-is. The scroll fix + SimpleDateFormat + double rotation are all worth doing. The chat-state simplification should be scoped to scroll behavior, not a full rewrite.

## Phase 3.5: Session Delete Confirmation
**Verdict**: STILL_REAL

- `NavigationDrawer.kt:274-283`: Delete button still calls `onDelete` directly with no confirmation.

**Plan change**: Keep, but expand scope: `PermissionsAdvancedSettingsPage.kt` has "Clear Traces" and "Clear Session History" buttons that also execute destructively with no confirmation. Same pattern, same fix.

## Phase 4: Unify Capsule/Overlay State Ownership
**Verdict**: PARTIALLY_DONE — keep but narrow

- `CapsuleStateHolder.kt`: Already centralizes mode, context, platformMode, turnPhase. Good.
- `IslandOverlayHost.kt`: Already reads from stateHolder. Good.
- `CapsuleOverlayHost.kt:73-76`: Still keeps own `capsuleContext`, `platformMode`, `hasIsland` flows.
- `CapsuleOverlayHost.kt:213-221`: Still exposes `updateNavContext()` for local mutation.

But host-only concerns (focusability, touch passthrough, interaction locks) are correctly host-owned and should stay.

**Plan change**: Narrow to: unify `context`/`platformMode`/`hasIsland` into CapsuleStateHolder. Don't move host-only concerns. Lower to P2 since groundwork exists.

## Phase 5: Accessibility Hardening
**Verdict**: PARTIALLY_DONE — keep but narrow

Still broken:
- `OnboardingShell.kt:54-61`: Icon+clickable, no IconButton (24dp, no 48dp touch target)
- `StatusIslandCompose.kt:30-43`: Clickable Surface with no role or label
- `SmartCapsuleSurfaceParts.kt:255-266`: NavIconButton with `contentDescription = null`

Already fixed:
- `ChatHeader.kt:57-90`: Uses proper IconButtons with descriptions
- `NavigationDrawer.kt:171-176, 274-283`: Uses IconButtons with descriptions

**Plan change**: Focus on the 3 real issues. Don't audit every settings row — adding `role = Role.Button` to custom clickable surfaces is higher ROI than replacing them with button primitives.

## Phase 6: Theme and Token Cleanup
**Verdict**: LOW_ROI as standalone phase

- `Color.kt:12-59`: Old general tokens still exist but aren't referenced by theme or composables.
- `Shape.kt:58-83`: CapsuleShape, PillShape, CardShape, InputShape, SheetShape — need to verify actual usage.
- `StatusIslandCompose.kt:35, 55`: Hardcoded colors, but this is better addressed when fixing its a11y (Phase 5).

**Plan change**: Drop as standalone phase. Delete dead tokens opportunistically when touching these files. Fix StatusIslandCompose theming as part of Phase 5.

## Phase 7: Decompose Large UI Files
**Verdict**: LOW_ROI as standalone work

- `OnboardingSteps.kt`: Still 735 lines but internally well-segmented by step.
- No functional benefit to splitting unless onboarding is actively being modified.

**New issue (more important)**: Onboarding steps with text fields (API key) aren't scrollable. Keyboard can push CTAs off-screen on smaller devices.

**Plan change**: Drop standalone decomposition. Add onboarding scroll/IME handling as a targeted fix if onboarding is touched.

## Phase 8: State Preservation and Resource Hygiene
**Verdict**: LOW_ROI as standalone phase — break apart

- `rememberSaveable`: Zero usage across ui/. But the real gaps are settings state (Phase 2). Most other `remember` is intentionally ephemeral.
- `PerceptionMode` raw strings: Real but spans app/activity/settings/session layers — not just a UI fix. ~6 files, cross-cutting.
- Duplicate version: Trivial to fix, low priority.
- String resources: Not worth doing unless localization is a goal.
- `SimpleDateFormat`: Already folded into Phase 3.

**Plan change**: Fold rememberSaveable into Phase 2. Fold SimpleDateFormat into Phase 3. Drop string extraction. PerceptionMode enum and version dedup become optional cleanup.

---

## Revised Priority Stack

| Priority | Phase | Description | Status |
|----------|-------|-------------|--------|
| **P0** | 1 (narrowed) | SmartCapsuleSurface composition mutations + in-app caller fix | STILL_REAL |
| **P0** | 2 (expanded) | Settings state hoisting + rememberSaveable | STILL_REAL |
| **P1** | 3 | Chat scroll/follow + SimpleDateFormat + double rotation | STILL_REAL |
| **P1** | 3.5 (expanded) | Delete confirmation for session + storage-clear actions | STILL_REAL |
| **P2** | 5 (narrowed) | 3 specific a11y fixes: onboarding back, island, capsule nav | PARTIALLY_DONE |
| **P2** | 4 (narrowed) | Unify context/platformMode/hasIsland into CapsuleStateHolder | PARTIALLY_DONE |
| **Drop** | 6 | Token cleanup → opportunistic | LOW_ROI |
| **Drop** | 7 | File decomposition → opportunistic | LOW_ROI |
| **Drop** | 8 | Absorbed into Phases 2 and 3 | LOW_ROI |

## New Issues Not In Original Plan
1. **Destructive settings actions lack confirmation**: "Clear Traces" and "Clear Session History" in PermissionsAdvancedSettingsPage execute immediately
2. **Onboarding scroll/IME**: API-key step not scrollable, CTA pushed off-screen by keyboard
3. **In-app capsule missing previousMode**: SmartCapsuleCompose.kt doesn't pass previousMode

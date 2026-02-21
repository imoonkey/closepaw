# UI Suggestions — Aligned First-Principles Draft (v3)

Date: 2026-02-20
Status: Updated with user decisions

## 0. Decision Standard

We prioritize:
1. User control and recoverability (can always stop/resume/respond)
2. Predictable behavior across entry surfaces (main app vs overlay)
3. Minimal hidden coupling between rendering flags and state logic
4. Consistency with design docs only when it still serves 1-3

## 1. Implementation vs Design Mismatches (Must Discuss)

### 1.1 Overlay touchability (`FLAG_NOT_TOUCHABLE`) conflicts with interactive capsule model (P0) — DONE

**Implemented**: commit `25e8587`, verified in `run_20260221_001359` (5-turn success).

Solution — two-layer touchability system:
1. **Mode-driven baseline** via `shouldCapsuleOverlayBeTouchable(mode)` — only `Hidden` passes touches through.
2. **Gesture pass-through gate** (`OverlayTouchGate`) — temporarily sets `FLAG_NOT_TOUCHABLE` during `dispatchGesture()` with 50ms IPC settle delay.

Also switched `ActionPriorityOrder` to gesture-first and added self-takeover prevention prompts.

Full details: `doc/todo/ui_sota/overlay_touch/impl_summary_claude.md`

Note: `Running` must be touchable to enable the interaction lock shield (§1.3). The capsule UI elements themselves remain non-interactive during Running — only the full-screen touch-eating shield receives touches.

### 1.2 A11y island/minimize policy diverges from round6 baseline (P1) — RESOLVED

**User decision**: Option B — keep island + ⊖ for A11y.

Rationale: Users can minimize capsule for more screen space. Does not affect A11y normal operation.

Action:
- Keep current code (A11y island path enabled).
- Update round6 design doc / NavSpec doc to reflect this decision.
- NavSpecTest assertions for A11y ⊖ are now correct behavior.

### 1.3 Interaction lock semantics currently coupled to touchability flag (P1) — DONE

**Resolved** by §1.1 overlay touchability fix. The decoupling works across all three scenarios:

1. **Running + no gesture**: `shouldCapsuleOverlayBeTouchable(Running)` → `true`, overlay touchable. `shouldLockUserInteraction()` → `true` (at OTHER_APP), `setInteractionLocked(true)` expands overlay to `MATCH_PARENT` with full-screen `View` eating all user touches via `setOnTouchListener { _, _ -> true }`.
2. **Running + gesture dispatch**: `OverlayTouchGate` temporarily sets `FLAG_NOT_TOUCHABLE` → gesture passes through → token close restores → shield resumes.
3. **Takeover**: `shouldLockUserInteraction()` returns `false` (`userOwnsControl = mode is Takeover`) → shield off, overlay shrinks to `WRAP_CONTENT`, user can operate underlying app while capsule buttons (Resume/Stop) remain touchable.

### 1.4 VD main app viewer icon in Row3 during Idle/Done is unnecessary (P1) — DONE

**Fixed**: set `showOpenViewer = false` in `SmartCapsuleSurface.kt:140`. VD viewer remains reachable via Row1 nav button (👁) or island tap during active task states.

## 2. Implementation/Design Already Aligned but Worth Revalidating

### 2.1 Force-CAPSULE for `WaitingForInput|WaitingForAction|Error`

Current alignment:
- Code and design both force actionable states to capsule.

First-principles check:
- This is correct. Action-required states should never hide behind island.

### 2.2 `Done` auto-hide (3s)

Current alignment:
- Code and prior design align.

First-principles check:
- Good default for low-friction flow.
- But if user frequently misses completion feedback, consider making duration configurable.

### 2.3 MAIN_APP hides all system overlays

Current alignment:
- Code aligns and has lifecycle fallback (`onMainAppVisible`) to enforce it.

First-principles check:
- Correct. Avoid duplicate UI and focus confusion.

## 3. Non-Design Gaps from Current Implementation

### 3.1 UserResponse feedback asymmetry between overlay and main app (P1)

Problem:
- Overlay path updates mode immediately via `onUserResponseSent(callId)`.
- Main app path directly submits op, no immediate `Processing response...` state.

Recommendation:
- Reuse same guarded transition in main-app path before submitting op.

### 3.2 `resolveUserLocation` heuristic robustness (P2)

Problem:
- Activity-class-name heuristic may be brittle on OEM variants.

Recommendation:
- Add ignored-event telemetry and fallback strategy tuning (without broad refactor).

### 3.3 `dismissError` routing bypasses controller (P2)

Problem:
- Overlay path: `CapsuleOverlayHost.onDismissError` → `ServiceOverlayController` callback (line 86) → `stateHolder.onDismissError()`
- Main app path: `ChatViewModel.dismissError()` (line 232) → `AgentService.instance?.capsuleStateHolder?.onDismissError()` directly

This bypasses `ServiceOverlayController`, so any future side-effects in the controller (like explicit `applyVisibility()`) would be missed.

Recommendation:
- Route main app dismiss through `ServiceOverlayController.onDismissError()` for consistency.
- Currently works because mode observer catches Hidden transitions, but fragile.

## 4. Proposed Execution Order

1. ~~P0: finalize touchability policy — mode-driven toggle with Running touchable for shield~~ ✅ DONE (`25e8587`)
2. ~~P1: remove Row3 viewer icon from VD main app idle~~ ✅ DONE
3. P1: A11y island policy already resolved (keep ⊖) — update design docs + tests
4. P1: unify UserResponse immediate feedback path
5. P2: harden location-detection diagnostics
6. P2: route `dismissError` through controller

## 5. User Decisions (Resolved)

1. **A11y Running touch blocking**: YES — block user touches to underlying app during Running. Takeover excepted.
2. **A11y island/⊖ policy**: Option B — keep compact UI (island + ⊖).
3. **A11y vs VD priority**: VD is long-term target (less user disruption). A11y is short-term target (easier onboarding, simpler permissions). Both must work perfectly now. No design impact — both paths get equal quality.
4. **VD viewer icon in Row3**: Remove. Unnecessary in idle/done, visually clutters input area.

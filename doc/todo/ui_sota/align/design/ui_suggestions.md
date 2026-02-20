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

### 1.1 Overlay touchability (`FLAG_NOT_TOUCHABLE`) conflicts with interactive capsule model (P0)

Mismatch:
- Design expects actionable overlay controls.
- Current runtime flag makes overlay capsule non-interactive.

User impact:
- In overlay contexts user cannot directly tap Takeover/Resume/Stop/Done/Close/input.

System impact:
- State machine looks healthy, but key transitions become unreachable by user touch.

Recommendation:
- Implement mode-driven touchability policy.
- Suggested baseline:
  - Non-touchable: `TakeoverPending`, `Hidden`
  - Touchable: `Running` (for interaction lock shield), `Takeover`, `WaitingForInput`, `WaitingForAction`, `Done`, `Error`

Note: `Running` must be touchable to enable the interaction lock shield (§1.3). The capsule UI elements themselves remain non-interactive during Running — only the full-screen touch-eating shield receives touches.

### 1.2 A11y island/minimize policy diverges from round6 baseline (P1) — RESOLVED

**User decision**: Option B — keep island + ⊖ for A11y.

Rationale: Users can minimize capsule for more screen space. Does not affect A11y normal operation.

Action:
- Keep current code (A11y island path enabled).
- Update round6 design doc / NavSpec doc to reflect this decision.
- NavSpecTest assertions for A11y ⊖ are now correct behavior.

### 1.3 Interaction lock semantics currently coupled to touchability flag (P1)

Mismatch:
- There is logic for full-screen lock in running states.
- With global non-touchable window, lock view cannot truly consume touch.

**User decision**: A11y Running MUST block user touches to underlying app. Takeover excepted.

Recommendation:
- After touchability toggle (§1.1): Running overlay should be touchable (to enable full-screen touch-eating shield), but capsule UI itself remains non-interactive in Running.
- Agent gestures need alternative routing (e.g., dispatch on virtual display layer or coordinate with shield).
- Takeover mode: overlay must pass touches through to underlying app for user operation.

### 1.4 VD main app viewer icon in Row3 during Idle/Done is unnecessary (P1) — NEW

Mismatch:
- Current code: `SmartCapsuleSurface.kt:140` shows 👁 viewer icon in Row3 (next to input) when `mode is Hidden && navSpec.showWatch`.
- In VD + MAIN_APP + Hidden (idle), this displays a viewer icon to open the virtual display viewer.

Problem:
- No active task running → virtual display has no meaningful content to show.
- Visually clutters the input area.
- Done mode: already excluded by NavSpec (`row2Hidden=true` → `showWatch=false`).

Recommendation:
- Remove the Row3 viewer icon entirely. Set `showOpenViewer = false` (or remove the conditional block).
- VD viewer should only be reachable during active task states via Row1 nav button (👁) or island tap.

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

1. P0: finalize touchability policy — mode-driven toggle with Running touchable for shield
2. P1: remove Row3 viewer icon from VD main app idle
3. P1: A11y island policy already resolved (keep ⊖) — update design docs + tests
4. P1: unify UserResponse immediate feedback path
5. P2: harden location-detection diagnostics
6. P2: route `dismissError` through controller

## 5. User Decisions (Resolved)

1. **A11y Running touch blocking**: YES — block user touches to underlying app during Running. Takeover excepted.
2. **A11y island/⊖ policy**: Option B — keep compact UI (island + ⊖).
3. **A11y vs VD priority**: VD is long-term target (less user disruption). A11y is short-term target (easier onboarding, simpler permissions). Both must work perfectly now. No design impact — both paths get equal quality.
4. **VD viewer icon in Row3**: Remove. Unnecessary in idle/done, visually clutters input area.

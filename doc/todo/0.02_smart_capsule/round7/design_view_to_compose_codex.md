status: draft

# View -> Compose Full Migration Design (Codex)

Date: 2026-02-16  
Scope: `app/src/main/kotlin/com/moonkey/androidagent/**`

---

## 1. Problem Statement

Current UI rendering is split across:
- Compose UI (main app chat + compose capsule)
- Legacy View overlays (smart capsule overlay, island, glow, action visualizer)

This creates three concrete problems:
1. **Inconsistent rendering paths**: same UX concept rendered by different engines.
2. **State drift risk**: host synchronization bugs can produce duplicate overlays.
3. **Maintenance overhead**: two UI stacks for one product domain.

Goal: migrate all **UI rendering code** to Compose so each feature has one rendering path.

---

## 2. Current View-Based Code Inventory

### 2.1 Rendering/UI modules (must migrate)

1. Smart Capsule overlay (legacy View stack)
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleManager.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleLayoutBuilder.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleRenderer.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleAnimator.kt`

2. Status Island overlay
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/StatusIslandManager.kt`

3. Edge Glow overlay
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/EdgeGlowManager.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/EdgeGlowView.kt`

4. Action visualizer overlay (tap/swipe effects)
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/visualizer/ActionVisualizerManager.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/visualizer/ClickRippleView.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/visualizer/SwipeTrailView.kt`

5. VD viewer rendering bridge
- `app/src/main/kotlin/com/moonkey/androidagent/ui/viewer/VirtualDisplayViewerActivity.kt` (uses `AndroidView(SurfaceView)`)

### 2.2 Non-rendering `android.view.*` usage (not a Compose target)

These are platform APIs (not UI widget rendering) and should remain:
- Accessibility tree APIs (`AccessibilityNodeInfo`, `AccessibilityWindowInfo`) in perception/platform.
- Input events (`MotionEvent`, `KeyEvent`, `InputEvent`) in injector.
- Surface/Display plumbing for virtual display capture and injection.

So “full Compose migration” must be interpreted as:
- **All user-visible UI rendering is Compose**
- Not “remove every `android.view` symbol from the whole app”.

---

## 3. Feasibility Decision

## 3.1 Can we make main-app capsule and overlay capsule 100% same rendering module?
**Yes.**

Approach:
- One shared composable surface: `SmartCapsuleSurface(...)`
- Main app uses it directly in Compose tree.
- Overlay uses `ComposeView` attached to `WindowManager` with `TYPE_ACCESSIBILITY_OVERLAY`.

This enforces one rendering implementation and eliminates renderer divergence.

## 3.2 Can we remove all custom View subclasses for overlays?
**Yes.**

`EdgeGlowView`, `ClickRippleView`, `SwipeTrailView` can all be replaced with Compose Canvas animations.

## 3.3 VD Viewer strictness note

Current viewer uses `SurfaceView` via `AndroidView`.  
Strict “no View widget at all” for viewer requires either:
- Compose-native external surface API available in current Compose runtime, or
- a custom Compose surface host abstraction wrapping platform surface lifecycle.

Design decision:
- Phase design targets **Compose-first viewer UI shell** immediately.
- Keep one technical spike to verify best surface host API under current BOM (`2024.12.01`) before implementation lock.

---

## 4. Target Architecture

## 4.1 UI rendering layers

1. Shared capsule rendering module (single source)
- `ui/capsule/surface/SmartCapsuleSurface.kt` (new)
- consumes `CapsuleRenderSpec`, `NavSpec`, callbacks
- no host-specific logic inside

2. Overlay host module (Compose window hosts)
- `ui/overlay/host/OverlayComposeHost.kt` (new): generic `WindowManager + ComposeView` host
- `ui/overlay/host/CapsuleOverlayHost.kt` (new)
- `ui/overlay/host/IslandOverlayHost.kt` (new)
- `ui/overlay/host/GlowOverlayHost.kt` (new)
- `ui/overlay/host/VisualizerOverlayHost.kt` (new)

3. Controller/state stays existing
- `ServiceOverlayController` remains visibility authority
- `CapsuleStateHolder` remains state machine authority

## 4.2 Host invariants

1. At any time only one capsule host can be visible:
- `MAIN_APP` -> compose capsule in chat only
- non-main -> overlay capsule/island per policy

2. Overlay hosts are dumb render hosts:
- no state transitions in host layer
- state transitions only in `CapsuleStateHolder` + policy layer

3. Animations move to Compose:
- glow pulse/fade in Compose animation APIs
- ripple/swipe visualizer via Compose Canvas + Animatable

---

## 5. Phased Migration Plan

## Phase 0: Baseline and guardrails

Deliverables:
- Snapshot/instrumentation assertions for:
  - no duplicate capsule
  - `MAIN_APP` hides overlay windows
  - VD glow only in viewer context
- Keep current behavior frozen before migration.

Exit criteria:
- baseline tests pass on current branch.

## Phase 1: Compose Overlay Host foundation

Deliverables:
- Add reusable `OverlayComposeHost`.
- Window param and focus policy abstraction moved out of legacy managers.

Exit criteria:
- host can show/hide a trivial Compose node reliably.

## Phase 2: Smart Capsule overlay migration

Deliverables:
- Introduce `SmartCapsuleSurface` composable.
- Replace `SmartCapsuleManager/LayoutBuilder/Renderer/Animator` with Compose host implementation.
- Main app capsule and overlay capsule both call `SmartCapsuleSurface`.

Exit criteria:
- legacy smart capsule view stack deleted.
- compose and overlay capsule visual parity verified.

## Phase 3: Status Island + Edge Glow migration

Deliverables:
- Replace `StatusIslandManager` rendering with Compose host.
- Replace `EdgeGlowView/EdgeGlowManager` drawing path with Compose Canvas host.

Exit criteria:
- legacy island/glow view classes deleted.

## Phase 4: Action visualizer migration

Deliverables:
- Replace `ActionVisualizerManager + ClickRippleView + SwipeTrailView` rendering with Compose canvas overlay.
- Preserve pass-through touch semantics and timing.

Exit criteria:
- legacy visualizer view classes deleted.

## Phase 5: VD viewer rendering migration

Deliverables:
- Compose-only viewer shell finalized.
- Surface host strategy locked after spike (external surface API or minimal compatibility bridge).

Exit criteria:
- no ad-hoc view rendering code in viewer feature path.

## Phase 6: Cleanup and docs

Deliverables:
- remove dead files/imports
- update `doc/main` and round docs
- add migration rationale and final architecture diagram

---

## 6. File-Level Migration Map

1. Delete after migration
- `ui/overlay/SmartCapsuleManager.kt`
- `ui/overlay/SmartCapsuleLayoutBuilder.kt`
- `ui/overlay/SmartCapsuleRenderer.kt`
- `ui/overlay/SmartCapsuleAnimator.kt`
- `ui/overlay/EdgeGlowView.kt`
- `ui/overlay/visualizer/ClickRippleView.kt`
- `ui/overlay/visualizer/SwipeTrailView.kt`

2. Rewrite in Compose-host style
- `ui/overlay/StatusIslandManager.kt`
- `ui/overlay/EdgeGlowManager.kt`
- `ui/overlay/visualizer/ActionVisualizerManager.kt`
- `ui/viewer/VirtualDisplayViewerActivity.kt`

3. Keep mostly unchanged (logic authority)
- `app/ServiceOverlayController.kt`
- `ui/overlay/CapsuleStateHolder.kt`
- `app/OverlayLocationPolicy.kt`
- `ui/overlay/model/*`

---

## 7. Risks and Mitigations

1. Overlay focus/IME regressions
- Mitigation: explicit host tests for `FLAG_NOT_FOCUSABLE` transitions and keyboard behavior in `WaitingForInput`.

2. Animation regressions (glow/ripple/trail)
- Mitigation: pixel-level screenshot checks + timing-based tests for animation lifecycle.

3. Surface host instability in VD viewer
- Mitigation: Phase-5 spike gate before deleting compatibility path.

4. Large diff / hard rollback
- Mitigation: phase-by-phase merge with feature flags (`useComposeOverlayHosts`).

---

## 8. Acceptance Criteria

1. No duplicate capsule visible at runtime.
2. Main app and overlay capsule render from the same composable module.
3. No legacy custom `View` rendering classes remain for capsule/island/glow/visualizer.
4. All P1 behaviors from round6 followup remain passing.
5. Build + unit tests + UX visual checks pass.

---

## 9. Recommendation

Proceed with phased migration, starting with **Phase 1 + Phase 2** first (highest payoff: removes dual capsule renderer risk immediately).  
After Phase 2, re-evaluate whether viewer surface can be fully Compose-native under current runtime before final hard cutover.


# Aligned Design: Migrate All View-Based Overlays to Compose

> Merged from Claude and Codex independent designs.
> Source: `doc/todo/0.02_smart_capsule/round7/design_view_to_compose_claude.md`
> Source: `doc/todo/0.02_smart_capsule/round7/design_view_to_compose_codex.md`

## 1. Problem Statement

Current UI rendering is split across two stacks:
- **Compose** (main app: ChatScreen, SmartCapsuleCompose)
- **Legacy View** (system overlays: SmartCapsuleManager, StatusIslandManager, EdgeGlowManager, ActionVisualizerManager + 3 custom View subclasses)

Concrete problems:
1. **Duplicate rendering bug**: In-app Compose capsule and overlay View capsule can render simultaneously (the "Creating task plan..." doubled text issue).
2. **Two codepaths** for the same spec (`CapsuleRenderSpec`), each with its own quirks.
3. **Maintenance overhead**: every capsule visual change must be made twice.

Goal: migrate all **user-visible UI rendering** to Compose. Platform APIs (`AccessibilityNodeInfo`, `MotionEvent`, `KeyEvent`, Surface/Display plumbing) remain as-is.

## 2. View-Based Code Inventory

| Component | Files | LOC | WindowManager | Rendering |
|---|---|---|---|---|
| Smart Capsule overlay | `SmartCapsuleManager`, `SmartCapsuleLayoutBuilder`, `SmartCapsuleRenderer`, `SmartCapsuleAnimator`, `CapsuleViews` | ~700 | TYPE_ACCESSIBILITY_OVERLAY, bottom | Programmatic View hierarchy |
| Status Island | `StatusIslandManager` | ~210 | TYPE_ACCESSIBILITY_OVERLAY, top | Programmatic View |
| Edge Glow | `EdgeGlowView`, `EdgeGlowManager` | ~550 | TYPE_ACCESSIBILITY_OVERLAY, full-screen, not-touchable | Custom Canvas + LinearGradient |
| Action Visualizer | `ActionVisualizerManager`, `ClickRippleView`, `SwipeTrailView` | ~460 | TYPE_ACCESSIBILITY_OVERLAY, full-screen, not-touchable | Custom Canvas circles + lines |
| VD Viewer | `VirtualDisplayViewerActivity` | ~140 | N/A (activity content) | SurfaceView in Compose AndroidView |

**Total**: ~2,060 LOC across 11 files.

## 3. Target Architecture

```
CapsuleStateHolder (single source of truth, no change)
    |
    +-- SmartCapsuleSurface (shared composable, new)
    |       \-- used by BOTH in-app and overlay hosts
    |
    +-- In-app: SmartCapsuleCompose wraps SmartCapsuleSurface
    |       \-- bottomBar in ChatScreen, no change to hosting
    |
    +-- Overlay: CapsuleOverlayHost (ComposeView in WindowManager)
    |       \-- wraps SmartCapsuleSurface
    |       \-- replaces: SmartCapsuleManager + LayoutBuilder + Renderer + Animator + CapsuleViews
    |
    +-- IslandOverlayHost (ComposeView in WindowManager)
    |       \-- replaces: StatusIslandManager
    |
    +-- GlowOverlayHost (ComposeView in WindowManager)
    |       \-- Compose Canvas for gradient edges
    |       \-- replaces: EdgeGlowView + EdgeGlowManager
    |
    +-- VisualizerOverlayHost (ComposeView in WindowManager)
    |       \-- Compose Canvas for ripple + trail
    |       \-- replaces: ActionVisualizerManager + ClickRippleView + SwipeTrailView
    |
    ServiceOverlayController (orchestrator, contract unchanged)
```

### Key architectural decisions:
- **Shared composable**: Extract `SmartCapsuleSurface` from the current `SmartCapsuleCompose`. Both in-app and overlay hosts call it. This guarantees one rendering implementation.
- **OverlayComposeHost**: Reusable utility for `WindowManager + ComposeView + LifecycleOwner` wiring. Per-feature hosts (`CapsuleOverlayHost`, `IslandOverlayHost`, etc.) use it.
- **Overlay hosts are dumb render hosts**: no state transitions, no business logic. State lives in `CapsuleStateHolder`, policy in `ServiceOverlayController`.

## 4. Compose-in-Overlay Infrastructure

The `AccessibilityService` must provide `LifecycleOwner` + `SavedStateRegistryOwner` for `ComposeView`.

Build:
- `ServiceLifecycleOwner` class: implements `LifecycleOwner` + `SavedStateRegistryOwner`, created in `AgentService.onCreate()`, follows service lifecycle
- `OverlayComposeHost` utility: factory that creates properly-configured `ComposeView` with tree owners and `ViewCompositionStrategy`
- Wire in `AgentService.kt`

## 5. Phased Plan

### Phase 0: Infrastructure + Baseline

**Deliverables**:
- `ServiceLifecycleOwner` + `OverlayComposeHost` infrastructure
- Validation: trivial Compose overlay from accessibility service
- Baseline assertions: no duplicate capsule, MAIN_APP hides overlay windows

**Exit criteria**: trivial Compose overlay works; baseline assertions pass.

### Phase 1: Smart Capsule Overlay -> Compose

**Deliverables**:
- Extract `SmartCapsuleSurface` from `SmartCapsuleCompose`
- `CapsuleOverlayHost` using `OverlayComposeHost` + `SmartCapsuleSurface`
- Port overlay-specific behaviors:
  - `FLAG_NOT_FOCUSABLE` toggling for keyboard -> `rememberOverlayKeyboardController()`
  - `flashSupplementConfirmation` -> transient state on `CapsuleStateHolder` + `LaunchedEffect`
  - Height animation -> Compose `AnimatedVisibility` / `animateContentSize`
- Wire into `ServiceOverlayController` (same public API)

**Delete**: `SmartCapsuleLayoutBuilder`, `SmartCapsuleRenderer`, `SmartCapsuleAnimator`, `CapsuleViews`
**Replace**: `SmartCapsuleManager` -> `CapsuleOverlayHost`

**Exit criteria**: legacy capsule View stack deleted; visual parity verified.

### Phase 2: Status Island -> Compose

**Deliverables**:
- `StatusIslandCompose` composable (dot + text + clickable)
- `IslandOverlayHost` using `OverlayComposeHost`

**Delete**: `StatusIslandManager`

### Phase 3: Edge Glow -> Compose

**Deliverables**:
- `EdgeGlowCompose` composable (Compose Canvas + `Brush.linearGradient`)
- Pulse via `rememberInfiniteTransition`, fade via `AnimatedVisibility`
- `GlowOverlayHost` using `OverlayComposeHost`

**Delete**: `EdgeGlowView`, `EdgeGlowManager`

### Phase 4: Action Visualizer -> Compose

**Deliverables**:
- `ActionVisualizerCompose` composable (Canvas, `SnapshotStateList<VisualizationItem>`, `withFrameMillis`)
- `VisualizerOverlayHost` using `OverlayComposeHost`
- Preserve pass-through touch semantics and timing

**Delete**: `ClickRippleView`, `SwipeTrailView`, `ActionVisualizerManager`

### Phase 5: Cleanup

- Delete empty `ui/overlay/visualizer/` directory
- Remove dead imports
- Update architecture docs

## 6. What NOT to Migrate

| Item | Reason |
|---|---|
| `SurfaceView` in VD Viewer | Already Compose-hosted via `AndroidView`. `SurfaceView` is a hardware surface with no Compose equivalent. Keep `AndroidView` wrapper. |
| `Toast.makeText` in MainActivity | Android system component, not custom View code. Optional Snackbar replacement, low priority. |
| Platform APIs (`AccessibilityNodeInfo`, `MotionEvent`, etc.) | Not UI rendering. |

Non-goal for this round:
- Do not force-remove `AndroidView(SurfaceView)` from VD viewer in this migration. If needed, run a separate technical spike after overlay migration completes.

## 7. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| ComposeView in AccessibilityService lifecycle setup | Phase 0 validates before any production changes |
| Overlay keyboard/IME regressions | `rememberOverlayKeyboardController()` helper; explicit tests for `FLAG_NOT_FOCUSABLE` transitions |
| Compose Canvas performance (glow, visualizer) | Comparable to View Canvas; `Modifier.graphicsLayer` for HW acceleration if needed |
| Animation regressions (glow pulse, ripple timing) | Visual regression checks per phase |
| Large diff / hard rollback | Phase-by-phase merges |

## 8. Open Questions

1. **VD Viewer SurfaceView spike timing**: Keep out of this migration critical path; optionally run later as a separate technical exploration.

## 9. File-Level Summary

**Delete (10 files)**:
- `ui/overlay/SmartCapsuleManager.kt`
- `ui/overlay/SmartCapsuleLayoutBuilder.kt`
- `ui/overlay/SmartCapsuleRenderer.kt`
- `ui/overlay/SmartCapsuleAnimator.kt`
- `ui/overlay/StatusIslandManager.kt`
- `ui/overlay/EdgeGlowView.kt`
- `ui/overlay/EdgeGlowManager.kt`
- `ui/overlay/visualizer/ActionVisualizerManager.kt`
- `ui/overlay/visualizer/ClickRippleView.kt`
- `ui/overlay/visualizer/SwipeTrailView.kt`

**New (10 files)**:
- `ui/overlay/compose/ServiceLifecycleOwner.kt`
- `ui/overlay/compose/OverlayComposeHost.kt`
- `ui/capsule/surface/SmartCapsuleSurface.kt`
- `ui/overlay/compose/CapsuleOverlayHost.kt`
- `ui/overlay/compose/IslandOverlayHost.kt`
- `ui/overlay/compose/StatusIslandCompose.kt`
- `ui/overlay/compose/GlowOverlayHost.kt`
- `ui/overlay/compose/EdgeGlowCompose.kt`
- `ui/overlay/compose/VisualizerOverlayHost.kt`
- `ui/overlay/compose/ActionVisualizerCompose.kt`

**Keep (logic authority, no change)**:
- `app/ServiceOverlayController.kt` (update references only)
- `ui/overlay/CapsuleStateHolder.kt`
- `ui/overlay/model/*`

## 10. Implementation Status (Codex, 2026-02-16)

Implemented in this pass:
- Added Compose-in-service infrastructure:
  - `ui/overlay/compose/ServiceLifecycleOwner.kt`
  - `ui/overlay/compose/OverlayComposeHost.kt`
- Extracted shared capsule renderer:
  - `ui/capsule/surface/SmartCapsuleSurface.kt`
  - `ui/capsule/SmartCapsuleCompose.kt` now wraps shared surface only.
- Migrated overlay renderers to Compose hosts:
  - `ui/overlay/compose/CapsuleOverlayHost.kt`
  - `ui/overlay/compose/IslandOverlayHost.kt`
  - `ui/overlay/compose/StatusIslandCompose.kt`
  - `ui/overlay/compose/GlowOverlayHost.kt`
  - `ui/overlay/compose/EdgeGlowCompose.kt`
  - `ui/overlay/compose/VisualizerOverlayHost.kt`
  - `ui/overlay/compose/ActionVisualizerCompose.kt`
- Rewired integration:
  - `app/AgentService.kt` now provides service lifecycle/saved-state owner and creates Compose hosts.
  - `app/ServiceOverlayController.kt` now orchestrates Compose hosts (no legacy View manager usage).
  - `ui/overlay/visualizer/ActionVisualizerManager.kt` now delegates to Compose visualizer host.
- Removed legacy View render stacks:
  - `SmartCapsuleManager`, `SmartCapsuleLayoutBuilder`, `SmartCapsuleRenderer`, `SmartCapsuleAnimator`
  - `StatusIslandManager`
  - `EdgeGlowManager`, `EdgeGlowView`
  - `ClickRippleView`, `SwipeTrailView`

Verification completed:
- `./gradlew :app:compileDebugKotlin`
- `./gradlew :app:testDebugUnitTest --tests \"com.moonkey.androidagent.app.OverlayLocationPolicyTest\" --tests \"com.moonkey.androidagent.ui.overlay.CapsuleStateHolderTest\" --tests \"com.moonkey.androidagent.ui.overlay.model.CapsuleRenderSpecTest\"`

Remaining validation for next pass:
- End-to-end on-device UX checks for overlay focus/IME behavior in WaitingForInput and Takeover.
- Visual parity checks for glow pulse and gesture visualizer timing.

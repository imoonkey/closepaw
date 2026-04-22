# Design: Migrate All View-Based Overlays to Compose

## Problem

The project has two parallel UI systems:

1. **Main app** -- pure Compose (ChatScreen, SmartCapsuleCompose, etc.)
2. **System overlays** -- traditional View (SmartCapsuleManager, StatusIslandManager, EdgeGlowManager, ActionVisualizerManager, plus 3 custom View subclasses)

This causes:
- **Duplication bug**: The in-app Compose capsule and the overlay View capsule can render simultaneously, producing doubled text (the issue seen with "Creating task plan for playing...").
- **Two rendering codepaths** for the same spec (`CapsuleRenderSpec`), each with its own quirks.
- **Maintenance cost**: Every capsule visual change must be made twice.

## Current View-Based Inventory

| Component | Files | WindowManager type | Rendering |
|---|---|---|---|
| **Smart Capsule overlay** | `SmartCapsuleManager`, `SmartCapsuleLayoutBuilder`, `SmartCapsuleRenderer`, `SmartCapsuleAnimator`, `CapsuleViews` | `TYPE_ACCESSIBILITY_OVERLAY`, bottom, focusable toggle | Programmatic LinearLayout/FrameLayout/EditText hierarchy, ~700 LOC total |
| **Status Island** | `StatusIslandManager` | `TYPE_ACCESSIBILITY_OVERLAY`, top | Programmatic LinearLayout + dot + TextView, ~210 LOC |
| **Edge Glow** | `EdgeGlowView`, `EdgeGlowManager` | `TYPE_ACCESSIBILITY_OVERLAY`, full-screen, not-touchable | Custom `View.onDraw()` with Canvas + LinearGradient, pulse animation, ~550 LOC |
| **Action Visualizer** | `ActionVisualizerManager`, `ClickRippleView`, `SwipeTrailView` | `TYPE_ACCESSIBILITY_OVERLAY`, full-screen, not-touchable | Custom `View.onDraw()` with Canvas circles + lines, ~460 LOC |
| **SurfaceView (VD Viewer)** | `VirtualDisplayViewerActivity` | N/A (activity content) | `SurfaceView` wrapped in Compose `AndroidView` -- already Compose-hosted |

**Total View code to migrate**: ~1,920 LOC across 10 files.

## Migration Strategy: ComposeView in WindowManager

The core technique is replacing programmatic View hierarchies with `ComposeView`:

```kotlin
// Before (View)
val container = LinearLayout(context).apply { ... }
windowManager.addView(container, params)

// After (Compose-in-overlay)
val composeView = ComposeView(context).apply {
    setViewTreeLifecycleOwner(serviceLifecycleOwner)
    setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
    setContent {
        SmartCapsuleContent(stateHolder, ...)
    }
}
windowManager.addView(composeView, params)
```

Key requirement: The `AccessibilityService` must provide a `LifecycleOwner` and `SavedStateRegistryOwner` for `ComposeView` to function. This is a one-time setup in the service.

## Architecture After Migration

```
CapsuleStateHolder (single source of truth)
    |
    +-- SmartCapsuleCompose (in-app, bottomBar in ChatScreen)
    |       \-- already Compose, no change
    |
    +-- OverlayCapsuleManager (overlay, ComposeView in WindowManager)
    |       \-- reuses SmartCapsuleCompose composable directly
    |       \-- replaces: SmartCapsuleManager, LayoutBuilder, Renderer, Animator, CapsuleViews
    |
    +-- OverlayIslandManager (overlay, ComposeView in WindowManager)
    |       \-- new StatusIslandCompose composable
    |       \-- replaces: StatusIslandManager
    |
    +-- OverlayEdgeGlowManager (overlay, ComposeView or Canvas in WindowManager)
    |       \-- Compose Canvas or drawBehind modifier
    |       \-- replaces: EdgeGlowView, EdgeGlowManager
    |
    +-- OverlayVisualizerManager (overlay, ComposeView in WindowManager)
    |       \-- Compose Canvas for ripple/trail
    |       \-- replaces: ActionVisualizerManager, ClickRippleView, SwipeTrailView
    |
    ServiceOverlayController (orchestrator, no change to contract)
```

## Phased Plan

### Phase 0: Compose-in-Overlay Infrastructure

**Goal**: Enable `ComposeView` to run inside `WindowManager` overlays from `AccessibilityService`.

**What to build**:
- `ServiceLifecycleOwner` class implementing `LifecycleOwner` + `SavedStateRegistryOwner`
  - Created in `AgentService.onCreate()`, started, stopped, destroyed with service lifecycle
- `ComposeOverlayHost` utility: factory function that creates a properly-configured `ComposeView` with tree owners attached
  - Handles `setViewTreeLifecycleOwner`, `setViewTreeSavedStateRegistryOwner`, `setViewCompositionStrategy`
- Validation: show a trivial Compose overlay (e.g. `Text("hello")`) from the accessibility service to confirm the pipeline works

**Files**:
- New: `ui/overlay/compose/ServiceLifecycleOwner.kt` (~60 LOC)
- New: `ui/overlay/compose/ComposeOverlayHost.kt` (~40 LOC)
- Edit: `app/AgentService.kt` (wire lifecycle owner)

### Phase 1: Smart Capsule Overlay -> Compose

**Goal**: Replace the ~700 LOC View capsule with a `ComposeView` overlay that reuses `SmartCapsuleCompose`.

**Approach**:
1. Create `OverlayCapsuleManager` that uses `ComposeOverlayHost` to create a `ComposeView`
2. Inside `setContent {}`, call the existing `SmartCapsuleCompose` composable, passing `CapsuleStateHolder` flows directly
3. The Compose composable already observes `CapsuleRenderSpec` and handles all rendering, animations, and input -- so no new rendering code needed
4. Port overlay-specific behaviors:
   - `WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE` toggling for keyboard (use `SideEffect` + `WindowManager.updateViewLayout`)
   - `flashSupplementConfirmation` -- convert to a composable `LaunchedEffect` on a `SharedFlow`
   - Debounce on button clicks (minor, can use Compose `remember` + timestamp)
5. The `SmartCapsuleAnimator` height animation becomes unnecessary -- Compose handles layout animation natively via `AnimatedVisibility`, `animateContentSize`
6. Wire into `ServiceOverlayController`: replace `SmartCapsuleManager` reference with `OverlayCapsuleManager`, keeping the same public API (`show()`, `hide()`, `dispose()`, callbacks)

**Files to delete** (after migration):
- `SmartCapsuleLayoutBuilder.kt`
- `SmartCapsuleRenderer.kt`
- `SmartCapsuleAnimator.kt`
- `CapsuleViews` data class (from `SmartCapsuleLayoutBuilder.kt`)

**Files to replace**:
- `SmartCapsuleManager.kt` -> `OverlayCapsuleManager.kt` (~150 LOC, down from ~430)

**Behavior differences to handle**:
- Keyboard management: In overlay context, `EditText` keyboard show/hide is handled manually. Compose `TextField` in an overlay needs the same `FLAG_NOT_FOCUSABLE` toggling + `InputMethodManager` calls. Encapsulate this in a `rememberOverlayKeyboardController()` helper.
- `flashSupplementConfirmation`: Currently mutates `TextView.text` directly. Convert to a transient state (`supplementFlash: StateFlow<String?>`) on `CapsuleStateHolder`, which the composable observes and auto-clears via `LaunchedEffect(delay)`.

### Phase 2: Status Island -> Compose

**Goal**: Replace the ~210 LOC View island with a `ComposeView` overlay.

**Approach**:
1. Create `StatusIslandCompose` composable: Row with animated dot + text, single `clickable` modifier
2. Create `OverlayIslandManager`: uses `ComposeOverlayHost`, `setContent { StatusIslandCompose(...) }`
3. Compose observes `CapsuleStateHolder.mode` directly via `collectAsState()` -- no manual `post {}` + `TextView.text` mutation

**Files to delete**:
- `StatusIslandManager.kt`

**New files**:
- `ui/overlay/compose/StatusIslandCompose.kt` (~80 LOC)
- `ui/overlay/compose/OverlayIslandManager.kt` (~80 LOC)

### Phase 3: Edge Glow -> Compose

**Goal**: Replace `EdgeGlowView` + `EdgeGlowManager` (~550 LOC) with a Compose canvas overlay.

**Approach**:
1. Create `EdgeGlowCompose` composable that uses `Canvas` or `Modifier.drawBehind` to draw 4 gradient edges
   - Same `LinearGradient` shader logic, just expressed as Compose `Brush.linearGradient`
   - Pulse animation via `rememberInfiniteTransition().animateFloat()`
   - Fade-in/fade-out via `AnimatedVisibility` with `fadeIn()`/`fadeOut()`
2. Create `OverlayEdgeGlowManager`: uses `ComposeOverlayHost`, emits a single Compose overlay
3. State-based coloring: observe `GlowState` from `CapsuleStateHolder` via `collectAsState()`

**Note**: Canvas-based drawing in Compose is comparable in performance to custom View `onDraw()`. Hardware layer acceleration is handled by the Compose runtime.

**Files to delete**:
- `EdgeGlowView.kt`
- `EdgeGlowManager.kt`

**New files**:
- `ui/overlay/compose/EdgeGlowCompose.kt` (~100 LOC)
- `ui/overlay/compose/OverlayEdgeGlowManager.kt` (~100 LOC)

### Phase 4: Action Visualizer -> Compose

**Goal**: Replace `ClickRippleView`, `SwipeTrailView`, `ActionVisualizerManager` (~460 LOC) with Compose canvas.

**Approach**:
1. Create `ActionVisualizerCompose` composable:
   - Maintains a list of active visualizations as `SnapshotStateList<VisualizationItem>`
   - Each item is a sealed class: `ClickRipple(x, y, longPress, startTime)` or `SwipeTrail(sx, sy, ex, ey, duration, scroll, startTime)`
   - Single `Canvas` composable draws all active items based on `withFrameMillis` progress
   - Items auto-remove after their animation duration via `LaunchedEffect`
2. Create `OverlayVisualizerManager`: uses `ComposeOverlayHost`, keeps container permanently attached, Compose content draws active items
3. Public API unchanged: `showClick(x, y)`, `showSwipe(...)`, `showScrollAsSwipe(...)`, `dispose()`

**Files to delete**:
- `ClickRippleView.kt`
- `SwipeTrailView.kt`
- `ActionVisualizerManager.kt`

**New files**:
- `ui/overlay/compose/ActionVisualizerCompose.kt` (~150 LOC)
- `ui/overlay/compose/OverlayVisualizerManager.kt` (~100 LOC)

### Phase 5: Cleanup

- Delete empty `ui/overlay/visualizer/` directory
- Move remaining model classes if needed
- Update `ServiceOverlayController` to reference new manager classes
- Verify `SurfaceView` in `VirtualDisplayViewerActivity` -- already Compose-hosted via `AndroidView`, no change needed
- Remove `Toast.makeText` calls in `MainActivity` and replace with Compose `Snackbar` (optional, low priority -- Toasts are Android-native, not custom View code)

## What NOT to Migrate

| Item | Reason |
|---|---|
| `SurfaceView` in VD Viewer | Already wrapped in Compose `AndroidView`. `SurfaceView` is a hardware surface -- there's no Compose equivalent for GPU-direct rendering. Keep `AndroidView` wrapper. |
| `Toast.makeText` in MainActivity | Android system component, not custom View code. Can optionally replace with Compose `Snackbar` but not a priority. |

## Risk Analysis

| Risk | Mitigation |
|---|---|
| `ComposeView` in `AccessibilityService` requires lifecycle owner setup | Phase 0 validates this works before any production code changes |
| Compose overlay keyboard handling differs from View | Encapsulate in `rememberOverlayKeyboardController()`, test systematically |
| Compose Canvas performance for full-screen effects (glow, visualizer) | Comparable to View Canvas; use `Modifier.graphicsLayer` for hardware acceleration if needed |
| Overlay z-order with multiple `ComposeView` windows | Same as current View: z-order is determined by `addView` order. No change. |
| Compose adds ~200KB to overlay APK overhead | Already shipping Compose for main app; overlay ComposeViews share the same runtime. Zero incremental APK cost. |

## LOC Impact Estimate

| Phase | Files deleted | Files added | Net LOC change |
|---|---|---|---|
| Phase 0 (infra) | 0 | 2 | +100 |
| Phase 1 (capsule) | 4 | 1 | -550 |
| Phase 2 (island) | 1 | 2 | -50 |
| Phase 3 (glow) | 2 | 2 | -350 |
| Phase 4 (visualizer) | 3 | 2 | -210 |
| Phase 5 (cleanup) | 0 | 0 | -20 |
| **Total** | **10** | **9** | **-1,080** |

## Dependency Order

```
Phase 0 (infra)
    |
    +---> Phase 1 (capsule)  -- highest value, fixes the duplication bug
    |
    +---> Phase 2 (island)   -- independent of Phase 1
    |
    +---> Phase 3 (glow)     -- independent
    |
    +---> Phase 4 (visualizer) -- independent
    |
    v
Phase 5 (cleanup)            -- after all above
```

Phases 1-4 are independent after Phase 0 and can be done in any order. Phase 1 is highest priority because it resolves the immediate duplication bug and eliminates the most code.

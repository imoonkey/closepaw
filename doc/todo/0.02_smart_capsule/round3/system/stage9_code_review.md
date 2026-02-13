# Stage 9 Code Review: VD Viewer + Status Island Integration

**Scope**: VirtualDisplayViewerActivity, StatusIslandManager, AgentService, ServiceOverlayController  
**Design ref**: system_design_round3.md §5

---

## 1. Correctness

### ✅ VD viewer lifecycle manages contexts correctly

**VirtualDisplayViewerActivity** (lines 62–77):

- `onStart`: calls `notifyViewerVisible(surfaceView)` (if surface ready) + `onViewerOpened()`
- `onStop`: calls `notifyViewerHidden()` + `onViewerClosed()`

**ServiceOverlayController**:

- `onViewerOpened()`: `setContext(SCREEN_VIEWING)`, `showCapsuleOverlay()`, `hideIsland()`, `updateNavContext(...)`
- `onViewerClosed()`: `setContext(BACKGROUND)`, `hideCapsuleOverlay()`, `showIsland()`

Matches the design: onStart → SCREEN_VIEWING + capsule visible + island hidden; onStop → BACKGROUND + capsule hidden + island visible.

### ✅ Island tap expands capsule overlay

**StatusIslandManager** (line 133): `setOnClickListener { onExpandCapsule() }`  
**AgentService** (lines 161–164): `onExpandCapsule = { overlayController?.onIslandTapped() }`  
**ServiceOverlayController.onIslandTapped()** (lines 114–120): sets SCREEN_VIEWING, shows capsule, hides island, updates nav context.

Tap → expand capsule (not open viewer) is implemented as specified.

### ✅ VD output switching

- `surfaceCreated`: `notifyViewerVisible(sv)` when surface is ready
- `onStart`: `surfaceView?.let { notifyViewerVisible(it) }` (handles surface ready before onStart)
- `onStop` / `surfaceDestroyed`: `notifyViewerHidden()` (idempotent in VirtualDisplayPlatform)

Ordering and idempotency are correct.

---

## 2. Architecture

### ✅ Context transitions are clear

- `onViewerOpened` / `onViewerClosed` update `CapsuleContext` and overlay visibility
- `onIslandTapped` mirrors viewer-open behavior (SCREEN_VIEWING, capsule shown, island hidden)
- `onMinimize` (capsule [1] ⊖): `hideCapsuleOverlay()`, `showIsland()` — already wired in Stage 7

### ✅ Island simplification

- Removed inline controls (`buildInlineControls`, `toggleInlineControls`, `controlsContainer`, `pauseIconText`)
- Single callback: `onExpandCapsule: () -> Unit`
- Island is a status pill only; controls live in the expanded capsule

### [MEDIUM] `updateNavContext` order in `onViewerOpened` / `onIslandTapped`

**Lines**: ServiceOverlayController.kt 124–130, 117–119

**Problem**: `updateNavContext` is called after `showCapsuleOverlay()`, which calls `renderMode()`. The first render uses the previous nav context; nav buttons may briefly show the wrong state before `updateNavContext` re-renders.

**Fix**: Call `updateNavContext` before `showCapsuleOverlay()`:

```kotlin
fun onViewerOpened() {
    stateHolder.setContext(CapsuleContext.SCREEN_VIEWING)
    capsuleManager.updateNavContext(
        CapsuleContext.SCREEN_VIEWING, platformMode, hasIsland = statusIslandManager != null
    )
    showCapsuleOverlay()
    hideIsland()
}
```

---

## 3. Edge Cases

### [MEDIUM] Viewer opens when no task is active

**Scenario**: User opens VDViewerActivity (e.g. via [3] from main app) after the session has completed or before a task starts.

**Current behavior**:

- `notifyViewerVisible`: `session?.getServices()?.platform as? VirtualDisplayPlatform ?: return` → no-op if no session/platform
- `onViewerOpened`: always runs → shows capsule overlay, hides island

**Result**: Capsule overlay appears; VD viewer may show black if there is no active VD. Acceptable but could be confusing.

**Recommendation**: Consider logging when `notifyViewerVisible` returns early, or documenting this behavior.

### [LOW] Island tapped after task completes

**Scenario**: `onTaskCompleted` calls `showSuccess("✓ Done!")`, which shows the island and schedules auto-hide after 3s. User taps island during that window.

**Current behavior**: `onIslandTapped()` → show capsule (Done state), hide island. User can see completion state and use [1] to minimize. Capsule shows Done; minimize hides capsule and shows island again. Behavior is coherent.

### [INFO] `notifyViewerHidden` called twice

**Scenario**: `onStop` calls `notifyViewerHidden()`; later `surfaceDestroyed` also calls it.

**Current behavior**: `VirtualDisplayPlatform.switchToImageReader()` is idempotent. Redundant calls are safe.

---

## 4. Code Quality

### ✅ No dead code

- VirtualDisplayViewerActivity: no ViewerCapsule, SwipeUpDismissOverlay, or exit hint
- StatusIslandManager: no inline controls or related fields
- Lifecycle and callbacks are wired consistently

### [LOW] Hardcoded log tag in LivePreviewSurface

**Line**: VirtualDisplayViewerActivity.kt 117, 121, 129

**Problem**: Uses `"VDViewerSurface"` instead of the activity’s `TAG`.

**Fix**: Use `TAG` from the companion object or pass it into the composable.

### [LOW] `onOpenViewer` default in ServiceOverlayController

**Line**: ServiceOverlayController.kt 37

**Problem**: `onOpenViewer: (() -> Unit)? = null` — AgentService always passes a non-null callback in VD mode. The default is only relevant when `statusIslandManager` is null (e.g. ACCESSIBILITY-only builds). Acceptable as-is.

---

## 5. Design Spec Compliance

| Spec | Implementation | Status |
|------|----------------|--------|
| VD Viewer: pure SurfaceView, no built-in controls | VirtualDisplayViewerActivity | ✅ |
| onStart → show capsule, hide island, SCREEN_VIEWING | onViewerOpened | ✅ |
| onStop → hide capsule, show island, BACKGROUND | onViewerClosed | ✅ |
| Island tap → expand capsule (not open viewer) | onExpandCapsule → onIslandTapped | ✅ |
| Remove inline controls from island | StatusIslandManager | ✅ |
| Capsule [1] ⊖ minimizes to island | onMinimize | ✅ (Stage 7) |

---

## 6. Summary

| Severity | Count |
|----------|-------|
| CRITICAL | 0 |
| HIGH | 0 |
| MEDIUM | 2 |
| LOW | 2 |
| INFO | 1 |

**Recommendation**: **APPROVE** — no critical or high issues. Medium items are minor and can be addressed in a follow-up.

### Suggested follow-ups

1. **[MEDIUM]** Call `updateNavContext` before `showCapsuleOverlay()` in `onViewerOpened` and `onIslandTapped` to avoid a brief wrong nav state.
2. **[MEDIUM]** Document or log when the viewer opens with no active session/platform.
3. **[LOW]** Use a consistent log tag in LivePreviewSurface.

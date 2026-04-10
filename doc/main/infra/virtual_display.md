# Virtual Display Platform

> Shizuku-based virtual display implementation for isolated app execution.
> -> See: [platform.md](platform.md) for AndroidPlatform interface and AccessibilityPlatform.
> Last updated: 2026-04-10 (commit: 4cce154)

## Architecture

> See: `platform/virtualdisplay/VirtualDisplayPlatform.kt`

```
VirtualDisplayPlatform (orchestrator)
├── VdLifecycleArbiter                 # State machine + concurrency arbiter
├── VirtualDisplayWindowAccessor       # A11y window/root filtered by displayId
├── NodeActionPerformer                # Shared node actions (root via WindowAccessor)
├── VirtualDisplayInputInjector        # Input injection (reflection + shell fallback)
├── VirtualDisplayCaptureCoordinator   # A11y tree + screenshot capture
├── VirtualDisplaySurfaceController    # Surface switching (ImageReader ↔ SurfaceView)
├── VirtualDisplayScreenshotProcessor  # Bitmap → ScreenImage + trace
├── VirtualDisplayAppController        # App launch on VD
├── VirtualDisplayViewerTouchHandler   # Forward viewer touch to VD
└── ShizukuClient                      # Binder wrapper (IDisplayManager, IInputManager)
```

## Lifecycle State Machine

> See: `platform/virtualdisplay/VdLifecycleArbiter.kt`

All VD operations go through `VdLifecycleArbiter`, which serializes lifecycle transitions and protects in-flight operational calls.

### States

| State | Description |
|-------|-------------|
| `Stopped` | No display. Initial and final state. |
| `Running(displayId, imageReader)` | Display active, operational calls allowed. |
| `Broken(reason, displayId, imageReader)` | Unrecoverable error (binder death). Carries resources for cleanup. |

### Concurrency Model

- **Lifecycle transitions** (`start`, `stop`, binder death): Take exclusive access via `lifecycleMutex`. `stop()` sets state to `Stopped` *before* draining ops (`preDrainState`) to prevent admission races.
- **Operational calls** (`captureScreen`, `performAction`, `launchApp`): Run under `withRunningLease` — increment atomic counter, check state, execute, decrement. Lifecycle transitions wait for in-flight ops to drain (5s timeout).
- **Binder death**: `markBroken()` is an emergency transition outside the mutex. Preserves `displayId` and `imageReader` for later cleanup.

### start() Rollback

If any step after VD creation fails (binder listener, state transition), `start()` rolls back: removes listener, releases display, closes reader, clears proxies, resets to `Stopped`.

## Hybrid Surface Model

`VirtualDisplaySurfaceController` manages two modes:

| Mode | Surface | Capture Method | When |
|------|---------|----------------|------|
| `IMAGE_READER` | `ImageReader` | `acquireLatestImage()` | Default — agent operating or viewer hidden |
| `LIVE_PREVIEW` | `SurfaceView` from viewer | `PixelCopy.request()` | Viewer visible — user watching live |

- `switchToLivePreview(surfaceView)` redirects VD output to viewer's `SurfaceView`. Allows surface replacement if the viewer is recreated.
- `switchToImageReader()` reverts to `ImageReader` surface
- `PixelCopy` fallback: after 2 consecutive failures, auto-reverts to `IMAGE_READER`

## Bounded Callbacks

> See: `platform/BoundedCallback.kt`

All callback-driven framework APIs use `boundedCallback()` — a shared helper that wraps `suspendCancellableCoroutine` with `withTimeoutOrNull` and `invokeOnCancellation` cleanup.

| API | Timeout | Cleanup |
|-----|---------|---------|
| `takeScreenshot` | 5s | Late callback closes `HardwareBuffer` |
| `takeScreenshotOfWindow` | 5s | Late callback closes `HardwareBuffer` |
| `PixelCopy.request` | 3s | None (PixelCopy has no cancellation API) |

## Input Injection

> See: `platform/virtualdisplay/VirtualDisplayInputInjector.kt`

### Primary Path: MotionEvent + setDisplayId

Events are constructed via `MotionEvent.obtain()` and targeted to the VD via `InputEvent.setDisplayId()` (hidden API, reflection). A round-trip verification test runs once on first use: sets displayId=42, reads it back. If verification fails, falls back to shell.

### Shell Fallback: `input -d <displayId>`

When `setDisplayId` reflection doesn't work (HiddenApiBypass failure on some ROMs), all injection methods fall back to shell commands via Shizuku:

| Action | Shell Command |
|--------|---------------|
| Tap | `input -d <id> tap <x> <y>` |
| Long press | `input -d <id> swipe <x> <y> <x> <y> <duration>` |
| Swipe | `input -d <id> swipe <x1> <y1> <x2> <y2> <duration>` |
| Key event | `input -d <id> keyevent <keycode>` |

### Cancellation Safety

Long-press and swipe track gesture ownership after `ACTION_DOWN`. On cancellation or mid-gesture failure, `sendBestEffortCancel()` sends `ACTION_CANCEL` to release the target UI from pressed/dragging state.

## Window Selection

> See: `platform/virtualdisplay/VirtualDisplayWindowAccessor.kt`

- **Single-root** (`getRootOnDisplay`): Picks topmost (highest-layer) `TYPE_APPLICATION` window, excluding overlays and IME. Used by `NodeActionPerformer` and `getCurrentPackageName()`.
- **Multi-root** (`getRootsOnDisplay`): All non-overlay/non-IME windows sorted by layer ascending (bottom-to-top for capture ordering).
- **Accessibility side** (`AccessibilityPlatform`): Same topmost-window policy for `getCurrentPackageName()`. Screenshot targets the topmost window ID.

## Display Metrics

> See: `platform/virtualdisplay/VirtualDisplayConfig.kt`

`fromPhysicalDisplay()` uses `WindowManager.maximumWindowMetrics.bounds` (API 31+) for full physical display dimensions including nav bar and cutout. Pre-API 31 falls back to `getRealMetrics()`.

## Resource Cleanup

- `clearCachedProxies()` called during `stop()`, binder death, and `start()` rollback
- `getCurrentPackageName()` recycles root node on both platforms
- `isKeyboardVisibleOnMainDisplay()` recycles all window objects
- Debug screenshots capped at 20 files (both accessibility and VD paths)

## ShizukuClient

> See: `platform/virtualdisplay/ShizukuClient.kt`

Thin wrapper for privileged Shizuku binder calls:

| Method | Underlying API |
|--------|---------------|
| `createVirtualDisplay(...)` | `IDisplayManager.createVirtualDisplay()` (API 33+ `VirtualDisplayConfig` vs legacy) |
| `releaseVirtualDisplay(displayId)` | `IDisplayManager.releaseVirtualDisplay()` |
| `setVirtualDisplaySurface(displayId, surface)` | `IDisplayManager.setVirtualDisplaySurface()` |
| `injectInputEvent(event, mode)` | `IInputManager.injectInputEvent()` |
| `clearCachedProxies()` | Clears proxy provider + display transport caches |
| `bypassHiddenApis()` | `HiddenApiBypass` for `setDisplayId()` and `ServiceManager` |

Supporting files: `ShizukuServiceProxyProvider`, `ShizukuDisplayTransport`, `ShizukuInputTransport`, `ShizukuActivityLauncher`, `ShizukuShellExecutor`, `ShizukuRuntimeGateway`.

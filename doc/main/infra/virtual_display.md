# Virtual Display Platform

> Shizuku-based virtual display implementation for isolated app execution.
> -> See: [platform.md](platform.md) for AndroidPlatform interface and AccessibilityPlatform.
> Last updated: 2026-03-09 (commit: f23287d)

## Architecture

> See: `platform/virtualdisplay/VirtualDisplayPlatform.kt`

```
VirtualDisplayPlatform (orchestrator)
├── VirtualDisplayWindowAccessor       # A11y window/root filtered by displayId
├── NodeActionPerformer                # Shared node actions (root via WindowAccessor)
├── VirtualDisplayInputInjector        # Shizuku input injection (tap/swipe/system)
├── VirtualDisplayCaptureCoordinator   # A11y tree + screenshot capture
├── VirtualDisplaySurfaceController    # Surface switching (ImageReader ↔ SurfaceView)
├── VirtualDisplayScreenshotProcessor  # Bitmap → ScreenImage + trace
├── VirtualDisplayAppController        # App launch on VD + keyboard dismiss
├── VirtualDisplayViewerTouchHandler   # Forward viewer touch to VD
└── ShizukuClient                      # Binder wrapper (IDisplayManager, IInputManager)
```

## Key Design Decisions

- **A11y tree via `displayId` filtering**: `VirtualDisplayWindowAccessor` filters `AccessibilityService.windows` by `window.displayId`. Reuses `Perceptor.snapshot()`.
- **Node-based actions via shared `NodeActionPerformer`**: Same implementation as `AccessibilityPlatform`, differing only in `rootProvider`.
- **Coordinate/system actions via Shizuku input injection**: `TapAt`, `LongPressAt`, `Swipe`, `SystemButton` inject events via `IInputManager` with `setDisplayId()` reflection.
- **Tap-to-focus disabled**: `allowTapToFocus()` returns `false`. After text actions, `dismissMainDisplayKeyboard()` sends BACK on display 0.

## Hybrid Surface Model

`VirtualDisplaySurfaceController` manages two modes:

| Mode | Surface | Capture Method | When |
|------|---------|----------------|------|
| `IMAGE_READER` | `ImageReader` | `acquireLatestImage()` | Default — agent operating or viewer hidden |
| `LIVE_PREVIEW` | `SurfaceView` from viewer | `PixelCopy.request()` | Viewer visible — user watching live |

- `switchToLivePreview(surfaceView)` redirects VD output to viewer's `SurfaceView`
- `switchToImageReader()` reverts to `ImageReader` surface
- `PixelCopy` fallback: after 2 consecutive failures, auto-reverts to `IMAGE_READER`

## Lifecycle

| Phase | What Happens |
|-------|-------------|
| `start()` | `bypassHiddenApis()`, creates `ImageReader`, `ShizukuClient.createVirtualDisplay()`, registers binder death listener, 200ms surface delay |
| Runtime | Captures a11y tree + screenshot, performs actions on VD |
| Viewer opens | `switchToLivePreview()` |
| Viewer closes | `switchToImageReader()` |
| `stop()` | Removes death listener, resets surface controller, releases VD, closes ImageReader |

## ShizukuClient

> See: `platform/virtualdisplay/ShizukuClient.kt`

Thin wrapper for privileged Shizuku binder calls:

| Method | Underlying API |
|--------|---------------|
| `createVirtualDisplay(...)` | `IDisplayManager.createVirtualDisplay()` (API 33+ `VirtualDisplayConfig` vs legacy) |
| `releaseVirtualDisplay(displayId)` | `IDisplayManager.releaseVirtualDisplay()` |
| `setVirtualDisplaySurface(displayId, surface)` | `IDisplayManager.setVirtualDisplaySurface()` |
| `injectInputEvent(event, mode)` | `IInputManager.injectInputEvent()` |
| `bypassHiddenApis()` | `HiddenApiBypass` for `setDisplayId()` and `ServiceManager` |

Supporting files: `ShizukuServiceProxyProvider`, `ShizukuDisplayTransport`, `ShizukuInputTransport`, `ShizukuActivityLauncher`, `ShizukuShellExecutor`, `ShizukuRuntimeGateway`.

## VirtualDisplayConfig

> See: `platform/virtualdisplay/VirtualDisplayConfig.kt`

`data class VirtualDisplayConfig(width, height, densityDpi, density)` with `fromPhysicalDisplay(context)` factory.

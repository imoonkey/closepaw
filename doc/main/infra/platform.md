# Platform Abstraction

> AndroidPlatform, Perceptor, screen perception, and virtual display support.
> Last updated: 2026-02-17 (commit: c57e349)

## AndroidPlatform

> See: `platform/AndroidPlatform.kt`

Abstraction for Android-specific operations: screen capture, action execution, app management. Two implementations exist: `AccessibilityPlatform` (default, real screen) and `VirtualDisplayPlatform` (Shizuku-based, isolated display).

### Interface

```kotlin
interface AndroidPlatform {
    suspend fun start() {}   // Acquire resources (no-op for accessibility)
    suspend fun stop() {}    // Release resources (no-op for accessibility)
    suspend fun captureScreen(): ScreenSnapshot
    suspend fun performAction(action: UIAction): ActionResult
    suspend fun launchApp(packageName: String): ActionResult
    suspend fun getInstalledApps(): List<AppInfo>
    fun hasRequiredPermissions(): Boolean
    fun getCurrentPackageName(): String?
    fun getDisplayInfo(): DisplayInfo
    fun allowTapToFocus(): Boolean = true
}
```

`start()` / `stop()` have default no-op implementations. `AccessibilityPlatform` does not override them. `VirtualDisplayPlatform` creates the virtual display in `start()` and tears it down in `stop()`.

`allowTapToFocus()` controls whether the `TypeExecutor` may attempt a tap-to-focus fallback for text input. Returns `false` in VD mode because tapping the virtual display to focus a field triggers unintended keyboard events on the physical screen.

`performAction` takes only a `UIAction` — no snapshot parameter. All atomic actions work with coordinates or focused state; element resolution happens in the executor layer.

### Data Types

```kotlin
data class DisplayInfo(val widthPixels: Int, val heightPixels: Int, val density: Float)
data class AppInfo(val packageName: String, val label: String, val isSystemApp: Boolean = false)
```

### Platform Selection

> See: `platform/PlatformFactory.kt`

`PlatformFactory.create()` selects the implementation based on `SessionConfig.platformMode`:

| Mode | Platform | Fallback |
|------|----------|----------|
| `ACCESSIBILITY` | `AccessibilityPlatform` | N/A |
| `VIRTUAL_DISPLAY` | `VirtualDisplayPlatform` | Falls back to `AccessibilityPlatform` if Shizuku is unavailable or permission not granted |

Shizuku availability and permission are checked at creation time. Fallback is logged but not yet surfaced to the user.

---

## AccessibilityPlatform

> See: `platform/AccessibilityPlatform.kt`

Implementation using Android Accessibility APIs. Constructor takes `AccessibilityService`, `SessionConfig`, optional `ActionVisualizerManager` (for UI ripple/trail feedback), and `TraceRecorder`.

### Atomic Platform Principle

Each `UIAction` variant maps to **exactly one** Android API call. The platform has zero strategy — no fallback, no target resolution, no UI change detection. Those responsibilities live in the executor layer.

> See: [tools.md](tools.md) for the executor architecture.

### Action Dispatch

```kotlin
override suspend fun performAction(action: UIAction): ActionResult = when (action) {
    is UIAction.ClickNodeAt      -> nodeActionPerformer.performNodeClickAt(action.x, action.y)
    is UIAction.TapAt            -> gestureInjector.injectTap(action.x, action.y)
    is UIAction.LongClickNodeAt  -> nodeActionPerformer.performNodeLongClickAt(action.x, action.y)
    is UIAction.LongPressAt      -> gestureInjector.injectLongPress(...)
    is UIAction.SetTextOnNodeAt  -> nodeActionPerformer.performSetTextOnNodeAt(...)
    is UIAction.SetTextOnFocused -> nodeActionPerformer.performSetTextOnFocused(...)
    is UIAction.Swipe            -> gestureInjector.injectSwipe(...)  // clamps to screen bounds
    is UIAction.SystemButton     -> ENTER via nodeActionPerformer, others via gestureInjector
    is UIAction.Wait             -> delay(action.durationMs)
}
```

Visualizer feedback: `ClickNodeAt` and `LongClickNodeAt` trigger `visualizer?.showClick()` before executing the node action, showing ripple effects on-screen.

### Component Structure

```text
AccessibilityPlatform (orchestrator)
├── NodeActionPerformer             # Shared node actions (click/long-click/set-text/enter)
├── AccessibilityGestureInjector    # Gesture/global-action dispatch (tap/swipe/system buttons)
├── AccessibilityScreenshotCapturer # Screenshot capture + trace persistence
├── AppManager                      # Shared installed-app query (PackageManager)
└── BitmapUtils                     # Shared bitmap scaling + JPEG compression
```

### Screen Capture

`captureScreen()` conditionally captures based on `PerceptionConfig`:

1. **Always captures accessibility tree** — needed for node finding, change detection, and trace
2. **Conditionally captures screenshot** — when `PerceptionConfig.capturesScreenshot` is true OR `TraceRecorder` is enabled (for debugging)
3. **Only includes screenshot in snapshot** when perception config requests it

Trace recording dumps both raw a11y tree (via `A11yTreeDumper.dump()`) and sanitized a11y tree (via `Perceptor.toPromptJson()`) as JSON artifacts.

---

## VirtualDisplayPlatform

> See: `platform/virtualdisplay/VirtualDisplayPlatform.kt`

Implementation running apps on a Shizuku virtual display, isolated from the physical screen.

### Architecture

```text
VirtualDisplayPlatform (orchestrator)
├── VirtualDisplayWindowAccessor       # A11y window/root filtered by displayId
├── NodeActionPerformer                # Shared node actions (root via WindowAccessor)
├── VirtualDisplayInputInjector        # Shizuku input injection (tap/swipe/long-press/system buttons)
├── VirtualDisplayCaptureCoordinator   # A11y tree + screenshot capture (ImageReader or PixelCopy)
├── VirtualDisplaySurfaceController    # Surface switching (ImageReader ↔ SurfaceView)
├── VirtualDisplayScreenshotProcessor  # Bitmap → ScreenImage conversion + trace
├── VirtualDisplayAppController        # App launch on VD + keyboard dismiss
├── VirtualDisplayViewerTouchHandler   # Forward viewer touch events to VD
└── ShizukuClient                      # Binder wrapper (IDisplayManager, IInputManager)
```

### Key Design Decisions

- **A11y tree via `displayId` filtering**: `VirtualDisplayWindowAccessor` filters `AccessibilityService.windows` by `window.displayId` to get only the virtual display's UI tree. Reuses `Perceptor.snapshot()` for conversion.
- **Node-based actions via shared `NodeActionPerformer`**: `ClickNodeAt`, `LongClickNodeAt`, `SetTextOnNodeAt`, `SetTextOnFocused`, and `SystemButton(ENTER)` use the same shared implementation as `AccessibilityPlatform`, differing only in `rootProvider`.
- **Coordinate-based actions via Shizuku input injection**: `TapAt`, `LongPressAt`, `Swipe`, and non-ENTER `SystemButton` inject `MotionEvent` via `IInputManager` with `setDisplayId()` reflection.
- **Tap-to-focus disabled**: `allowTapToFocus()` returns `false`. After each text action, `VirtualDisplayAppController.dismissMainDisplayKeyboard()` sends `KEYCODE_BACK` on display 0 as a safety net.
- **Coroutine-friendly**: All blocking waits use `delay()`, not `Thread.sleep()`.

### Hybrid Surface Model

The VD runs in one of two `VirtualDisplaySurfaceMode`s, managed by `VirtualDisplaySurfaceController`:

| Mode | Surface | Capture Method | When |
|------|---------|----------------|------|
| `IMAGE_READER` | `ImageReader` surface | `acquireLatestImage()` | Default — agent operating or viewer hidden |
| `LIVE_PREVIEW` | `SurfaceView` from viewer | `PixelCopy.request()` | Viewer visible — user watching live |

- `switchToLivePreview(surfaceView)` — redirects VD output to the viewer's `SurfaceView` via `ShizukuClient.setVirtualDisplaySurface()`, switches capture to `PixelCopy`.
- `switchToImageReader()` — reverts VD output to `ImageReader` surface.
- `PixelCopy` fallback: after 2 consecutive failures (`PIXEL_COPY_MAX_FAILURES`), `VirtualDisplayCaptureCoordinator` automatically reverts to `IMAGE_READER` mode.
- Surface state is tracked in a `@Volatile` `SurfaceState` data class, protected by `synchronized(stateLock)`.

### Lifecycle

| Phase | What Happens |
|-------|-------------|
| `start()` | `bypassHiddenApis()`, creates `ImageReader`, calls `ShizukuClient.createVirtualDisplay()`, registers binder death listener, delays `200ms` for surface readiness |
| Runtime | Captures a11y tree + screenshot (ImageReader or PixelCopy), performs actions on VD |
| Viewer opens | `switchToLivePreview()` — user sees live VD feed |
| Viewer closes | `switchToImageReader()` — back to background capture |
| `stop()` | Removes binder death listener, resets surface controller, releases VD, closes ImageReader |

### ShizukuClient

> See: `platform/virtualdisplay/ShizukuClient.kt`

Thin wrapper for privileged Shizuku binder calls using reflection on Android framework stubs.

| Method | Underlying API |
|--------|---------------|
| `isAvailable()` | `Shizuku.pingBinder()` |
| `hasPermission()` | Shizuku permission check |
| `createVirtualDisplay(...)` | `IDisplayManager.createVirtualDisplay()` (API 33+ `VirtualDisplayConfig` vs legacy) |
| `releaseVirtualDisplay(displayId)` | `IDisplayManager.releaseVirtualDisplay()` |
| `setVirtualDisplaySurface(displayId, surface)` | `IDisplayManager.setVirtualDisplaySurface()` via stored callback |
| `injectInputEvent(event, mode)` | `IInputManager.injectInputEvent()` |
| `bypassHiddenApis()` | `HiddenApiBypass` for `InputEvent.setDisplayId()` and `ServiceManager` access |
| `addBinderDeadListener/removeBinderDeadListener` | `Shizuku` binder death callbacks |

Supporting files in `virtualdisplay/`:
- `ShizukuServiceProxyProvider` — resolves `IDisplayManager`/`IInputManager` service proxies
- `ShizukuDisplayTransport` — manages display creation/release/surface switching
- `ShizukuInputTransport` — manages input event injection with `setDisplayId()` reflection
- `ShizukuActivityLauncher` — launches activities on a specific display via `ActivityOptions.setLaunchDisplayId()`
- `ShizukuShellExecutor` — executes shell commands via `Shizuku.newProcess()`
- `ShizukuRuntimeGateway` — coordinates availability/permission checks

### VirtualDisplayConfig

> See: `platform/virtualdisplay/VirtualDisplayConfig.kt`

```kotlin
data class VirtualDisplayConfig(
    val width: Int, val height: Int,
    val densityDpi: Int, val density: Float
) {
    companion object {
        fun fromPhysicalDisplay(context: Context): VirtualDisplayConfig
    }
}
```

---

## UIAction Types

> See: `platform/UIAction.kt`

`sealed interface UIAction` — atomic platform operations. Each variant maps to exactly one Android API call.

**Naming convention:**
- `*NodeAt` — accessibility node operation at coordinates (`ACTION_*`)
- `*At` — gesture operation at coordinates (`dispatchGesture` / input injection)
- `*OnFocused` — operation on currently focused node

| Action | Type | Description |
|--------|------|-------------|
| `ClickNodeAt(x, y)` | Node | Find clickable node at coords → `ACTION_CLICK` |
| `TapAt(x, y)` | Gesture | Gesture tap at coordinates |
| `LongClickNodeAt(x, y)` | Node | Find node at coords → `ACTION_LONG_CLICK` |
| `LongPressAt(x, y, durationMs)` | Gesture | Gesture hold at coordinates |
| `SetTextOnNodeAt(x, y, text, clear)` | Node | Find node at coords → `ACTION_SET_TEXT` |
| `SetTextOnFocused(text, clear)` | Node | Find focused editable → `ACTION_SET_TEXT` |
| `Swipe(startX, startY, endX, endY, durationMs)` | Gesture | Gesture swipe (scroll via swipe) |
| `SystemButton(button)` | System | Global action: `BACK`, `HOME`, `RECENTS`, `ENTER` |
| `Wait(durationMs)` | System | Pause execution |

---

## ActionResult

> See: `platform/ActionResult.kt`

```kotlin
sealed interface ActionResult {
    data class Success(val message: String = "Action completed") : ActionResult
    data class Failure(val reason: String) : ActionResult
    data class Cancelled(val reason: String = "Action cancelled") : ActionResult
    fun isSuccess(): Boolean = this is Success
}
```

No `ElementNotFound` — platform doesn't know about elements, returns `Failure("No clickable node at (x,y)")`. No `exception` field — exceptions are logged at source.

---

## NodeActionPerformer

> See: `platform/NodeActionPerformer.kt`

Shared node-action executor for both platforms. The only platform-specific dependency is `rootProvider: () -> AccessibilityNodeInfo?`.

| Method | Purpose |
|--------|---------|
| `performNodeClickAt(x, y)` | Find clickable node → `ACTION_CLICK` |
| `performNodeLongClickAt(x, y)` | Find long-clickable node → `ACTION_LONG_CLICK` |
| `performSetTextOnNodeAt(x, y, text, clear)` | Find text-input node → `ACTION_SET_TEXT` |
| `performSetTextOnFocused(text, clear)` | Find focused editable → `ACTION_SET_TEXT` |
| `performEnterKey()` | Focused editable → `ACTION_IME_ENTER` (API 30+) with `ACTION_CLICK` fallback |

All operations run on `Dispatchers.Main` (required for a11y API calls). Properly recycles `AccessibilityNodeInfo` objects via try/finally.

---

## AccessibilityNodeFinder

> See: `platform/AccessibilityNodeFinder.kt`

Internal helper for finding accessibility nodes in the a11y tree:

| Method | Purpose |
|--------|---------|
| `findClickableNodeAtLocation(x, y)` | Top-most clickable node at coordinates (reverse z-order search) |
| `findLongClickableNodeAtLocation(x, y)` | Top-most long-clickable node at coordinates |
| `findFocusedEditableNode()` | Currently focused editable node |
| `findNodeAtLocation(x, y)` | Text-input capable node at coordinates |

All methods check `isVisibleToUser` to avoid clicking invisible nodes. Properly recycles `AccessibilityNodeInfo` objects during traversal.

---

## Perceptor

> See: `perception/Perceptor.kt`

Converts raw `AccessibilityNodeInfo` tree into semantic `ScreenSnapshot`.

### Responsibilities

- Traverse accessibility tree with proper node recycling
- Extract element data (bounds, text, class) without storing raw nodes
- Filter off-screen elements and tiny elements
- Filter keyboard/IME nodes (Gboard, Samsung, SwiftKey)
- Clip bounds to screen dimensions
- Limit to `MAX_ELEMENTS` for token budget
- Generate JSON for LLM prompts via `toPromptJson()`

Both platforms reuse `Perceptor.snapshot()` — `AccessibilityPlatform` passes `service.rootInActiveWindow`, `VirtualDisplayPlatform` passes the root from `VirtualDisplayWindowAccessor.getRootOnDisplay()` with explicit width/height.

### Prompt JSON Example

```json
{
  "index": 0,
  "text": "Settings",
  "text_index": 0,
  "class": "android.widget.TextView",
  "clickable": true,
  "focused": false,
  "long_clickable": false,
  "bounds": [0, 100, 1080, 150],
  "center": [540, 125]
}
```

Notes:
- `text` is merged text (`element.text` fallback to `element.description`).
- `text_index` is emitted only when repeated visible labels need disambiguation.
- Boolean fields like `clickable`/`editable`/`scrollable` are emitted only when true.

---

## PerceptionConfig

> See: `perception/PerceptionConfig.kt`

Controls which perception modalities the agent captures each turn.

| Variant | Description |
|---------|-------------|
| `AccessibilityOnly` | A11y tree only. Current production default. |
| `ScreenshotOnly(maxDimension, jpegQuality)` | Screenshot only. For apps with poor a11y support. |
| `Hybrid(maxDimension, jpegQuality)` | Both modalities. Richest perception, highest token cost. |

Properties: `capturesAccessibility`, `capturesScreenshot`, `screenshotMaxDimension`, `screenshotJpegQuality`.

---

## ScreenSnapshot

> See: `model/Models.kt`

```kotlin
data class ScreenSnapshot(
    val timestamp: Long,
    val elements: List<PerceptionElement>?,  // null in screenshot-only mode
    val image: ScreenImage? = null,
    val debug: ScreenSnapshotDebug? = null
) {
    init { require(elements != null || image != null) { "..." } }
    val hasAccessibility: Boolean get() = !elements.isNullOrEmpty()
    val hasScreenshot: Boolean get() = image != null
}
```

At least one modality (a11y tree or screenshot) must be present.

---

## BitmapUtils

> See: `platform/BitmapUtils.kt`

Shared utility for both platforms:

| Method | Purpose |
|--------|---------|
| `scaleBitmapIfNeeded(bitmap, maxDimension)` | Downscale large bitmaps for LLM token budget |
| `compressJpeg(bitmap, quality)` | JPEG compression for screenshot payloads |

---

## File Structure

```
platform/
├── AndroidPlatform.kt         # Interface (with start/stop lifecycle, DisplayInfo, AppInfo)
├── PlatformFactory.kt         # Platform selection (checks SessionConfig.platformMode + Shizuku)
├── AccessibilityPlatform.kt   # Implementation using Accessibility APIs
├── AccessibilityGestureInjector.kt  # Gesture dispatch (tap/swipe/long-press/system buttons)
├── AccessibilityScreenshotCapturer.kt # Screenshot capture + trace persistence
├── AccessibilityNodeFinder.kt # Node search helpers (shared by both platforms)
├── NodeActionPerformer.kt     # Shared node action executor (click/text/enter)
├── AppManager.kt              # Shared installed-app query (PackageManager)
├── BitmapUtils.kt             # Bitmap scaling + JPEG compression (shared)
├── UIAction.kt                # Atomic action types (sealed interface)
├── ActionResult.kt            # Result types (Success/Failure/Cancelled)
└── virtualdisplay/
    ├── VirtualDisplayPlatform.kt          # Implementation using Shizuku + virtual display
    ├── VirtualDisplayConfig.kt            # Display configuration (width, height, density)
    ├── VirtualDisplayWindowAccessor.kt    # A11y window/root filtered by displayId
    ├── VirtualDisplayInputInjector.kt     # Input injection (tap/swipe/system buttons via Shizuku)
    ├── VirtualDisplayCaptureCoordinator.kt # A11y tree + screenshot capture coordination
    ├── VirtualDisplaySurfaceController.kt # Surface mode switching (ImageReader ↔ SurfaceView)
    ├── VirtualDisplayScreenshotProcessor.kt # Bitmap → ScreenImage + trace
    ├── VirtualDisplayAppController.kt     # App launch on VD + keyboard dismiss
    ├── VirtualDisplayViewerTouchHandler.kt # Forward viewer touch to VD input
    ├── ShizukuClient.kt                   # Shizuku binder wrapper (orchestrator)
    ├── ShizukuServiceProxyProvider.kt     # IDisplayManager/IInputManager proxy resolution
    ├── ShizukuDisplayTransport.kt         # Display create/release/surface operations
    ├── ShizukuInputTransport.kt           # Input event injection with displayId
    ├── ShizukuActivityLauncher.kt         # Activity launch on specific display
    ├── ShizukuShellExecutor.kt            # Shell command execution via Shizuku
    └── ShizukuRuntimeGateway.kt           # Availability/permission checks

perception/
├── PerceptionConfig.kt        # Capture mode (AccessibilityOnly, ScreenshotOnly, Hybrid)
├── Perceptor.kt               # A11y tree → ScreenSnapshot (shared by both platforms)
└── ScreenSummary.kt           # Text summary for history
```

---

## Related Docs

- [Tools](tools.md) - Tool execution and executor architecture
- [Loop](../agent/loop.md) - Perception in ReAct loop
- [Planning](../agent/planning.md) - Context hygiene

# Platform Abstraction

> AndroidPlatform, Perceptor, screen perception, and virtual display support.
> Last updated: 2026-02-11 (commit: c1cbe68)

## AndroidPlatform

→ See: `platform/AndroidPlatform.kt`

Abstraction for Android-specific operations. Two implementations exist: `AccessibilityPlatform` (default) and `VirtualDisplayPlatform` (Shizuku-based).

### Interface

```kotlin
interface AndroidPlatform {
    suspend fun start() {}   // Lifecycle: acquire resources (virtual display, ImageReader)
    suspend fun stop() {}    // Lifecycle: release resources
    suspend fun captureScreen(): ScreenSnapshot
    suspend fun performAction(action: UIAction): ActionResult
    suspend fun launchApp(packageName: String): ActionResult
    suspend fun getInstalledApps(): List<AppInfo>
    fun hasRequiredPermissions(): Boolean
    fun getCurrentPackageName(): String?
    fun getDisplayInfo(): DisplayInfo
}
```

`start()` / `stop()` have default no-op implementations. `AccessibilityPlatform` does not use them. `VirtualDisplayPlatform` creates the virtual display in `start()` and tears it down in `stop()`.

Note: `performAction` takes only a `UIAction` — no snapshot parameter. All atomic actions work with coordinates or focused state; element resolution happens in the executor layer.

### Operations

| Method | Purpose |
|--------|---------|
| `start()` | Acquire platform resources (no-op for accessibility) |
| `stop()` | Release platform resources (no-op for accessibility) |
| `captureScreen()` | Get current UI state as `ScreenSnapshot` |
| `performAction()` | Execute a single atomic UI action |
| `launchApp()` | Launch app by package name |
| `getInstalledApps()` | Query launchable apps |
| `hasRequiredPermissions()` | Check accessibility permission |
| `getCurrentPackageName()` | Get foreground app |
| `getDisplayInfo()` | Screen dimensions |

### Platform Selection

→ See: `platform/PlatformFactory.kt`

`PlatformFactory.create()` selects the platform implementation based on `SessionConfig.platformMode`:

| Mode | Platform | Fallback |
|------|----------|----------|
| `ACCESSIBILITY` | `AccessibilityPlatform` | N/A |
| `VIRTUAL_DISPLAY` | `VirtualDisplayPlatform` | Falls back to `AccessibilityPlatform` if Shizuku is unavailable |

Shizuku availability is checked at creation time. Fallback is logged but currently not surfaced to the user (planned for UI phase).

---

## AccessibilityPlatform

→ See: `platform/AccessibilityPlatform.kt`

Implementation of `AndroidPlatform` using Android Accessibility APIs.

### Atomic Platform Principle

Each `UIAction` variant maps to **exactly one** Android API call. The platform has zero strategy — no fallback, no target resolution, no UI change detection. Those responsibilities live in the executor layer.

→ See: [tools.md](tools.md) for the executor architecture.

### Action Dispatch

```kotlin
override suspend fun performAction(action: UIAction): ActionResult = when (action) {
    is UIAction.ClickNodeAt     -> performNodeClickAt(action.x, action.y)
    is UIAction.TapAt           -> performTap(...)
    is UIAction.LongClickNodeAt -> performNodeLongClickAt(action.x, action.y)
    is UIAction.LongPressAt     -> performLongPressGesture(...)
    is UIAction.SetTextOnNodeAt -> performSetTextOnNodeAt(...)
    is UIAction.SetTextOnFocused -> performSetTextOnFocused(...)
    is UIAction.Swipe           -> performSwipe(action)
    is UIAction.SystemButton    -> performSystemButton(action)
    is UIAction.Wait            -> performWait(action)
}
```

### Action Visualization Integration

Can integrate with `ActionVisualizerManager` to show visual feedback:

```kotlin
class AccessibilityPlatform(
    private val service: AccessibilityService,
    private val visualizer: ActionVisualizerManager? = null
) {
    private suspend fun performTap(x: Float, y: Float): ActionResult {
        visualizer?.showClick(x, y)
        // ... dispatch gesture
    }
}
```

---

## VirtualDisplayPlatform

→ See: `platform/virtualdisplay/VirtualDisplayPlatform.kt`

Implementation of `AndroidPlatform` that runs apps on a virtual display, isolated from the physical screen.

### Architecture

```
VirtualDisplayPlatform
├── ShizukuClient          # Binder access via Shizuku (reflection on framework stubs)
├── ImageReader            # Screenshot capture from virtual display Surface
├── AccessibilityService   # A11y tree filtered by displayId
└── AccessibilityNodeFinder # Reused from AccessibilityPlatform for node actions
```

### Key Design Decisions

- **A11y tree via `displayId` filtering**: Filters `AccessibilityService.windows` by `window.displayId` to get only the virtual display's UI tree. Reuses `Perceptor.snapshot()` for conversion.
- **Node-based actions via a11y `performAction()`**: `ClickNodeAt`, `LongClickNodeAt`, `SetTextOnNodeAt` use `AccessibilityNodeFinder` on the filtered root, same as `AccessibilityPlatform`.
- **Coordinate-based actions via Shizuku input injection**: `TapAt`, `LongPressAt`, `Swipe` inject `MotionEvent` via `IInputManager` with `setDisplayId()` reflection.
- **Screen capture via `ImageReader`**: `createVirtualDisplay()` renders to an `ImageReader` surface; `captureScreenshot()` acquires the latest image.
- **Coroutine-friendly**: All blocking waits use `delay()`, not `Thread.sleep()`.

### Lifecycle

| Phase | What Happens |
|-------|-------------|
| `start()` | Creates `ImageReader`, calls `ShizukuClient.createVirtualDisplay()`, registers binder death listener |
| Runtime | Captures a11y tree + screenshot, performs actions on virtual display |
| `stop()` | Releases `ImageReader`, clears display ID |

### ShizukuClient

→ See: `platform/virtualdisplay/ShizukuClient.kt`

Thin wrapper for privileged Shizuku binder calls using reflection on Android framework stubs.

| Method | Underlying API |
|--------|---------------|
| `isAvailable()` | `Shizuku.pingBinder()` + permission check |
| `createVirtualDisplay(config, surface)` | `IDisplayManager.createVirtualDisplay()` (API 33+ or legacy) |
| `injectInputEvent(event, mode)` | `IInputManager.injectInputEvent()` |
| `startActivityOnDisplay(displayId, intent)` | `ActivityOptions.setLaunchDisplayId()` + `am start` |
| `addBinderDeadListener(callback)` | `Shizuku.addBinderDeadListener()` |

Uses `HiddenApiBypass` for `InputEvent.setDisplayId()` and `ServiceManager` access. All reflection results use safe null checks with descriptive exceptions (no `!!`).

### VirtualDisplayConfig

→ See: `platform/virtualdisplay/VirtualDisplayConfig.kt`

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

### BitmapUtils

→ See: `platform/BitmapUtils.kt`

Extracted from `AccessibilityPlatform` for reuse:

| Method | Purpose |
|--------|---------|
| `scaleBitmapIfNeeded(bitmap, maxDimension)` | Downscale large bitmaps |
| `compressJpeg(bitmap, quality)` | JPEG compression for LLM |

---

## UIAction Types

→ See: `platform/UIAction.kt`

Atomic platform operations. Each variant maps to exactly one Android API call.

**Naming convention:**
- `*NodeAt` — accessibility node operation at coordinates (`ACTION_*`)
- `*At` — gesture operation at coordinates (`dispatchGesture`)
- `*OnFocused` — operation on currently focused node

| Action | Type | Description |
|--------|------|-------------|
| `ClickNodeAt(x, y)` | Node | Find clickable node at coords → `ACTION_CLICK` |
| `TapAt(x, y)` | Gesture | Gesture tap at coordinates |
| `LongClickNodeAt(x, y)` | Node | Find node at coords → `ACTION_LONG_CLICK` |
| `LongPressAt(x, y, durationMs)` | Gesture | Gesture hold at coordinates |
| `SetTextOnNodeAt(x, y, text, clear)` | Node | Find node at coords → `ACTION_SET_TEXT` |
| `SetTextOnFocused(text, clear)` | Node | Find focused editable → `ACTION_SET_TEXT` |
| `Swipe(startX, startY, endX, endY, durationMs)` | Gesture | Gesture swipe |
| `SystemButton(button)` | System | Global action (back, home, recents, enter) |
| `Wait(durationMs)` | System | Pause execution |

---

## ActionResult

→ See: `platform/ActionResult.kt`

Simple result of executing an atomic `UIAction`:

```kotlin
sealed interface ActionResult {
    data class Success(val message: String) : ActionResult
    data class Failure(val reason: String) : ActionResult
    data class Cancelled(val reason: String = "Action cancelled") : ActionResult

    fun isSuccess(): Boolean = this is Success
}
```

No `ElementNotFound` — platform doesn't know about elements, returns `Failure("No clickable node at (x,y)")`. No `exception` field — exceptions are logged at source.

---

## AccessibilityNodeFinder

→ See: `platform/AccessibilityNodeFinder.kt`

Internal helper for finding accessibility nodes in the a11y tree:

| Method | Purpose |
|--------|---------|
| `findClickableNodeAtLocation(x, y)` | Smallest clickable node at coordinates |
| `findLongClickableNodeAtLocation(x, y)` | Smallest long-clickable node at coordinates |
| `findFocusedEditableNode()` | Currently focused editable node |
| `findNodeAtLocation(x, y)` | Text-input capable node at coordinates |

All methods check `isVisibleToUser` to avoid clicking invisible nodes. Properly recycles `AccessibilityNodeInfo` objects.

---

## Perceptor

→ See: `perception/Perceptor.kt`

Converts raw AccessibilityNodeInfo tree into semantic `ScreenSnapshot`.

### Responsibilities

- Traverse accessibility tree with proper node recycling
- Extract element data (bounds, text, class) without storing raw nodes
- Filter off-screen elements and tiny elements
- Filter keyboard/IME nodes (Gboard, Samsung, SwiftKey)
- Clip bounds to screen dimensions
- Limit to `MAX_ELEMENTS` for token budget
- Generate JSON for LLM prompts via `toPromptJson()`

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

→ See: `perception/PerceptionConfig.kt`

Controls which perception modalities the agent captures each turn. Replaces the boolean `enableScreenshotInput` in `SessionConfig`.

| Variant | Description |
|---------|--------------|
| `AccessibilityOnly` | A11y tree only. Current production default. |
| `ScreenshotOnly(maxDimension, jpegQuality)` | Screenshot only. For apps with poor a11y support. |
| `Hybrid(maxDimension, jpegQuality)` | Both modalities. Richest perception, highest token cost. |

Properties: `capturesAccessibility`, `capturesScreenshot`, `screenshotMaxDimension`, `screenshotJpegQuality` (with defaults when not capturing screenshots).

---

## ScreenSnapshot

→ See: `model/Models.kt`

`elements` is now nullable (`List<PerceptionElement>?`). At least one modality must be present:

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

---

## Conditional Capture

`AccessibilityPlatform.captureScreen()` conditionally captures based on `PerceptionConfig`:

| Config | A11y tree | Screenshot |
|--------|-----------|------------|
| `AccessibilityOnly` | Yes | No |
| `ScreenshotOnly` | No | Yes |
| `Hybrid` | Yes | Yes |

Trace recording may capture screenshots for debugging even when `capturesScreenshot` is false.

---

## File Structure

```
platform/
├── AndroidPlatform.kt         # Interface (with start/stop lifecycle)
├── PlatformFactory.kt         # Platform selection (AccessibilityPlatform or VirtualDisplayPlatform)
├── AccessibilityPlatform.kt   # Implementation using Accessibility APIs
├── AccessibilityNodeFinder.kt # Node search helpers (shared by both platforms)
├── BitmapUtils.kt             # Bitmap scaling + JPEG compression (shared)
├── UIAction.kt                # Atomic action types
├── ActionResult.kt            # Result types
└── virtualdisplay/
    ├── VirtualDisplayPlatform.kt  # Implementation using Shizuku + virtual display
    ├── VirtualDisplayConfig.kt    # Display configuration (width, height, density)
    └── ShizukuClient.kt          # Shizuku binder wrapper (IDisplayManager, IInputManager)

perception/
├── PerceptionConfig.kt        # Capture mode (AccessibilityOnly, ScreenshotOnly, Hybrid)
├── Perceptor.kt               # A11y tree → ScreenSnapshot
└── ScreenSummary.kt           # Text summary for history
```

---

## Related Docs

- [Tools](tools.md) - Tool execution and executor architecture
- [Loop](../agent/loop.md) - Perception in ReAct loop
- [Planning](../agent/planning.md) - Context hygiene

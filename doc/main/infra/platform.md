# Platform Abstraction

> AndroidPlatform, action execution, capture wiring, and virtual display support.
> Last updated: 2026-03-09 (commit: f23287d)

## AndroidPlatform

> See: `platform/AndroidPlatform.kt`

Abstraction for Android operations: screen capture, action execution, app management. Two implementations: `AccessibilityPlatform` (default) and `VirtualDisplayPlatform` (Shizuku-based).

```kotlin
interface AndroidPlatform {
    suspend fun start() {}
    suspend fun stop() {}
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

`performAction` takes only a `UIAction` — no snapshot. Element resolution happens in the executor layer.

### Platform Selection

> See: `platform/PlatformFactory.kt`

| Mode | Platform | Fallback |
|------|----------|----------|
| `ACCESSIBILITY` | `AccessibilityPlatform` | N/A |
| `VIRTUAL_DISPLAY` | `VirtualDisplayPlatform` | Falls back to `AccessibilityPlatform` if Shizuku unavailable |

---

## AccessibilityPlatform

> See: `platform/AccessibilityPlatform.kt`

### Atomic Platform Principle

Each `UIAction` variant maps to **exactly one** Android API call. Zero strategy — no fallback, no target resolution, no UI change detection. Those live in the executor layer.

### Action Dispatch

| UIAction | Handler |
|----------|---------|
| `ClickNodeAt` / `LongClickNodeAt` / `SetTextOnNodeAt` / `SetTextOnFocused` / `ScrollNodeAt` | `NodeActionPerformer` |
| `TapAt` / `LongPressAt` / `Swipe` | `AccessibilityGestureInjector` |
| `SystemButton` | ENTER via `NodeActionPerformer`, others via gesture |
| `Wait` | `delay(durationMs)` |

Visualizer feedback: click/long-click trigger `showClick()` before executing.

### OverlayTouchGate

> See: `platform/OverlayTouchGate.kt`

During `dispatchGesture`, the overlay must become pass-through. `OverlayTouchGate` brackets injection with `acquirePassthrough()` / `releasePassthrough()` (sets `FLAG_NOT_TOUCHABLE`). Integrated into `AccessibilityGestureInjector`.

### Screen Capture

1. **Always captures accessibility tree** — needed for node finding, change detection, trace
2. **Conditionally captures screenshot** — when `PerceptionConfig.capturesScreenshot` or trace enabled
3. **Only includes screenshot in snapshot** when perception config requests it

---

## VirtualDisplayPlatform

-> See: [virtual_display.md](virtual_display.md) for full architecture, hybrid surface model, and ShizukuClient.

---

## UIAction Types

> See: `platform/UIAction.kt`

`sealed interface UIAction` — atomic platform operations. Naming: `*NodeAt` (a11y node op), `*At` (gesture op), `*OnFocused` (focused node op).

| Action | Type | Description |
|--------|------|-------------|
| `ClickNodeAt(x, y)` | Node | Find clickable → `ACTION_CLICK` |
| `TapAt(x, y)` | Gesture | Gesture tap |
| `LongClickNodeAt(x, y)` | Node | Find node → `ACTION_LONG_CLICK` |
| `LongPressAt(x, y, durationMs)` | Gesture | Gesture hold |
| `SetTextOnNodeAt(x, y, text, clear)` | Node | Find node → `ACTION_SET_TEXT` |
| `SetTextOnFocused(text, clear)` | Node | Focused editable → `ACTION_SET_TEXT` |
| `ScrollNodeAt(x, y, direction)` | Node | Find scrollable → `ACTION_SCROLL_*` |
| `Swipe(startX, startY, endX, endY, durationMs)` | Gesture | Raw coordinate swipe |
| `SystemButton(button)` | System | `BACK`, `HOME`, `RECENTS`, `ENTER` |
| `Wait(durationMs)` | System | Pause execution |

## ActionResult

> See: `platform/ActionResult.kt`

`Success(message)`, `Failure(reason)`, `Cancelled(reason)`. No `ElementNotFound` — platform returns `Failure("No clickable node at (x,y)")`.

---

## Shared Components

**NodeActionPerformer** (`platform/NodeActionPerformer.kt`): Shared node-action executor for both platforms. Only dependency: `rootProvider: () -> AccessibilityNodeInfo?`. All ops on `Dispatchers.Main`.

**AccessibilityNodeFinder** (`platform/AccessibilityNodeFinder.kt`): Internal helper for finding nodes in the a11y tree by location (clickable, long-clickable, scrollable, focused editable). Checks `isVisibleToUser`, properly recycles nodes.

**BitmapUtils** (`platform/BitmapUtils.kt`): `scaleBitmapIfNeeded()`, `compressJpeg()`.

---

## Perception Integration

-> See: [perception.md](perception.md) for the full capture pipeline, prompt JSON contract, and text semantics.

Platform-side: `AccessibilityPlatform` collects roots from relevant windows, excludes overlay/IME, retries empty-root capture 3×. `VirtualDisplayPlatform` uses display-scoped roots via `VirtualDisplayWindowAccessor`.

**PerceptionConfig** (`perception/PerceptionConfig.kt`): `AccessibilityOnly` (default), `ScreenshotOnly(maxDimension, quality)`, `Hybrid(maxDimension, quality)`.

**ScreenSnapshot** (`model/Models.kt`): `elements` (always present), `image` (optional), `keyboardVisible`, `textEnriched`, `debug`.

---

## File Structure

```
platform/
├── AndroidPlatform.kt                # Interface
├── PlatformFactory.kt                # Platform selection
├── AccessibilityPlatform.kt          # Accessibility implementation
├── AccessibilityGestureInjector.kt   # Gesture dispatch + touch gate
├── OverlayTouchGate.kt               # Gesture pass-through
├── AccessibilityScreenshotCapturer.kt # Screenshot + trace
├── AccessibilityNodeFinder.kt        # Node search
├── NodeActionPerformer.kt            # Shared node actions
├── AppManager.kt                     # Installed-app query
├── BitmapUtils.kt                    # Bitmap utils
├── UIAction.kt                       # Action types
├── ActionResult.kt                   # Result types
└── virtualdisplay/                   # → See virtual_display.md
```

## Related Docs

- [Virtual Display](virtual_display.md) - VirtualDisplayPlatform, ShizukuClient
- [Perception](perception.md) - Perceptor pipeline, ScreenSnapshot
- [Tools](tools.md) - Tool execution and executor architecture
- [Loop](../agent/loop.md) - Perception in ReAct loop

# Platform Abstraction

> AndroidPlatform, action execution, capture wiring, and virtual display support.
> Last updated: 2026-05-15 (action visualizer wiring restored across Accessibility, VD, and debug action paths)

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

`PlatformFactory.create()` receives the optional `ActionVisualizerManager` from the live
`AgentService` and passes it into whichever platform is actually constructed. This keeps visual
feedback tied to the effective platform, including VD sessions.

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

Visualizer feedback: node click/long-click trigger `showClick()` before executing. Gesture
tap/long-press/swipe feedback is emitted by `AccessibilityGestureInjector`, which also uses
`OverlayTouchGate` to make overlays pass through during `dispatchGesture()`.

### OverlayTouchGate

> See: `platform/OverlayTouchGate.kt`

During `dispatchGesture`, the overlay must become pass-through. `OverlayTouchGate` brackets injection with `acquirePassthrough()` / `releasePassthrough()` (sets `FLAG_NOT_TOUCHABLE`). Integrated into `AccessibilityGestureInjector`.

### Screen Capture

1. **Always captures accessibility tree** — needed for node finding, change detection, trace
2. **Conditionally captures screenshot** — when `PerceptionConfig.capturesScreenshot` or trace enabled
3. **Only includes screenshot in snapshot** when perception config requests it

### Window Selection

`collectRootsOnActiveDisplay()` collects all non-overlay/non-IME windows sorted by layer ascending. `getCurrentPackageName()` uses the topmost (highest-layer) `TYPE_APPLICATION` window — aligned with the VD platform's `getRootOnDisplay()`. Screenshot targets the topmost window ID.

---

## VirtualDisplayPlatform

-> See: [virtual_display.md](virtual_display.md) for full architecture, hybrid surface model, and ShizukuClient.

VD action dispatch follows the same atomic-platform rule but routes gesture actions through
`VirtualDisplayInputInjector` instead of `AccessibilityService.dispatchGesture()`. It still emits
the shared action visualizer on the real screen before node click/long-click, tap, long-press, and
raw swipe actions so users can see where the agent operated even though the target app lives on the
virtual display.

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

**BoundedCallback** (`platform/BoundedCallback.kt`): Shared bounded callback-to-suspend bridge with `withTimeoutOrNull` + `invokeOnCancellation`. Used by accessibility screenshot and VD PixelCopy paths.

---

## Perception Integration

-> See: [perception.md](perception.md) for the full capture pipeline, prompt JSON contract, and text semantics.

Platform-side: `AccessibilityPlatform` collects roots from relevant windows (topmost-layer selection for actions/privacy, all roots for capture), excludes overlay/IME, retries empty-root capture 3×. `VirtualDisplayPlatform` uses display-scoped roots via `VirtualDisplayWindowAccessor` with layer-ordered topmost window selection. VD captures run `Perceptor.snapshot()` off `Dispatchers.Main`.

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
├── AccessibilityScreenshotCapturer.kt # Bounded screenshot + trace
├── BoundedCallback.kt                # Shared bounded callback bridge
├── AccessibilityNodeFinder.kt        # Node search
├── NodeActionPerformer.kt            # Shared node actions
├── AppManager.kt                     # Installed-app query
├── BitmapUtils.kt                    # Bitmap utils
├── UIAction.kt                       # Action types
├── ActionResult.kt                   # Result types
└── virtualdisplay/                   # → See virtual_display.md
    ├── VdLifecycleArbiter.kt         # State machine + concurrency
    ├── VirtualDisplayPlatform.kt     # Orchestrator
    └── ...
```

## Related Docs

- [Virtual Display](virtual_display.md) - VirtualDisplayPlatform, ShizukuClient
- [Perception](perception.md) - Perceptor pipeline, ScreenSnapshot
- [Tools](tools.md) - Tool execution and executor architecture
- [Loop](../agent/loop.md) - Perception in ReAct loop

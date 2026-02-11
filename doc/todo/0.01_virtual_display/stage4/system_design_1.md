# Virtual Display System Design — Stage 4

Date: 2026-02-11

---

## Philosophy

This is not a framework change. This is plumbing. We need exactly
three new components and modifications to two existing ones. If you
find yourself writing an abstract base class, stop and rethink.

---

## Component Inventory

### New Components

1. **`StatusIslandManager`** — The small pill overlay on the real screen
2. **`VirtualDisplayViewerActivity`** — Full-screen live preview
3. **`VirtualDisplayFrameRelay`** — Connects `ImageReader` frames to
   `TextureView`

### Modified Components

4. **`ServiceOverlayController`** — Gains mode awareness (VD vs real)
5. **`AgentService`** — Wires new components, handles PlatformMode

---

## 1. StatusIslandManager

**File**: `ui/overlay/StatusIslandManager.kt`
**Lines**: ~120

This replaces SmartCapsule + EdgeGlow on the real screen during VD
mode. It shows one small floating pill and nothing else.

```kotlin
class StatusIslandManager(
    private val context: AccessibilityService,
    private val onTap: () -> Unit       // opens VirtualDisplayViewerActivity
) {
    private var pillView: View? = null
    private val wm = context.getSystemService(WindowManager::class.java)

    fun show()        // adds pill to WindowManager
    fun hide()        // removes pill
    fun isShowing(): Boolean

    fun updateStatus(text: String, dotColor: Int)
    fun showSuccess(message: String)   // shows ✅, auto-hides after 3s
    fun showError(message: String)     // shows ❌, auto-hides after 5s
}
```

The pill view is a simple `LinearLayout` with:
- A small circle `View` (status dot, 8dp)
- A `TextView` (status text, single line, 12sp)
- Rounded background drawable, translucent dark

Layout params:
```kotlin
WindowManager.LayoutParams(
    WRAP_CONTENT, WRAP_CONTENT,
    TYPE_ACCESSIBILITY_OVERLAY,
    FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN,
    PixelFormat.TRANSLUCENT
).apply {
    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    y = statusBarHeight + 8.dp
}
```

Note: `FLAG_NOT_TOUCHABLE` is NOT set. The pill is tappable. Touches
on the pill open the viewer; touches everywhere else pass through
because the pill is `WRAP_CONTENT` (small), not `MATCH_PARENT`.

**Lifecycle**: Created by `ServiceOverlayController` when a VD session
starts. Destroyed when session ends.

---

## 2. VirtualDisplayViewerActivity

**File**: `ui/viewer/VirtualDisplayViewerActivity.kt`
**Lines**: ~100

A simple Activity that shows the VD content with controls.

```kotlin
class VirtualDisplayViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen immersive
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            VirtualDisplayViewer(
                frameRelay = AgentService.instance?.frameRelay,
                onSwipeUp = { finish() }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // frameRelay stays alive — agent keeps running
    }
}
```

The Compose content:

```kotlin
@Composable
fun VirtualDisplayViewer(
    frameRelay: VirtualDisplayFrameRelay?,
    onSwipeUp: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 1. VD frame preview (TextureView via AndroidView)
        if (frameRelay != null) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also { frameRelay.attachOutput(it) }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Edge glow (Compose, over the preview)
        EdgeGlowOverlay(state = currentGlowState)

        // 3. Smart Capsule (Compose, bottom)
        SmartCapsuleOverlay(
            modifier = Modifier.align(Alignment.BottomCenter),
            /* same callbacks as current capsule: stop, pause, resume */
        )

        // 4. Swipe-up detector
        SwipeUpDismiss(onDismiss = onSwipeUp)
    }
}
```

**Manifest entry:**
```xml
<activity
    android:name=".ui.viewer.VirtualDisplayViewerActivity"
    android:theme="@style/Theme.AndroidAgent.FullScreen"
    android:exported="false"
    android:launchMode="singleTask" />
```

`singleTask` prevents multiple instances. The Activity doesn't handle
any intents — it reads state directly from `AgentService.instance`.

---

## 3. VirtualDisplayFrameRelay

**File**: `platform/virtualdisplay/VirtualDisplayFrameRelay.kt`
**Lines**: ~60

Bridges `ImageReader` frames to an optional `TextureView` output.

```kotlin
class VirtualDisplayFrameRelay(
    private val imageReader: ImageReader
) {
    @Volatile private var outputSurface: SurfaceTexture? = null
    private val listener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            outputSurface?.let { surface ->
                // Blit image planes to outputSurface via Canvas or GL
                blitToSurface(image, surface)
            }
        } finally {
            image.close()
        }
    }

    fun start() {
        imageReader.setOnImageAvailableListener(listener, Handler(Looper.getMainLooper()))
    }

    fun stop() {
        imageReader.setOnImageAvailableListener(null, null)
        outputSurface = null
    }

    fun attachOutput(textureView: TextureView) {
        outputSurface = textureView.surfaceTexture
    }

    fun detachOutput() {
        outputSurface = null
    }
}
```

The relay does NOT interfere with `captureScreenshot()` in
`VirtualDisplayPlatform`. Screenshot capture calls
`acquireLatestImage()` directly — this is fine because `ImageReader`
with `maxImages=2` allows concurrent access. The relay's listener
fires independently.

Actually, there's a subtlety: if both the relay listener AND
`captureScreenshot` call `acquireLatestImage()`, they'd compete.
The solution is simple: give the relay its *own* ImageReader-consumer
pipeline. We change `VirtualDisplayPlatform.start()` to create two
ImageReaders sharing the same VirtualDisplay surface via a
`SurfaceTexture` → that's overcomplicated.

Simpler approach: the relay doesn't use `ImageReader` at all. The
`VirtualDisplay`'s `Surface` can be reassigned. When the viewer opens,
we swap the ImageReader's surface for a shared surface that feeds both
the ImageReader (for screenshots) and the TextureView (for preview).

Simplest approach: **Don't make the relay real-time at all.** Have
`captureScreenshot()` cache the last `Bitmap` it produced. The
viewer Composable polls this cached bitmap on a timer (200ms). It's
simple, adequate, and involves zero `Surface` plumbing.

```kotlin
class VirtualDisplayFrameRelay {
    @Volatile var lastFrame: Bitmap? = null
        private set

    fun updateFrame(bitmap: Bitmap) {
        lastFrame?.recycle()
        lastFrame = bitmap.copy(bitmap.config, false)
    }

    fun dispose() {
        lastFrame?.recycle()
        lastFrame = null
    }
}
```

And in the Composable:
```kotlin
val frame by produceState<Bitmap?>(null) {
    while (isActive) {
        value = frameRelay?.lastFrame
        delay(200)  // 5fps is fine for monitoring
    }
}
frame?.let {
    Image(it.asImageBitmap(), contentDescription = "VD Preview",
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Fit)
}
```

This is 20 lines of code instead of a Surface pipeline. The user
is just *watching*. 5fps is more than enough.

---

## 4. ServiceOverlayController Changes

**File**: `app/ServiceOverlayController.kt`

Current behavior: always shows SmartCapsule + EdgeGlow on the real
screen. New behavior: check platform mode.

```kotlin
class ServiceOverlayController(
    context: AccessibilityService,
    private val appPackage: String,
    private val logTag: String,
    private val onStop: () -> Unit,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onOpenApp: () -> Unit
) {
    private val edgeGlowManager = EdgeGlowManager(context)
    private val capsuleManager = SmartCapsuleManager(...)

    // NEW: Status Island for VD mode
    private val statusIslandManager = StatusIslandManager(context) {
        // onTap: open viewer activity
        val intent = Intent(context, VirtualDisplayViewerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // NEW: Which mode are we in?
    private var platformMode: PlatformMode = PlatformMode.ACCESSIBILITY

    fun setPlatformMode(mode: PlatformMode) {
        platformMode = mode
    }
```

Then every method that currently calls `capsuleManager.xxx()` or
`edgeGlowManager.xxx()` is wrapped:

```kotlin
fun onTaskStarted(taskId: String, input: String) {
    hasActiveTask = true
    currentTaskInput = input
    currentGlowState = GlowState.Active

    when (platformMode) {
        PlatformMode.VIRTUAL_DISPLAY -> {
            statusIslandManager.updateStatus(input, GlowState.Active.color)
            statusIslandManager.show()
        }
        PlatformMode.ACCESSIBILITY -> {
            // existing logic unchanged
            if (!isAppInForeground) {
                edgeGlowManager.show(GlowState.Active)
                capsuleManager.onTaskStarted(taskId, input)
            }
        }
    }
}
```

This isn't elegant — it's a flat `when` switch in each method. But
it reads top-to-bottom, has zero indirection, and makes the behavior
for each mode completely explicit. You can read any single method
and understand both modes without jumping to another file.

---

## 5. AgentService Changes

**File**: `app/AgentService.kt`

Two changes:

### 5a. Set platform mode on overlay controller

In `runAgent()`, after creating the session:

```kotlin
overlayController?.setPlatformMode(config.platformMode)
```

### 5b. Expose frame relay

```kotlin
// In AgentService
var frameRelay: VirtualDisplayFrameRelay? = null
    private set
```

In `handleEvent`, when `ActionExecuted` or `ScreenCaptured` fires
for a VD session, update the relay:

```kotlin
is AgentEvent.ScreenCaptured -> {
    // The platform already captured a bitmap — cache it in relay
    // This is done inside VirtualDisplayPlatform.captureScreenshot()
}
```

Actually, the cleaner integration point is directly in
`VirtualDisplayPlatform.captureScreenshot()`. After producing the
`Bitmap` and before recycling it, push a copy to the relay:

```kotlin
// In VirtualDisplayPlatform.captureScreenshot():
val scaled = BitmapUtils.scaleBitmapIfNeeded(cropped, maxDim)
frameRelay?.updateFrame(scaled)   // <-- new line
val bytes = BitmapUtils.compressJpeg(scaled, quality)
```

The `frameRelay` is injected into `VirtualDisplayPlatform` at
construction time via `PlatformFactory`.

---

## Bug Fix: Overlay Leak

**Root cause**: `ServiceOverlayController` always shows
capsule+glow on the real screen when `hasActiveTask && !isAppInForeground`.
In VD mode, the agent is always "in background" (it's on a different
display), so overlays always show.

**Fix**: The `when(platformMode)` branches above handle this. In VD
mode, capsule and glow are never shown on the real screen. Only
`StatusIslandManager` is used. Done.

---

## Bug Fix: Keyboard Popup

**Root cause**: `ACTION_SET_TEXT` on an accessibility node focuses the
IME on the default display, even when the node is on a virtual
display. Android's IME routing is per-display in theory, but in
practice the soft keyboard often appears on the default display.

**Fix (two-pronged)**:

### 6a. Clear focus after text input (already exists)

`NodeActionPerformer.performSetTextOnNodeAt()` already clears focus
via `ACTION_CLEAR_FOCUS` after setting text. This handles the most
common case.

### 6b. Dismiss keyboard via shell command (new)

After any `SetText` action in VD mode, the platform calls:

```kotlin
// In VirtualDisplayPlatform.performAction(), after SetText cases:
dismissMainDisplayKeyboard()

private fun dismissMainDisplayKeyboard() {
    // Uses Shizuku to run: am broadcast -a com.android.server.InputMethodManagerService.HIDE_SOFT_INPUT
    // Or simpler: inject BACK key on display 0 if IME is showing
    try {
        val cmd = arrayOf("input", "keyevent", "--display", "0", "111") // KEYCODE_ESCAPE
        shizuku.executeShellCommand(cmd)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to dismiss main display keyboard", e)
    }
}
```

This is a pragmatic band-aid. The keyboard might flicker briefly
before being dismissed. The real fix is to never trigger the IME on
display 0 in the first place, which would require injecting key events
for typing instead of using `ACTION_SET_TEXT`. That's a separate
feature (Phase 5 scope).

---

## File Plan

| File | Action | Lines (est) |
|---|---|---|
| `ui/overlay/StatusIslandManager.kt` | NEW | ~120 |
| `ui/viewer/VirtualDisplayViewerActivity.kt` | NEW | ~100 |
| `platform/virtualdisplay/VirtualDisplayFrameRelay.kt` | NEW | ~30 |
| `app/ServiceOverlayController.kt` | MODIFY | +40 |
| `app/AgentService.kt` | MODIFY | +15 |
| `platform/virtualdisplay/VirtualDisplayPlatform.kt` | MODIFY | +10 |
| `platform/PlatformFactory.kt` | MODIFY | +5 |
| `AndroidManifest.xml` | MODIFY | +5 |

Total new code: ~325 lines. Modifications: ~75 lines.

---

## What This Design Does NOT Do

- **No multi-touch passthrough** to the VD from the viewer. The user
  watches, they don't interact with the VD directly. The agent is
  the one operating.
- **No picture-in-picture mode**. PiP adds complexity for marginal
  benefit. The Status Island → tap → full-screen viewer is good
  enough.
- **No recording/replay**. Out of scope.
- **No Compose navigation within the viewer**. It's a single screen.
  Open, watch, close.

---

## Dependencies

- No new libraries.
- `TextureView` or `Image` composable — both are in the standard SDK.
- `VirtualDisplayViewerActivity` needs a theme with no action bar
  and full-screen flags.

---

## Implementation Order

1. `StatusIslandManager` — standalone, testable in isolation
2. `ServiceOverlayController` modifications — mode branching
3. `VirtualDisplayFrameRelay` — trivial Bitmap cache
4. `VirtualDisplayPlatform` modification — push frames to relay
5. `VirtualDisplayViewerActivity` — ties everything together
6. `AgentService` wiring — connects relay and mode
7. Bug fixes — keyboard dismiss after SetText
8. `AndroidManifest.xml` — register new Activity

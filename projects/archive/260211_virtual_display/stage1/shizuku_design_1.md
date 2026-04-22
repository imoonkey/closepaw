# Virtual Display via Shizuku — Design

> Three files. No managers-of-managers. No abstract factory builder patterns.
> Just code that creates a display, injects input, and captures the screen.

---

## 1. Why

The agent operates on the user's real screen. This sucks for everybody:

- **User can't use their phone** while the agent works
- **User touches disrupt the agent** mid-task
- **Agent actions are visible** and distracting

The fix: create a second Android display. The agent works there.
The user keeps their phone. Everybody wins.

---

## 2. What Shizuku Gives Us

Shizuku runs a process with `shell` UID. Through `ShizukuBinderWrapper`, our
app can call system APIs that normally require shell/system permissions:

| API | What it does | Why we need it |
|-----|-------------|----------------|
| `IDisplayManager.createVirtualDisplay()` | Creates a display | The whole point |
| `IInputManager.injectInputEvent()` | Injects touch/key events | Agent needs to tap/swipe/type |
| `ActivityOptions.setLaunchDisplayId()` | Launches app on a specific display | Start target app on our display |

What we do **NOT** need Shizuku for:

| Capability | How we get it | Why it's free |
|------------|--------------|---------------|
| **Screen capture** | `ImageReader` as the display Surface | We own the display, we own its pixels |
| **A11y tree** | Existing `AccessibilityService` | A11y service sees all displays, filter by `displayId` |

This split is important: Shizuku is only needed for things that require
shell permission. Everything else uses standard APIs.

---

## 3. How It Fits the Existing Architecture

The current architecture already supports this. `AndroidPlatform` is an interface:

```kotlin
interface AndroidPlatform {
    suspend fun captureScreen(): ScreenSnapshot
    suspend fun performAction(action: UIAction): ActionResult
    fun getDisplayInfo(): DisplayInfo
    fun getCurrentPackageName(): String?
    fun hasRequiredPermissions(): Boolean
    suspend fun getInstalledApps(): List<AppInfo>
    suspend fun launchApp(packageName: String): ActionResult
}
```

`AccessibilityPlatform` implements it for the real screen. We add
`VirtualDisplayPlatform` that implements it for a virtual display.

The `Agent`, `SessionServices`, `ToolRouter`, `Perceptor` — none of them know
or care. They talk to `AndroidPlatform`. The virtual display is invisible to
everything above the platform layer.

```
Agent → SessionServices → AndroidPlatform ──→ AccessibilityPlatform  (real screen)
                                           └→ VirtualDisplayPlatform (virtual display)
```

`SessionServices.create()` already takes `platform: AndroidPlatform`.
Zero changes needed above the platform layer.

---

## 4. The Three New Files

### 4.1 `platform/shizuku/ShizukuClient.kt`

Thin wrapper around Shizuku binder calls. No business logic. Just plumbing.

```kotlin
package com.moonkey.androidagent.platform.shizuku

/**
 * ShizukuClient — Talks to Shizuku. Gets binders. That's it.
 *
 * Every method here is a direct wrapper around a system service call
 * forwarded through ShizukuBinderWrapper. No caching, no state, no cleverness.
 */
class ShizukuClient {

    // ── Status ──────────────────────────────────────────────

    fun isAvailable(): Boolean = Shizuku.pingBinder()

    fun hasPermission(): Boolean =
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun requestPermission(requestCode: Int) =
        Shizuku.requestPermission(requestCode)

    // ── Display ─────────────────────────────────────────────

    fun createVirtualDisplay(
        name: String, width: Int, height: Int, dpi: Int,
        surface: Surface, flags: Int
    ): Int {
        // IDisplayManager via ShizukuBinderWrapper
        // Returns displayId
    }

    fun releaseVirtualDisplay(displayId: Int) { ... }

    // ── Input ───────────────────────────────────────────────

    fun injectInputEvent(event: InputEvent, mode: Int): Boolean {
        // IInputManager via ShizukuBinderWrapper
    }

    // ── App Launch ──────────────────────────────────────────

    fun launchOnDisplay(context: Context, intent: Intent, displayId: Int) {
        val options = ActivityOptions.makeBasic()
        options.launchDisplayId = displayId
        context.startActivity(intent, options.toBundle())
    }
}
```

**Why a separate class?** Because Shizuku binder wrappers are boilerplate-heavy
and version-sensitive. Isolate the ugly. Test the rest without it.

### 4.2 `platform/shizuku/VirtualDisplayPlatform.kt`

The new `AndroidPlatform` implementation. This is where the real work happens.

```kotlin
package com.moonkey.androidagent.platform.shizuku

/**
 * VirtualDisplayPlatform — AndroidPlatform implementation that
 * runs the agent on a virtual display.
 *
 * Input:  IInputManager.injectInputEvent() via Shizuku
 * Output: ImageReader (we own the surface, we own the pixels)
 * A11y:   AccessibilityService filtered by displayId
 */
class VirtualDisplayPlatform(
    private val service: AccessibilityService,
    private val shizuku: ShizukuClient,
    private val config: VirtualDisplayConfig
) : AndroidPlatform {

    private var displayId: Int = Display.INVALID_DISPLAY
    private var imageReader: ImageReader? = null

    // ── Lifecycle ───────────────────────────────────────────

    fun start(): Result<Int> {
        val reader = ImageReader.newInstance(
            config.width, config.height,
            PixelFormat.RGBA_8888, 2
        )
        val id = shizuku.createVirtualDisplay(
            name = "agent_display",
            width = config.width,
            height = config.height,
            dpi = config.dpi,
            surface = reader.surface,
            flags = DISPLAY_FLAGS
        )
        this.displayId = id
        this.imageReader = reader
        return Result.success(id)
    }

    fun stop() {
        shizuku.releaseVirtualDisplay(displayId)
        imageReader?.close()
        displayId = Display.INVALID_DISPLAY
        imageReader = null
    }

    // ── AndroidPlatform: Screen Capture ─────────────────────

    override suspend fun captureScreen(): ScreenSnapshot {
        val elements = captureA11yTree()
        val image = captureScreenshot()
        return ScreenSnapshot(
            timestamp = System.currentTimeMillis(),
            elements = elements,
            image = image
        )
    }

    private fun captureA11yTree(): List<PerceptionElement> {
        // Get windows on OUR display only
        val windows = service.windows
            .filter { it.displayId == displayId }

        // Find the main app window (TYPE_APPLICATION)
        val appWindow = windows
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?: return emptyList()

        val root = appWindow.root ?: return emptyList()
        return Perceptor.snapshot(root, config.width, config.height).elements
    }

    private fun captureScreenshot(): ScreenImage? {
        // Read pixels from ImageReader — zero privilege needed
        val image = imageReader?.acquireLatestImage() ?: return null
        val bitmap = image.toBitmap()  // standard conversion
        image.close()
        return compressToScreenImage(bitmap)
    }

    // ── AndroidPlatform: Actions ────────────────────────────

    override suspend fun performAction(action: UIAction): ActionResult {
        return when (action) {
            // Node-based: delegate to a11y (works across displays)
            is UIAction.ClickNodeAt -> performNodeClick(action.x, action.y)
            is UIAction.SetTextOnNodeAt -> performSetText(action)
            is UIAction.SetTextOnFocused -> performSetTextFocused(action)
            is UIAction.LongClickNodeAt -> performNodeLongClick(action.x, action.y)

            // Coordinate-based: inject via Shizuku
            is UIAction.TapAt -> injectTap(action.x, action.y)
            is UIAction.LongPressAt -> injectLongPress(action.x, action.y, action.durationMs)
            is UIAction.Swipe -> injectSwipe(action)

            // System buttons: inject key events
            is UIAction.SystemButton -> injectSystemButton(action.button)

            is UIAction.Wait -> {
                delay(action.durationMs)
                ActionResult.Success()
            }
        }
    }

    // ── Input Injection ─────────────────────────────────────

    private fun injectTap(x: Int, y: Int): ActionResult {
        val now = SystemClock.uptimeMillis()
        val down = motionEvent(now, now, ACTION_DOWN, x.toFloat(), y.toFloat())
        val up = motionEvent(now, now + 50, ACTION_UP, x.toFloat(), y.toFloat())

        val ok = shizuku.injectInputEvent(down, INJECT_MODE) &&
                 shizuku.injectInputEvent(up, INJECT_MODE)

        down.recycle(); up.recycle()
        return if (ok) ActionResult.Success() else ActionResult.Failure("tap inject failed")
    }

    private fun injectSwipe(action: UIAction.Swipe): ActionResult {
        val downTime = SystemClock.uptimeMillis()
        val steps = 20
        val stepMs = action.durationMs / steps

        // DOWN
        inject(motionEvent(downTime, downTime, ACTION_DOWN,
            action.startX.toFloat(), action.startY.toFloat()))

        // MOVE
        for (i in 1..steps) {
            Thread.sleep(stepMs)
            val t = i.toFloat() / steps
            val x = action.startX + (action.endX - action.startX) * t
            val y = action.startY + (action.endY - action.startY) * t
            inject(motionEvent(downTime, SystemClock.uptimeMillis(), ACTION_MOVE, x, y))
        }

        // UP
        inject(motionEvent(downTime, SystemClock.uptimeMillis(), ACTION_UP,
            action.endX.toFloat(), action.endY.toFloat()))

        return ActionResult.Success()
    }

    private fun injectSystemButton(button: SystemButtonType): ActionResult {
        val keyCode = when (button) {
            SystemButtonType.BACK    -> KeyEvent.KEYCODE_BACK
            SystemButtonType.HOME    -> KeyEvent.KEYCODE_HOME
            SystemButtonType.RECENTS -> KeyEvent.KEYCODE_APP_SWITCH
            SystemButtonType.ENTER   -> KeyEvent.KEYCODE_ENTER
        }
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            .apply { setDisplayId(displayId) }
        val up = KeyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, 0)
            .apply { setDisplayId(displayId) }

        val ok = shizuku.injectInputEvent(down, INJECT_MODE) &&
                 shizuku.injectInputEvent(up, INJECT_MODE)
        return if (ok) ActionResult.Success() else ActionResult.Failure("key inject failed")
    }

    // Node-based actions work through AccessibilityNodeInfo    — same as
    // AccessibilityPlatform but finding nodes on our display's windows.
    private fun performNodeClick(x: Int, y: Int): ActionResult {
        val node = findNodeAt(x, y) ?: return ActionResult.Failure("no node at ($x,$y)")
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.recycle()
        return if (ok) ActionResult.Success() else ActionResult.Failure("click failed")
    }

    private fun findNodeAt(x: Int, y: Int): AccessibilityNodeInfo? {
        val windows = service.windows.filter { it.displayId == displayId }
        for (window in windows) {
            val root = window.root ?: continue
            val node = findNodeByCoords(root, x, y)
            if (node != null) return node
            root.recycle()
        }
        return null
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun motionEvent(
        downTime: Long, eventTime: Long,
        action: Int, x: Float, y: Float
    ): MotionEvent {
        return MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
            setDisplayId(displayId)
        }
    }

    private fun inject(event: InputEvent): Boolean {
        val ok = shizuku.injectInputEvent(event, INJECT_MODE)
        event.recycle()
        return ok
    }

    // ── Other AndroidPlatform methods ───────────────────────

    override fun getDisplayInfo() = DisplayInfo(config.width, config.height, config.density)
    override fun hasRequiredPermissions() = shizuku.isAvailable() && shizuku.hasPermission()
    override fun getCurrentPackageName(): String? { /* from a11y windows on our display */ }
    override suspend fun getInstalledApps() = AccessibilityPlatform.getInstalledApps()
    override suspend fun launchApp(packageName: String): ActionResult {
        shizuku.launchOnDisplay(context, launchIntent(packageName), displayId)
        return ActionResult.Success()
    }

    companion object {
        private const val INJECT_MODE = 2 // INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH

        /** Display flags for a usable agent display */
        private const val DISPLAY_FLAGS =
            VIRTUAL_DISPLAY_FLAG_PUBLIC or
            VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            0x40 or   // FLAG_SUPPORTS_TOUCH
            0x200     // FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
    }
}
```

### 4.3 `platform/shizuku/VirtualDisplayConfig.kt`

Data. Nothing else.

```kotlin
package com.moonkey.androidagent.platform.shizuku

data class VirtualDisplayConfig(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val density: Float
) {
    companion object {
        /** Match the physical display */
        fun fromPhysicalDisplay(context: Context): VirtualDisplayConfig {
            val dm = context.resources.displayMetrics
            return VirtualDisplayConfig(
                width = dm.widthPixels,
                height = dm.heightPixels,
                dpi = dm.densityDpi,
                density = dm.density
            )
        }
    }
}
```

---

## 5. Key Design Decisions (and why)

### 5.1 ImageReader for screen capture, not Shizuku

We own the virtual display. We provide its rendering surface. So we use
`ImageReader` as that surface and read pixels directly. Zero privilege needed.

The alternative — `IWindowManager.captureDisplay()` via Shizuku — is more
complex, requires reflection into hidden APIs, and varies between Android
versions. Why use a privileged API when the unprivileged one works better?

### 5.2 Node-based actions through AccessibilityService, coordinate-based through Shizuku

AccessibilityNodeInfo actions (ACTION_CLICK, ACTION_SET_TEXT) work across
displays because they're addressing specific nodes, not screen coordinates.
The system routes them correctly regardless of which display the node lives on.

Gesture/coordinate actions need `IInputManager.injectInputEvent()` because
`AccessibilityService.dispatchGesture()` only works on the default display.
This is actually more reliable — `injectInputEvent` is what `adb shell input`
uses under the hood.

### 5.3 A11y tree filtering by displayId

`AccessibilityService.getWindows()` returns windows across all displays.
`AccessibilityWindowInfo.getDisplayId()` (API 30+) lets us filter.
Our minimum target is Android 11 (API 30) for wireless debugging anyway.
No compatibility hacks needed.

### 5.4 No display content mirroring / preview (for now)

The virtual display renders to an `ImageReader`. If we want a preview later,
we just swap the surface:

```kotlin
// Switch to preview surface
virtualDisplay.surface = previewTextureView.surfaceTexture

// Switch back to headless capture
virtualDisplay.surface = imageReader.surface
```

Or use `MediaProjection` to create a second virtual display that mirrors.
But this is UI work for a future PR. The platform design doesn't need it.

### 5.5 Fallback to real screen

If Shizuku is not available, the app works exactly as today using
`AccessibilityPlatform`. This is not a migration — it's an addition.
The wiring in the service/activity layer decides which platform to use:

```kotlin
val platform: AndroidPlatform = if (shizukuClient.isAvailable()) {
    VirtualDisplayPlatform(service, shizukuClient, displayConfig).also { it.start() }
} else {
    AccessibilityPlatform(service)
}
val services = SessionServices.create(config, platform, ...)
```

### 5.6 No abstract DisplayStrategy / DisplayFactory / DisplayProviderInterface

The existing `AndroidPlatform` interface is the abstraction. That's enough.
We don't need an abstraction for the abstraction. Two concrete implementations
of one interface. If we need a third one someday, we'll add it then. Not now.

---

## 6. What Changes in Existing Code

Almost nothing. That's the whole point of having a good interface.

| File | Change | Reason |
|------|--------|--------|
| `build.gradle.kts` | Add Shizuku dependencies | New dependency |
| `AndroidManifest.xml` | Add Shizuku provider + permission | Required by Shizuku |
| `AccessibilityService` setup | Create platform based on Shizuku availability | Wiring |
| `ScreenImageSource` enum | Add `VIRTUAL_DISPLAY_CAPTURE` | New image source type |

**Files that do NOT change:**
`Agent.kt`, `AgentTurnRunner.kt`, `SessionServices.kt`, `Perceptor.kt`,
`UIAction.kt`, `ActionResult.kt`, `ToolRouter.kt`, all tool implementations,
all agent definitions — none of them know about virtual displays.

---

## 7. Dependencies

```kotlin
// build.gradle.kts (app)
dependencies {
    // Shizuku API — binder forwarding
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Hidden API bypass — needed for IDisplayManager, IInputManager stubs
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    // Hidden API stubs (compile-only) — IDE autocomplete for @hide methods
    compileOnly("dev.rikka.hidden:stub:4.3.3")
}

android {
    buildFeatures {
        aidl = true  // Required for Shizuku AIDL
    }
}
```

### Shizuku API Verification

| API | AOSP Source | Shell permission? | Verified? |
|-----|-----------|-------------------|-----------|
| `IDisplayManager.createVirtualDisplay` | `frameworks/base/core/java/android/hardware/display/IDisplayManager.aidl` | Yes (shell/system) | ✅ Used by scrcpy, Taskbar, LSPosed |
| `IInputManager.injectInputEvent` | `frameworks/base/core/java/android/hardware/input/IInputManager.aidl` | Yes (shell) | ✅ Used by scrcpy, adb shell input |
| `ActivityOptions.setLaunchDisplayId` | Public API since API 26 | Needs shell for non-default display | ✅ Used by Taskbar |
| `AccessibilityWindowInfo.getDisplayId` | Public API since API 30 | No | ✅ Standard SDK |
| `ImageReader` | Public API | No | ✅ Standard SDK |

---

## 8. Lifecycle

```
App Start
  └→ Check Shizuku: installed? running? permitted?
       ├→ NO:  Use AccessibilityPlatform (real screen, same as today)
       └→ YES: User starts task
                └→ Create VirtualDisplayPlatform
                    └→ VirtualDisplayPlatform.start()
                        ├→ Create ImageReader (capture surface)
                        ├→ Create virtual display via Shizuku
                        └→ Launch target app on virtual display
                            └→ Agent loop runs
                                ├→ captureScreen: a11y tree (filtered) + ImageReader
                                ├→ performAction: node-based or injected
                                └→ repeat until done
                    └→ VirtualDisplayPlatform.stop()
                        ├→ Release virtual display
                        └→ Close ImageReader
```

No background service for the virtual display. It lives for the duration
of the agent session. Create on task start, destroy on task end. Simple.

---

## 9. Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| `IDisplayManager` AIDL changes between Android versions | Display creation fails | Pin to stable method signatures; test on 11/12/13/14/15 |
| Virtual display a11y tree is empty | Agent can't see UI elements | Verify `FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS` enables a11y; fallback to screenshot-only mode |
| Input injection targets wrong display | Wrong app gets poked | Always set `displayId` on every event; assert displayId before injection |
| Shizuku dies mid-session | Agent loses privileged access | Detect via `Shizuku.addBinderDeadListener()`; stop agent with error |
| ImageReader frame timing | Stale screenshot | Call `acquireLatestImage()` (drops stale frames); add small delay after actions |
| App refuses to launch on virtual display | Some apps check display type | Use `FLAG_PUBLIC`; most apps don't check |

---

## 10. What This Design Does NOT Cover

These are separate features / future work:

- **Smart Capsule UI** — Dynamic island showing agent status (see `0.02_smart_capsule`)
- **Virtual display preview** — Live view of what the agent sees
- **Multi-display task migration** — Moving apps between real and virtual display
- **Shizuku setup wizard** — Guiding users through Shizuku installation

Each of these is its own design doc. This doc covers only the platform layer:
creating the display, running the agent on it, and tearing it down.

---

## 11. Implementation Order

1. **Add Shizuku deps + manifest** — get the build working
2. **`ShizukuClient.kt`** — verify binder calls work on a real device
3. **`VirtualDisplayPlatform.kt`** — implement `captureScreen()` first (a11y tree + ImageReader), then `performAction()` one action type at a time
4. **Wire up** — platform selection in the service layer
5. **Test** — run existing agent tasks on virtual display, verify they still work

Step 3 is the bulk of the work. But it's straightforward — it's the same logic
as `AccessibilityPlatform` with different I/O backends.

---

## 12. Test Strategy

**Unit tests** (no device):
- `VirtualDisplayConfig.fromPhysicalDisplay()` — given metrics, produces correct config
- Action mapping in `performAction()` — correct dispatch for each UIAction variant

**Integration tests** (device + Shizuku):
- `ShizukuClient.createVirtualDisplay()` → returns valid displayId
- `ShizukuClient.injectInputEvent()` → event delivered
- `VirtualDisplayPlatform.captureScreen()` → non-empty ScreenSnapshot
- `VirtualDisplayPlatform.performAction(TapAt)` → a11y tree reflects UI change
- Full agent run on virtual display with a simple task ("open Settings")

**Regression**:
- All existing tool tests pass unchanged (they use `AndroidPlatform` interface)
- Agent behavior identical on virtual vs real display for reference tasks

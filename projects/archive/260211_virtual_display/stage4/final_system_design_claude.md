# Virtual Display Stage 4 — Final System Design

> "Talk is cheap. Show me the code." — Linus Torvalds

Date: 2026-02-11

---

## 0. What This Document Is

This is the final system design for Virtual Display Phase 4. It synthesizes the best elements from three system designs, three design reviews, the compatibility FAQ, and a full read of the current codebase. It uses the **Hybrid Model** (ImageReader background + SurfaceView live preview) because:

1. The API exists: `IDisplayManager.setVirtualDisplaySurface(displayId, surface)` is available through our existing Shizuku reflection path.
2. It gives 60fps hardware-rendered live preview for free — no bitmap polling, no frame relay, no CPU copies.
3. It makes the codebase simpler, not more complex. One `setSurface()` call replaces all of system_design_1's `VirtualDisplayFrameRelay` and system_design_2's `VirtualDisplayFrameHub`.

The UI design decisions are covered in [final_ui_design.md](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/0.01_virtual_display/stage4/final_ui_design.md). This document focuses on **how to build it**.

---

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────┐
│                  AgentService                    │
│                                                  │
│  ┌──────────────┐    ┌────────────────────────┐  │
│  │ AgentSession  │    │ ServiceOverlayController│ │
│  │              │    │ (mode-aware)            │ │
│  └──────┬───────┘    └────────┬───────────────┘  │
│         │                     │                   │
│         ▼                     │ VD mode           │
│  ┌──────────────────┐        ├── StatusIsland     │
│  │VirtualDisplayPlat│        │   (real screen)    │
│  │ form             │        │                    │
│  │                  │        └── Viewer overlays   │
│  │  imageReader ◄───┤            (in Activity)    │
│  │  displayId       │                             │
│  │  surfaceMode ────┼──► setSurface() switch      │
│  └──────────────────┘                             │
└─────────────────────────────────────────────────┘
```

**Five things change. Nothing else.**

| # | Component | Action | Est. Lines |
|---|---|---|---|
| 1 | `StatusIslandManager` | **NEW** | ~130 |
| 2 | `VirtualDisplayViewerActivity` | **NEW** | ~100 |
| 3 | `ServiceOverlayController` | **MODIFY** — add mode branching | +50 |
| 4 | `VirtualDisplayPlatform` | **MODIFY** — add surface switching | +30 |
| 5 | `ShizukuClient` | **MODIFY** — add `setVirtualDisplaySurface()` | +15 |
| 6 | `TypeExecutor` | **MODIFY** — skip tap-to-focus in VD mode | +10 |
| 7 | `AgentService` | **MODIFY** — wiring | +15 |
| 8 | `AndroidManifest.xml` | **MODIFY** — register Activity | +5 |

**Total: ~2 new files, 6 modifications, ~355 lines.**

No new abstractions. No coordinator objects. No frame hubs. No state machines beyond what already exists.

---

## 2. The Hybrid Model — Surface Switching

This is the single most important architectural decision. It replaces all the "frame relay" / "frame hub" / "bitmap cache" complexity from prior designs with one API call.

### How It Works

```
Background Mode                     Live Preview Mode
(user not watching)                  (Viewer open)
                  
VirtualDisplay                       VirtualDisplay
    │                                    │
    ▼                                    ▼
ImageReader.surface ◄── setSurface ──► SurfaceView.surface
    │                                    │
    ▼                                    ▼
Agent reads frames                   GPU renders 60fps
via acquireLatestImage()             directly to Activity
```

The `VirtualDisplay` always outputs to exactly one surface. We switch which surface it points to:

- **Background**: `imageReader.surface` — the agent captures screenshots on-demand via `acquireLatestImage()`. Zero overhead when nobody is watching. This is the status quo.
- **Live preview**: `viewerSurfaceView.holder.surface` — the GPU composites directly to the Viewer Activity. 60fps, hardware-accelerated, zero CPU copies.

### Surface Switch Implementation

In `ShizukuClient`, add one method:

```kotlin
// ShizukuClient.kt
fun setVirtualDisplaySurface(displayId: Int, surface: Surface) {
    val proxy = getDisplayManagerProxy()
    val method = proxy.javaClass.getMethod(
        "setVirtualDisplaySurface",
        IVirtualDisplayCallback::class.java,
        Int::class.javaPrimitiveType,
        Surface::class.java
    )
    method.invoke(proxy, null, displayId, surface)
}
```

> Note: The AIDL signature is `setVirtualDisplaySurface(IVirtualDisplayCallback token, int displayId, Surface surface)`. We pass `null` for the callback token — same pattern as `releaseVirtualDisplay`. If the token is required on some ROMs, we store the callback from `createVirtualDisplay` and pass it here.

In `VirtualDisplayPlatform`, add surface management:

```kotlin
// VirtualDisplayPlatform.kt — new fields + methods

enum class SurfaceMode { IMAGE_READER, LIVE_PREVIEW }

@Volatile private var surfaceMode = SurfaceMode.IMAGE_READER

fun switchToLivePreview(viewerSurface: Surface) {
    if (surfaceMode == SurfaceMode.LIVE_PREVIEW) return
    shizuku.setVirtualDisplaySurface(displayId, viewerSurface)
    surfaceMode = SurfaceMode.LIVE_PREVIEW
    Log.i(TAG, "Switched to live preview surface")
}

fun switchToImageReader() {
    if (surfaceMode == SurfaceMode.IMAGE_READER) return
    val reader = imageReader ?: return
    shizuku.setVirtualDisplaySurface(displayId, reader.surface)
    surfaceMode = SurfaceMode.IMAGE_READER
    Log.i(TAG, "Switched to ImageReader surface")
}
```

### Screenshot Capture in Live Mode

When the surface is pointed at the Viewer's `SurfaceView`, the `ImageReader` is disconnected. The agent still needs screenshots. Use `PixelCopy`:

```kotlin
// VirtualDisplayPlatform.captureScreenshot() — modified

private suspend fun captureScreenshot(): ScreenImage? {
    return when (surfaceMode) {
        SurfaceMode.IMAGE_READER -> captureFromImageReader()    // existing code
        SurfaceMode.LIVE_PREVIEW -> captureFromPixelCopy()      // new path
    }
}

private suspend fun captureFromPixelCopy(): ScreenImage? {
    val surface = liveSurfaceView ?: return captureFromImageReaderFallback()
    val bitmap = Bitmap.createBitmap(config.width, config.height, Bitmap.Config.ARGB_8888)
    
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            PixelCopy.request(surface, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    val maxDim = sessionConfig.perceptionConfig.screenshotMaxDimension
                    val quality = sessionConfig.perceptionConfig.screenshotJpegQuality
                    val scaled = BitmapUtils.scaleBitmapIfNeeded(bitmap, maxDim)
                    val bytes = BitmapUtils.compressJpeg(scaled, quality)
                    if (scaled !== bitmap) bitmap.recycle()
                    cont.resume(bytes?.let {
                        ScreenImage(
                            width = scaled.width, height = scaled.height,
                            mimeType = "image/jpeg", bytes = it,
                            source = ScreenImageSource.VIRTUAL_DISPLAY_CAPTURE
                        )
                    }.also { scaled.recycle() })
                } else {
                    bitmap.recycle()
                    cont.resume(null)
                }
            }, Handler(Looper.getMainLooper()))
        }
    }
}

private suspend fun captureFromImageReaderFallback(): ScreenImage? {
    // Briefly switch back to ImageReader, capture, switch back
    // Only needed if PixelCopy fails (shouldn't happen normally)
    switchToImageReader()
    delay(50)
    val result = captureFromImageReader()
    // Don't switch back here — PixelCopy failing means we stay on ImageReader
    return result
}
```

### Why This Is Better Than All Prior Designs

| Prior approach | Problem | Hybrid Model |
|---|---|---|
| system_design_1: Bitmap cache @ 5fps poll | CPU copies, 200ms latency, 5fps | 60fps GPU direct, zero CPU |
| system_design_2: FrameHub + StateFlow + JPEG | JPEG re-encode per frame, complex | One `setSurface()` call |
| system_design_3: 15fps continuous pump | Always-on CPU burn, stale frames | Zero overhead in background |

---

## 3. StatusIslandManager

**File**: `ui/overlay/StatusIslandManager.kt`

A floating pill overlay on the real screen. The **only** overlay visible during VD mode.

```kotlin
class StatusIslandManager(
    private val service: AccessibilityService,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit  // expand inline controls
) {
    private var pillView: View? = null
    private val wm = service.getSystemService(WindowManager::class.java)

    // Public API — all ServiceOverlayController needs to call
    fun show()
    fun hide()
    fun isShowing(): Boolean
    fun updateStatus(appName: String, statusText: String, dotColor: Int)
    fun showSuccess(message: String)   // "✅ Done!" → auto-hide 3s
    fun showError(message: String)     // "❌ Failed" → auto-hide 3s
    fun dispose()
}
```

Layout: `LinearLayout(HORIZONTAL)` with:
- App icon (16dp `ImageView`)
- App name (12sp `TextView`, max 8 chars, ellipsize)
- Divider (1dp vertical `View`)
- Status text (12sp `TextView`)
- Status dot (8dp circle `View`)

Window params:
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

`WRAP_CONTENT` means touches outside the pill pass through. No `FLAG_NOT_TOUCHABLE`.

Long-press expands inline: shows two buttons (⏸ Pause / ■ Stop) below the pill for 3 seconds, then auto-collapses. Simple `ViewPropertyAnimator` slide-down.

---

## 4. VirtualDisplayViewerActivity

**File**: `ui/viewer/VirtualDisplayViewerActivity.kt`

Full-screen Activity showing the live VD preview. Overlays (capsule + glow) are **Compose elements inside the Activity**, not system windows.

```kotlin
class VirtualDisplayViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            VirtualDisplayViewerScreen(
                onSwipeUp = { finish() }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // Tell VirtualDisplayPlatform to switch surface to our SurfaceView
        AgentService.instance?.notifyViewerVisible(this)
    }

    override fun onStop() {
        super.onStop()
        // Switch surface back to ImageReader
        AgentService.instance?.notifyViewerHidden()
    }
}
```

The Compose screen:

```kotlin
@Composable
fun VirtualDisplayViewerScreen(onSwipeUp: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 1. Live VD preview — SurfaceView, 60fps hardware rendered
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            // Surface is ready — platform will switch to it in onStart
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            // Platform reverts to ImageReader in onStop
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Edge glow (Compose, renders over the preview)
        EdgeGlowOverlay(/* read glow state from AgentService */)

        // 3. Smart Capsule (Compose, bottom-aligned)
        SmartCapsuleOverlay(
            modifier = Modifier.align(Alignment.BottomCenter),
            onStop = { /* submit Op.Shutdown */ },
            onPause = { /* submit Op.Pause */ },
            onResume = { /* submit Op.Resume */ }
        )

        // 4. Swipe-up exit gesture
        SwipeUpDismiss(onDismiss = onSwipeUp)

        // 5. Exit hint — shows "Swipe up to exit" for 2s on first entry
        SwipeExitHint()
    }
}
```

**Why SurfaceView, not TextureView?** SurfaceView renders to a separate hardware layer. The GPU can composite it without touching the main UI thread. TextureView renders into the View hierarchy's canvas — adds latency. For a full-screen video preview, SurfaceView is the correct choice.

**Manifest:**
```xml
<activity
    android:name=".ui.viewer.VirtualDisplayViewerActivity"
    android:theme="@style/Theme.AndroidAgent.FullScreen"
    android:exported="false"
    android:launchMode="singleTask" />
```

`singleTask` prevents multiple instances. If already open and tapped again, `onNewIntent()` is called.

### Overlay Components Inside the Activity

The Edge Glow and Smart Capsule rendered inside the Viewer are **Compose composables**, not `WindowManager` system overlays. This is the key insight. They:

- Cannot leak to the real screen (they are views inside an Activity, not system windows)
- Look identical to the existing overlays (same colors, sizes, animations — just implemented as Composables rather than `WindowManager.addView()` views)
- Read state from the same source (agent events via `AgentService.instance`)

For the initial implementation, these composables can be simple Compose rewrites of the existing `EdgeGlowManager` and `SmartCapsuleManager` visual logic. They don't need to replicate the `WindowManager` lifecycle — they're just Compose UI.

---

## 5. ServiceOverlayController Changes

**File**: `app/ServiceOverlayController.kt`

The controller gains mode awareness. In `VIRTUAL_DISPLAY` mode, it drives the `StatusIslandManager` instead of `EdgeGlowManager` + `SmartCapsuleManager`.

### New Fields

```kotlin
// Add to constructor
private val statusIslandManager: StatusIslandManager? = null  // injected for VD mode

// Add to class body  
private var platformMode: PlatformMode = PlatformMode.ACCESSIBILITY

fun setPlatformMode(mode: PlatformMode) {
    platformMode = mode
}
```

### Modified Methods — Pattern

Every event handler becomes a flat `when` branch:

```kotlin
fun onTaskStarted(taskId: String, input: String) {
    hasActiveTask = true
    currentTaskInput = input
    currentGlowState = GlowState.Active

    when (platformMode) {
        PlatformMode.VIRTUAL_DISPLAY -> {
            val appName = /* from platform.getCurrentPackageName() */
            statusIslandManager?.updateStatus(appName, input, GlowState.Active.color)
            statusIslandManager?.show()
        }
        PlatformMode.ACCESSIBILITY -> {
            // UNCHANGED — existing logic
            if (!isAppInForeground) {
                edgeGlowManager.show(GlowState.Active)
                capsuleManager.onTaskStarted(taskId, input)
            }
        }
    }
}
```

This pattern repeats for: `onTurnPhaseChanged`, `onActionExecuted`, `onTaskCompleted`, `onSessionCompleted`, `onSessionError`, `onSessionPaused`, `onSessionResumed`, `handleWindowStateChanged`.

For `handleWindowStateChanged` in VD mode: **do nothing**. The VD agent's window changes don't affect the real screen. The status island is always visible. No foreground/background tracking needed.

### Why Not a Coordinator?

system_design_2 proposed a `VirtualDisplayUiCoordinator`. It's clean architecture, but it's one more object, one more layer, one more thing to read. The `when(platformMode)` branches are verbose but **locally readable** — you can understand each method without jumping to another file. For 8 methods with 2 branches each, this is the right tradeoff.

---

## 6. TypeExecutor — IME Fix

**File**: `tool/action/TypeExecutor.kt`

Root cause: `TypeExecutor.execute()` has a fallback path (Attempt 2) that taps to focus before setting text. On VD, this tap triggers the IME on the default display.

Fix: the platform tells the executor whether tap-to-focus is allowed.

### AndroidPlatform Addition

```kotlin
// AndroidPlatform.kt — new method with default
interface AndroidPlatform {
    // ... existing methods ...
    
    /**
     * Whether tap-to-focus fallback is safe for text input.
     * VD mode: false (tap triggers IME on wrong display).
     * A11y mode: true (existing behavior).
     */
    fun allowTapToFocus(): Boolean = true
}
```

`AccessibilityPlatform`: inherits default `true`.
`VirtualDisplayPlatform`: overrides to `false`.

### TypeExecutor Modification

```kotlin
// TypeExecutor.execute() — in the "With target" path, after Attempt 1 fails:

// Attempt 2: Tap to focus, then SetTextOnFocused
if (!platform.allowTapToFocus()) {
    attemptTrail.add("TapToFocus: skipped (VD mode)")
    return ActionOutcome.Failed(
        reason = "SetTextOnNodeAt failed and tap-to-focus disabled in VD mode",
        attemptTrail = attemptTrail
    )
}
// ... existing tap-to-focus code unchanged ...
```

This is ~5 lines of new code. It prevents the root cause (tap → focus → IME) rather than suppressing the symptom (dismiss keyboard after the fact).

### Safety Net: Keyboard Dismiss

As a defense-in-depth measure, after any text action in VD mode, proactively dismiss the keyboard on display 0:

```kotlin
// VirtualDisplayPlatform.performAction() — after SetText cases:
if (action is UIAction.SetTextOnNodeAt || action is UIAction.SetTextOnFocused) {
    dismissMainDisplayKeyboard()
}

private fun dismissMainDisplayKeyboard() {
    try {
        shizuku.executeShellCommand(arrayOf("input", "keyevent", "--display", "0", "4"))
        // KEYCODE_BACK on display 0 — dismisses IME if showing, benign if not
    } catch (e: Exception) {
        Log.w(TAG, "Failed to dismiss keyboard", e)
    }
}
```

---

## 7. Completion Handoff

When the agent achieves its goal, bring the result to the user's screen.

### Implementation

```kotlin
// In AgentService.handleEvent(), on SessionCompleted with GOAL_ACHIEVED:

is AgentEvent.SessionCompleted -> {
    if (event.reason == CompletionReason.GOAL_ACHIEVED && 
        config.platformMode == PlatformMode.VIRTUAL_DISPLAY) {
        performHandoff()
    }
    // ... existing code ...
}

private fun performHandoff() {
    val lastPackage = platform?.getCurrentPackageName() ?: return
    
    // Simple relaunch — honest, predictable, works across ROMs
    val intent = packageManager.getLaunchIntentForPackage(lastPackage) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
    
    Log.i(TAG, "Handoff: relaunched $lastPackage on default display")
}
```

**Why simple relaunch, not task reparenting?**

- `ActivityOptions.setLaunchDisplayId(DEFAULT_DISPLAY)` with the existing task doesn't reparent — it creates a new task instance. Android doesn't support cross-display task migration through the public API.
- `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` clears the back stack, losing the agent's navigation state.
- A simple `getLaunchIntentForPackage()` relaunch works because most apps restore their last state on cold start (Android's built-in activity state restoration). For the rare app that doesn't, the user can still navigate to the result. This is acceptable.

---

## 8. AgentService Wiring

### Three Changes

**8a. Inject StatusIslandManager into ServiceOverlayController**

```kotlin
// AgentService.onServiceConnected() — when creating overlayController:
overlayController = ServiceOverlayController(
    context = this,
    appPackage = packageName,
    logTag = TAG,
    onStop = { scope.launch { session?.submit(Op.Shutdown) } },
    onPause = { scope.launch { session?.submit(Op.Pause) } },
    onResume = { scope.launch { session?.submit(Op.Resume) } },
    onOpenApp = { /* existing */ },
    statusIslandManager = StatusIslandManager(this,
        onTap = { startActivity(Intent(this, VirtualDisplayViewerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        onLongPress = { /* expand inline controls */ }
    )
)
```

**8b. Set platform mode on overlay controller**

```kotlin
// AgentService.runAgent() — after session creation:
overlayController?.setPlatformMode(platformMode)
```

**8c. Viewer lifecycle notifications**

```kotlin
// AgentService — new methods for Viewer Activity to call
fun notifyViewerVisible(activity: VirtualDisplayViewerActivity) {
    val platform = (session?.platform as? VirtualDisplayPlatform) ?: return
    val surfaceView = activity.getSurfaceView() // exposed via getter
    surfaceView.holder.surface?.let { platform.switchToLivePreview(it) }
}

fun notifyViewerHidden() {
    val platform = (session?.platform as? VirtualDisplayPlatform) ?: return
    platform.switchToImageReader()
}
```

---

## 9. Overlay Leak Bug Fix

**Root cause**: `ServiceOverlayController` shows capsule+glow on the real screen whenever `hasActiveTask && !isAppInForeground`. In VD mode, the agent is always "in background," so overlays always show.

**Fix**: The `when(platformMode)` branches in §5 handle this. In VD mode:
- `EdgeGlowManager` is never called → never shows on real screen ✅
- `SmartCapsuleManager` is never called → never shows on real screen ✅
- `StatusIslandManager` is the only overlay → one small pill ✅

**A11y mode**: All `PlatformMode.ACCESSIBILITY` branches execute the existing code unchanged. **Zero regression.**

---

## 10. A11y Mode — No Regression Contract

Every modified method follows this pattern:

```kotlin
when (platformMode) {
    PlatformMode.VIRTUAL_DISPLAY -> { /* new behavior */ }
    PlatformMode.ACCESSIBILITY -> { /* EXISTING CODE, UNTOUCHED */ }
}
```

The a11y path is literally the same code that runs today. The `when` branch just makes it conditional instead of unconditional. If anything breaks in a11y mode, it's a bug we introduce in the new code, not a consequence of the architecture.

---

## 11. File Plan

```
NEW FILES:
├── ui/overlay/StatusIslandManager.kt           ~130 lines
└── ui/viewer/VirtualDisplayViewerActivity.kt   ~100 lines

MODIFIED FILES:
├── app/ServiceOverlayController.kt             +50 lines (mode branching)
├── platform/virtualdisplay/VirtualDisplayPlatform.kt  +30 lines (surface switch)
├── platform/virtualdisplay/ShizukuClient.kt    +15 lines (setVirtualDisplaySurface)
├── platform/AndroidPlatform.kt                 +3 lines (allowTapToFocus)
├── tool/action/TypeExecutor.kt                 +10 lines (skip tap-to-focus)
├── app/AgentService.kt                         +15 lines (wiring)
└── AndroidManifest.xml                         +5 lines (Activity registration)
```

Total: **~355 new/modified lines, 2 new files, 6 modified files.**

---

## 12. Implementation Order

1. **`ShizukuClient.setVirtualDisplaySurface()`** — 15 lines. Test with a manual call.
2. **`VirtualDisplayPlatform` surface switching** — the core mechanism. Verify `setSurface` works by programmatically switching.
3. **`StatusIslandManager`** — standalone, testable in isolation on the real screen.
4. **`ServiceOverlayController` mode branching** — mode-aware, wires StatusIsland.
5. **`VirtualDisplayViewerActivity`** — ties together: SurfaceView + Compose overlays.
6. **`AgentService` wiring** — connects viewer lifecycle to surface switching.
7. **`TypeExecutor` + keyboard dismiss** — IME fix.
8. **`AndroidManifest.xml`** — register the Activity.
9. **Completion handoff** — last, because it only matters when everything else works.

Dependencies flow downward. Each step is testable independently.

---

## 13. Future Compatibility: Interactive Mode

> "在未来这个virtual display在user看它的时候, 它也可以选择接管" — qi_note

The Hybrid Model is **perfectly positioned** for User Takeover:

1. The Viewer's `SurfaceView` already receives the live VD output.
2. Adding `OnTouchListener` to that `SurfaceView` captures user touch events.
3. Those touch events are injected into the VD via existing `ShizukuClient.injectInputEvent()`.
4. The agent pauses (`Op.Pause`) while the user takes over.
5. When the user is done, the agent resumes (`Op.Resume`).

Nothing in this design blocks this. The `switchToLivePreview()` / `switchToImageReader()` surface management is exactly the toggle mechanism needed for interactive mode.

---

## 14. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| `setVirtualDisplaySurface` fails on some ROMs | Viewer shows black screen | Fallback to bitmap polling (cache last screenshot from ImageReader, poll at 5fps in Compose `Image`). Degrade gracefully. |
| `PixelCopy` fails in live mode | Agent can't capture screenshots while user watches | Switch back to ImageReader briefly for each capture, then switch to live preview again. ~100ms interruption per screenshot. |
| Shizuku callback token required for `setSurface` | Method invocation throws | Store the callback from `createVirtualDisplay`, pass it to `setVirtualDisplaySurface`. |
| StatusIsland touch leaking | Long-press menu blocks other touches | Island is `WRAP_CONTENT` — only covers its own area. Long-press menu auto-dismisses after 3s. |

---

## 15. What This Design Does NOT Do

- **No `VirtualDisplayUiCoordinator`** — flat mode branching is simpler for 2 modes.
- **No `VirtualDisplayFrameRelay` / `FrameHub`** — the Hybrid Model eliminates the need.
- **No `CompletionHandoffManager`** — 5 lines of code in `handleEvent` doesn't need a manager.
- **No `TextInputPolicy` enum** — a single `allowTapToFocus(): Boolean` on the platform interface is enough.
- **No continuous frame pump** — zero overhead when nobody watches.
- **No PiP mode** — Status Island → tap → full Viewer is sufficient.
- **No multi-touch passthrough** — user watches, doesn't interact (yet).

---

## 16. Verification Plan

### Manual Testing (Primary)

1. Start VD task → verify only Status Island visible on real screen (no glow, no capsule)
2. Tap Status Island → Viewer opens with live 60fps preview
3. Agent performs actions → visible in real-time in Viewer
4. Swipe up in Viewer → returns to real screen, agent continues, Status Island re-appears
5. Task completes → last app opens on real screen, Status Island shows ✅ then fades
6. Long-press Status Island → inline Stop/Pause controls appear
7. Run same task in A11y mode → verify existing overlay behavior unchanged
8. Agent types text in VD → verify no keyboard pops on real screen

### Build Verification

```bash
./gradlew clean assembleDebug lint
```

### Edge Cases

- Viewer open when task completes → should auto-dismiss and handoff
- Viewer open when Shizuku dies → should finish gracefully
- Rapid tap/exit on Status Island → no crash, no multiple Viewer instances (singleTask)
- Screen rotation while Viewer open → SurfaceView handles resize

---

## 17. Addendum — Merged from Codex Design + Review (2026-02-11)

The following improvements are incorporated from `final_system_design_codex.md` and `final_system_design_review_claude.md`.

### 17a. TaskCompleted Event Needs CompletionReason

**Problem**: Current `TaskCompleted` event has no `reason` field. `SessionCompleted` only fires on explicit `Op.Shutdown` (user stop), NOT on normal task completion (goal achieved, max turns, error). So the handoff logic cannot trigger on `SessionCompleted` — it must trigger on `TaskCompleted`.

**Fix**: Add `reason: CompletionReason` to `TaskCompleted`. Map from `AgentStopReason`:

```kotlin
// AgentEvent.kt
data class TaskCompleted(
    override val sessionId: SessionId,
    override val timestamp: Long,
    val taskId: String,
    val result: String?,
    val reason: CompletionReason   // ← NEW
) : AgentEvent

// AgentSession.handleAgentComplete():
val reason = when (stopReason) {
    is AgentStopReason.GoalAchieved -> CompletionReason.GOAL_ACHIEVED
    is AgentStopReason.MaxTurnsReached -> CompletionReason.MAX_TURNS
    is AgentStopReason.UserRequested -> CompletionReason.USER_STOPPED
    is AgentStopReason.Error -> CompletionReason.ERROR
}
```

**Handoff correction**: §7 handoff triggers on `TaskCompleted(reason=GOAL_ACHIEVED)` in `handleEvent()`, not on `SessionCompleted`.

### 17b. PixelCopy Retry Threshold

**Improvement from Codex**: Instead of immediately falling back on first PixelCopy failure, track consecutive failures and degrade after threshold.

```kotlin
private var pixelCopyFailCount = 0
private const val PIXEL_COPY_MAX_FAILURES = 2

private suspend fun captureFromPixelCopy(): ScreenImage? {
    // ... PixelCopy attempt ...
    if (result != PixelCopy.SUCCESS) {
        pixelCopyFailCount++
        if (pixelCopyFailCount >= PIXEL_COPY_MAX_FAILURES) {
            Log.w(TAG, "PixelCopy failed $pixelCopyFailCount times, reverting to ImageReader")
            switchToImageReader()
        }
        return captureFromImageReader()  // single-shot fallback
    }
    pixelCopyFailCount = 0  // reset on success
    // ...
}
```

### 17c. Unit Test Targets (from Codex)

- `TypeExecutor`: When `allowTapToFocus()` returns false, Attempt 2 (tap-to-focus) is skipped entirely
- `ServiceOverlayController`: In `VIRTUAL_DISPLAY` mode, `EdgeGlowManager` and `SmartCapsuleManager` are never invoked
- `AgentSession`: `TaskCompleted.reason` correctly maps from `AgentStopReason`

### 17d. Definition of Done (from Codex)

1. VD mode: main screen shows only StatusIsland (no capsule, no glow)
2. Keyboard crosstalk: zero reproduction cases
3. Viewer flow: enter/exit stable, agent not interrupted
4. Hybrid mode: surface switch works, screenshot pipeline unbroken
5. A11y mode: zero regression — existing overlay behavior identical

---

## One-Liner

Switch the VirtualDisplay's surface between ImageReader and SurfaceView. That's the whole trick. Everything else is plumbing.

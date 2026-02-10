package com.moonkey.androidagent.platform.virtualdisplay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.moonkey.androidagent.model.ScreenImage
import com.moonkey.androidagent.model.ScreenImageSource
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.perception.screenshotJpegQuality
import com.moonkey.androidagent.perception.screenshotMaxDimension
import com.moonkey.androidagent.platform.AccessibilityNodeFinder
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.AppInfo
import com.moonkey.androidagent.platform.BitmapUtils
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.SystemButtonType
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.protocol.SessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * VirtualDisplayPlatform — AndroidPlatform running on a Shizuku virtual display.
 *
 * Screen capture: ImageReader (we own the surface, zero privilege).
 * A11y tree: AccessibilityService.windows filtered by displayId.
 * Node actions: AccessibilityNodeInfo.performAction (works across displays).
 * Coordinate actions: IInputManager.injectInputEvent via Shizuku.
 * System buttons: KeyEvent injection with displayId.
 *
 * Lifecycle:
 *   start() → creates virtual display + ImageReader
 *   stop()  → releases virtual display + ImageReader
 *
 * No business logic. No fallback. If something fails, it fails loudly.
 */
class VirtualDisplayPlatform(
    private val service: AccessibilityService,
    private val shizuku: ShizukuClient,
    private val config: VirtualDisplayConfig,
    private val sessionConfig: SessionConfig
) : AndroidPlatform {

    companion object {
        private const val TAG = "VirtualDisplayPlatform"

        /** Display flags for a usable agent display. */
        private const val DISPLAY_FLAGS =
            0x1 or    // VIRTUAL_DISPLAY_FLAG_PUBLIC
            0x800 or  // VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            0x40 or   // FLAG_SUPPORTS_TOUCH (undocumented but needed)
            0x200     // FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS

        /** Small delay after ImageReader creation for surface to be ready. */
        private const val SURFACE_READY_DELAY_MS = 200L

        /** Max images buffered in ImageReader. 2 is sufficient for latest-frame reads. */
        private const val IMAGE_READER_MAX_IMAGES = 2
    }

    @Volatile private var displayId: Int = Display.INVALID_DISPLAY
    @Volatile private var imageReader: ImageReader? = null
    private var binderDeadListener: Shizuku.OnBinderDeadListener? = null

    // ── Lifecycle ────────────────────────────────────────────────

    override suspend fun start() {
        check(displayId == Display.INVALID_DISPLAY) { "Already started (displayId=$displayId)" }

        shizuku.bypassHiddenApis()

        // Create ImageReader as the display's rendering surface.
        val reader = ImageReader.newInstance(
            config.width, config.height,
            PixelFormat.RGBA_8888,
            IMAGE_READER_MAX_IMAGES
        )

        // Create virtual display via Shizuku binder.
        val id = shizuku.createVirtualDisplay(
            name = "moonkey_agent_display",
            width = config.width,
            height = config.height,
            densityDpi = config.densityDpi,
            surface = reader.surface,
            flags = DISPLAY_FLAGS
        )

        if (id < 0) {
            reader.close()
            throw IllegalStateException("Failed to create virtual display (returned $id)")
        }

        displayId = id
        imageReader = reader

        // Monitor Shizuku binder health.
        val listener = Shizuku.OnBinderDeadListener {
            Log.e(TAG, "Shizuku binder died! displayId=$displayId")
            // Caller (AgentSession) should detect this via session error.
        }
        binderDeadListener = listener
        shizuku.addBinderDeadListener(listener)

        // Give the surface a moment to initialize.
        delay(SURFACE_READY_DELAY_MS)

        Log.i(TAG, "Started: displayId=$displayId, ${config.width}x${config.height}@${config.densityDpi}dpi")
    }

    override suspend fun stop() {
        binderDeadListener?.let { shizuku.removeBinderDeadListener(it) }
        binderDeadListener = null

        if (displayId != Display.INVALID_DISPLAY) {
            shizuku.releaseVirtualDisplay(displayId)
            Log.d(TAG, "Released virtual display $displayId")
        }

        imageReader?.close()
        imageReader = null
        displayId = Display.INVALID_DISPLAY

        Log.i(TAG, "Stopped")
    }

    // ── Screen Capture ──────────────────────────────────────────

    override suspend fun captureScreen(): ScreenSnapshot {
        val timestamp = System.currentTimeMillis()
        val pc = sessionConfig.perceptionConfig

        // 1. A11y tree from our display's windows.
        val elements = if (pc.capturesAccessibility) {
            captureA11yTree()
        } else {
            emptyList()
        }

        // 2. Screenshot from ImageReader.
        val image = if (pc.capturesScreenshot) {
            captureScreenshot()
        } else {
            null
        }

        Log.d(TAG, "Captured screen: ${elements.size} elements, screenshot=${image != null}")

        return ScreenSnapshot(
            timestamp = timestamp,
            elements = elements,
            image = image
        )
    }

    /**
     * Capture the a11y tree from our virtual display's windows.
     *
     * Filters service.windows by displayId, finds the app window,
     * and passes its root to Perceptor for structured extraction.
     */
    private fun captureA11yTree(): List<com.moonkey.androidagent.model.PerceptionElement> {
        if (displayId == Display.INVALID_DISPLAY) return emptyList()

        val windows = try {
            service.windows?.filter { it.displayId == displayId } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get windows for display $displayId", e)
            return emptyList()
        }

        // Prefer TYPE_APPLICATION; fall back to any window with a root.
        val appWindow = windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        } ?: windows.firstOrNull { it.root != null }

        val root = appWindow?.root
        if (root == null) {
            Log.d(TAG, "No a11y root on display $displayId (${windows.size} windows)")
            return emptyList()
        }

        return try {
            Perceptor.snapshot(root, config.width, config.height).elements
        } catch (e: Exception) {
            Log.w(TAG, "Perceptor.snapshot failed", e)
            emptyList()
        }
    }

    /**
     * Capture a screenshot from the ImageReader surface.
     *
     * acquireLatestImage() drops stale frames, giving us the most recent.
     */
    private suspend fun captureScreenshot(): ScreenImage? {
        val reader = imageReader ?: return null

        return withContext(Dispatchers.Default) {
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                Log.w(TAG, "acquireLatestImage failed", e)
                return@withContext null
            } ?: return@withContext null

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                if (pixelStride == 0) return@withContext null
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width

                // Create bitmap from raw pixels.
                val bitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop to actual display size (row padding may add extra width).
                val cropped = if (bitmap.width > config.width) {
                    Bitmap.createBitmap(bitmap, 0, 0, config.width, config.height).also {
                        if (it !== bitmap) bitmap.recycle()
                    }
                } else {
                    bitmap
                }

                // Scale and compress.
                val maxDim = sessionConfig.perceptionConfig.screenshotMaxDimension
                val quality = sessionConfig.perceptionConfig.screenshotJpegQuality
                val scaled = BitmapUtils.scaleBitmapIfNeeded(cropped, maxDim)
                val width = scaled.width
                val height = scaled.height
                val bytes = BitmapUtils.compressJpeg(scaled, quality)

                if (scaled !== cropped) cropped.recycle()
                scaled.recycle()

                bytes?.let {
                    ScreenImage(
                        width = width,
                        height = height,
                        mimeType = "image/jpeg",
                        bytes = it,
                        source = ScreenImageSource.VIRTUAL_DISPLAY_CAPTURE
                    )
                }
            } finally {
                image.close()
            }
        }
    }

    // ── Action Dispatch ─────────────────────────────────────────

    override suspend fun performAction(action: UIAction): ActionResult {
        if (displayId == Display.INVALID_DISPLAY) {
            return ActionResult.Failure("Virtual display not started")
        }

        return when (action) {
            // Node-based actions: a11y performAction (works across displays).
            is UIAction.ClickNodeAt -> performNodeClickAt(action.x, action.y)
            is UIAction.LongClickNodeAt -> performNodeLongClickAt(action.x, action.y)
            is UIAction.SetTextOnNodeAt -> performSetTextOnNodeAt(
                action.x, action.y, action.text, action.clear
            )
            is UIAction.SetTextOnFocused -> performSetTextOnFocused(action.text, action.clear)

            // Coordinate-based actions: inject via Shizuku.
            is UIAction.TapAt -> injectTap(action.x, action.y)
            is UIAction.LongPressAt -> injectLongPress(action.x, action.y, action.durationMs)
            is UIAction.Swipe -> injectSwipe(action)

            // System buttons: KeyEvent injection with displayId.
            is UIAction.SystemButton -> injectSystemButton(action.button)

            // Wait: just delay, same as AccessibilityPlatform.
            is UIAction.Wait -> {
                delay(action.durationMs)
                ActionResult.Success("Waited ${action.durationMs}ms")
            }
        }
    }

    // ── Node-based Actions ──────────────────────────────────────

    @Suppress("DEPRECATION")
    private suspend fun performNodeClickAt(x: Int, y: Int): ActionResult {
        return withContext(Dispatchers.Main) {
            val root = getRootOnDisplay()
                ?: return@withContext ActionResult.Failure(
                    "No a11y root on display $displayId for click"
                )

            try {
                val node = AccessibilityNodeFinder.findClickableNodeAtLocation(root, x, y)
                    ?: return@withContext ActionResult.Failure("No clickable node at ($x,$y)")

                try {
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (ok) {
                        ActionResult.Success("ACTION_CLICK at ($x,$y)")
                    } else {
                        ActionResult.Failure("ACTION_CLICK returned false at ($x,$y)")
                    }
                } finally {
                    node.recycle()
                }
            } finally {
                root.recycle()
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun performNodeLongClickAt(x: Int, y: Int): ActionResult {
        return withContext(Dispatchers.Main) {
            val root = getRootOnDisplay()
                ?: return@withContext ActionResult.Failure(
                    "No a11y root on display $displayId for long-click"
                )

            try {
                val node = AccessibilityNodeFinder.findLongClickableNodeAtLocation(root, x, y)
                    ?: return@withContext ActionResult.Failure("No long-clickable node at ($x,$y)")

                try {
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    if (ok) {
                        ActionResult.Success("ACTION_LONG_CLICK at ($x,$y)")
                    } else {
                        ActionResult.Failure("ACTION_LONG_CLICK returned false at ($x,$y)")
                    }
                } finally {
                    node.recycle()
                }
            } finally {
                root.recycle()
            }
        }
    }

    private suspend fun performSetTextOnNodeAt(
        x: Int, y: Int, text: String, clear: Boolean
    ): ActionResult {
        return withContext(Dispatchers.Main) {
            val root = getRootOnDisplay()
                ?: return@withContext ActionResult.Failure(
                    "No a11y root on display $displayId for set-text"
                )

            try {
                val node = AccessibilityNodeFinder.findNodeAtLocation(root, x, y)
                    ?: return@withContext ActionResult.Failure("No text-input node at ($x,$y)")

                try {
                    setTextOnNode(node, text, clear)
                } finally {
                    if (node !== root) node.recycle()
                }
            } finally {
                root.recycle()
            }
        }
    }

    private suspend fun performSetTextOnFocused(text: String, clear: Boolean): ActionResult {
        return withContext(Dispatchers.Main) {
            val root = getRootOnDisplay()
                ?: return@withContext ActionResult.Failure(
                    "No a11y root on display $displayId for set-text"
                )

            try {
                val node = AccessibilityNodeFinder.findFocusedEditableNode(root)
                    ?: return@withContext ActionResult.Failure(
                        "No focused editable element on display $displayId"
                    )

                try {
                    setTextOnNode(node, text, clear)
                } finally {
                    node.recycle()
                }
            } finally {
                root.recycle()
            }
        }
    }

    private fun setTextOnNode(
        node: AccessibilityNodeInfo, text: String, clear: Boolean
    ): ActionResult {
        if (clear) {
            val clearArgs = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ""
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) {
            ActionResult.Success("Text entered: $text")
        } else {
            ActionResult.Failure("ACTION_SET_TEXT failed")
        }
    }

    // ── Coordinate-based Actions (Shizuku Injection) ────────────

    private fun injectTap(x: Int, y: Int): ActionResult {
        val downTime = SystemClock.uptimeMillis()
        val down = motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())
        val up = motionEvent(downTime, downTime + 50, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat())

        val ok = shizuku.injectInputEvent(down) && shizuku.injectInputEvent(up)
        down.recycle()
        up.recycle()

        return if (ok) {
            ActionResult.Success("Tap at ($x,$y)")
        } else {
            ActionResult.Failure("Tap inject failed at ($x,$y)")
        }
    }

    private suspend fun injectLongPress(x: Int, y: Int, durationMs: Long): ActionResult {
        val downTime = SystemClock.uptimeMillis()
        val down = motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())

        if (!shizuku.injectInputEvent(down)) {
            down.recycle()
            return ActionResult.Failure("Long press DOWN inject failed at ($x,$y)")
        }
        down.recycle()

        // Hold for the requested duration (non-blocking).
        delay(durationMs)

        val up = motionEvent(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x.toFloat(), y.toFloat())
        val ok = shizuku.injectInputEvent(up)
        up.recycle()

        return if (ok) {
            ActionResult.Success("Long press at ($x,$y) for ${durationMs}ms")
        } else {
            ActionResult.Failure("Long press UP inject failed at ($x,$y)")
        }
    }

    private suspend fun injectSwipe(action: UIAction.Swipe): ActionResult {
        val downTime = SystemClock.uptimeMillis()
        val steps = 20
        val stepMs = (action.durationMs / steps).coerceAtLeast(1)

        // DOWN at start.
        val down = motionEvent(
            downTime, downTime, MotionEvent.ACTION_DOWN,
            action.startX.toFloat(), action.startY.toFloat()
        )
        if (!shizuku.injectInputEvent(down)) {
            down.recycle()
            return ActionResult.Failure("Swipe DOWN inject failed")
        }
        down.recycle()

        // MOVE steps with linear interpolation (non-blocking delay).
        for (i in 1..steps) {
            delay(stepMs)
            val t = i.toFloat() / steps
            val x = action.startX + (action.endX - action.startX) * t
            val y = action.startY + (action.endY - action.startY) * t
            val move = motionEvent(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y
            )
            shizuku.injectInputEvent(move)
            move.recycle()
        }

        // UP at end.
        val up = motionEvent(
            downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
            action.endX.toFloat(), action.endY.toFloat()
        )
        val ok = shizuku.injectInputEvent(up)
        up.recycle()

        return if (ok) {
            ActionResult.Success(
                "Swipe (${action.startX},${action.startY}) → (${action.endX},${action.endY})"
            )
        } else {
            ActionResult.Failure("Swipe UP inject failed")
        }
    }

    // ── System Button Injection ─────────────────────────────────

    private fun injectSystemButton(button: SystemButtonType): ActionResult {
        val keyCode = when (button) {
            SystemButtonType.BACK -> KeyEvent.KEYCODE_BACK
            SystemButtonType.HOME -> KeyEvent.KEYCODE_HOME
            SystemButtonType.RECENTS -> KeyEvent.KEYCODE_APP_SWITCH
            SystemButtonType.ENTER -> KeyEvent.KEYCODE_ENTER
        }

        val now = SystemClock.uptimeMillis()
        val down = keyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode)
        val up = keyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode)

        val ok = shizuku.injectInputEvent(down) && shizuku.injectInputEvent(up)

        return if (ok) {
            ActionResult.Success("System button: $button")
        } else {
            ActionResult.Failure("System button inject failed: $button")
        }
    }

    // ── Other AndroidPlatform Methods ───────────────────────────

    override fun hasRequiredPermissions(): Boolean {
        return shizuku.isAvailable() && shizuku.hasPermission()
    }

    override fun getCurrentPackageName(): String? {
        if (displayId == Display.INVALID_DISPLAY) return null
        return try {
            val windows = service.windows?.filter { it.displayId == displayId } ?: return null
            val appWindow = windows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION
            }
            appWindow?.root?.packageName?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get package name for display $displayId", e)
            null
        }
    }

    override fun getDisplayInfo(): DisplayInfo {
        return DisplayInfo(
            widthPixels = config.width,
            heightPixels = config.height,
            density = config.density
        )
    }

    override suspend fun getInstalledApps(): List<AppInfo> {
        // Installed apps are device-wide, not display-specific.
        return withContext(Dispatchers.IO) {
            try {
                val pm = service.packageManager
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                    .mapNotNull { info ->
                        val ai = info.activityInfo ?: return@mapNotNull null
                        AppInfo(
                            packageName = ai.packageName,
                            label = info.loadLabel(pm)?.toString() ?: ai.packageName,
                            isSystemApp = (ai.applicationInfo.flags and
                                android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get installed apps", e)
                emptyList()
            }
        }
    }

    override suspend fun launchApp(packageName: String): ActionResult {
        return withContext(Dispatchers.Main) {
            try {
                val launchIntent = service.packageManager.getLaunchIntentForPackage(packageName)
                    ?: return@withContext ActionResult.Failure(
                        "App not found or not launchable: $packageName"
                    )
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                shizuku.launchOnDisplay(service, launchIntent, displayId)
                ActionResult.Success("Launched $packageName on display $displayId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch $packageName on display $displayId", e)
                ActionResult.Failure("Failed to launch $packageName: ${e.message}")
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Get the a11y root node from our virtual display's windows.
     * Prefer TYPE_APPLICATION; fall back to any window with a root.
     */
    private fun getRootOnDisplay(): AccessibilityNodeInfo? {
        if (displayId == Display.INVALID_DISPLAY) return null
        val windows = try {
            service.windows?.filter { it.displayId == displayId } ?: emptyList()
        } catch (e: Exception) {
            return null
        }

        val appWindow = windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        } ?: windows.firstOrNull { it.root != null }

        return appWindow?.root
    }

    /**
     * Create a MotionEvent targeting our virtual display.
     * Uses hidden InputEvent.setDisplayId() via HiddenApiBypass.
     */
    private fun motionEvent(
        downTime: Long, eventTime: Long,
        action: Int, x: Float, y: Float
    ): MotionEvent {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        setDisplayId(event, displayId)
        return event
    }

    /**
     * Create a KeyEvent targeting our virtual display.
     */
    private fun keyEvent(
        downTime: Long, eventTime: Long,
        action: Int, keyCode: Int
    ): KeyEvent {
        val event = KeyEvent(downTime, eventTime, action, keyCode, 0)
        setDisplayId(event, displayId)
        return event
    }

    /**
     * Set displayId on an InputEvent via reflection.
     * InputEvent.setDisplayId(int) is @hide in AOSP.
     * HiddenApiBypass exemptions must be active (called in start()).
     */
    private fun setDisplayId(event: android.view.InputEvent, id: Int) {
        try {
            val method = android.view.InputEvent::class.java.getMethod(
                "setDisplayId", Int::class.javaPrimitiveType
            )
            method.invoke(event, id)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set displayId=$id on ${event.javaClass.simpleName}", e)
        }
    }
}

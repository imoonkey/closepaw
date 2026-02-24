package com.moonkey.androidagent.platform.virtualdisplay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.media.ImageReader
import android.util.Log
import android.view.Display
import android.view.SurfaceView
import android.view.accessibility.AccessibilityWindowInfo
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.model.ScreenSnapshotDebug
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.AppInfo
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.NodeActionPerformer
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.trace.TraceRecorder
import com.moonkey.androidagent.util.recycleCompat
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

/**
 * VirtualDisplayPlatform — AndroidPlatform running on a Shizuku virtual display.
 *
 * Orchestrator: delegates to VirtualDisplayWindowAccessor (window/root), NodeActionPerformer (node
 * actions), and VirtualDisplayInputInjector (input).
 *
 * Screen capture: ImageReader (we own the surface). A11y tree: windows filtered by displayId. Node
 * actions: a11y performAction. Coordinate actions: Shizuku injection.
 *
 * Lifecycle: start() → creates virtual display + ImageReader; stop() → releases.
 */
class VirtualDisplayPlatform(
        private val service: AccessibilityService,
        private val shizuku: ShizukuClient,
        private val config: VirtualDisplayConfig,
        private val sessionConfig: SessionConfig,
        private val traceRecorder: TraceRecorder
) : AndroidPlatform {
    companion object {
        private const val TAG = "VirtualDisplayPlatform"

        private const val DISPLAY_FLAGS = 0x1 or 0x8 or 0x40 or 0x200 or 0x400 or 0x800

        private const val SURFACE_READY_DELAY_MS = 200L
        private const val IMAGE_READER_MAX_IMAGES = 2
    }

    @Volatile private var displayId: Int = Display.INVALID_DISPLAY
    @Volatile private var imageReader: ImageReader? = null
    private var binderDeadListener: Shizuku.OnBinderDeadListener? = null

    // ── Surface switching (Hybrid Model) ──
    private val displayIdProvider: () -> Int = { displayId }
    private val imageReaderProvider: () -> ImageReader? = { imageReader }

    private val windowAccessor = VirtualDisplayWindowAccessor(service, displayIdProvider)

    private val nodeActionPerformer =
            NodeActionPerformer(rootProvider = { windowAccessor.getRootOnDisplay() })

    private val inputInjector = VirtualDisplayInputInjector(shizuku, displayIdProvider)
    private val screenshotProcessor =
            VirtualDisplayScreenshotProcessor(
                    service = service,
                    sessionConfig = sessionConfig,
                    traceRecorder = traceRecorder
            )
    private val appController =
            VirtualDisplayAppController(
                    service = service,
                    shizuku = shizuku,
                    displayIdProvider = displayIdProvider
            )
    private val surfaceController =
            VirtualDisplaySurfaceController(
                    shizuku = shizuku,
                    displayIdProvider = displayIdProvider,
                    imageReaderProvider = imageReaderProvider
            )
    private val captureCoordinator =
            VirtualDisplayCaptureCoordinator(
                    config = config,
                    windowAccessor = windowAccessor,
                    imageReaderProvider = imageReaderProvider,
                    surfaceController = surfaceController,
                    switchToImageReader = { switchToImageReader() },
                    screenshotProcessor = screenshotProcessor,
                    traceRecorder = traceRecorder
            )
    private val viewerTouchHandler =
            VirtualDisplayViewerTouchHandler(
                    config = config,
                    displayIdProvider = displayIdProvider,
                    inputInjector = inputInjector,
                    shizuku = shizuku
            )

    override suspend fun start() {
        if (displayId != Display.INVALID_DISPLAY) return // Already running

        shizuku.bypassHiddenApis()

        val reader =
                ImageReader.newInstance(
                        config.width,
                        config.height,
                        PixelFormat.RGBA_8888,
                        IMAGE_READER_MAX_IMAGES
                )

        val id =
                shizuku.createVirtualDisplay(
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

        binderDeadListener =
                Shizuku.OnBinderDeadListener {
                    Log.e(TAG, "Shizuku binder died! displayId=$displayId")
                }
        shizuku.addBinderDeadListener(binderDeadListener!!)

        delay(SURFACE_READY_DELAY_MS)

        Log.i(
                TAG,
                "Started: displayId=$displayId, ${config.width}x${config.height}@${config.densityDpi}dpi"
        )
    }

    /**
     * Release platform resources. Called during session cleanup AFTER the agent loop has exited.
     * Must be idempotent. Not safe to call concurrently with captureScreen/performAction.
     */
    override suspend fun stop() {
        // Restore keyboard first — prevent permanently disabled IME on crash
        setKeyboardAuto()

        binderDeadListener?.let { shizuku.removeBinderDeadListener(it) }
        binderDeadListener = null

        surfaceController.reset()

        if (displayId != Display.INVALID_DISPLAY) {
            shizuku.releaseVirtualDisplay(displayId)
        }

        imageReader?.close()
        imageReader = null
        displayId = Display.INVALID_DISPLAY

        Log.i(TAG, "Stopped")
    }

    // ── Hybrid Surface Switching ──────────────────────────────────

    /**
     * Switch VirtualDisplay output to the Viewer's SurfaceView for 60fps live preview. Called when
     * the Viewer Activity becomes visible.
     */
    fun switchToLivePreview(surfaceView: SurfaceView) {
        val before = surfaceController.mode()
        surfaceController.switchToLivePreview(surfaceView)
        val after = surfaceController.mode()
        if (before != VirtualDisplaySurfaceMode.LIVE_PREVIEW &&
                after == VirtualDisplaySurfaceMode.LIVE_PREVIEW
        ) {
            captureCoordinator.onLivePreviewActivated()
        }
    }

    /**
     * Switch VirtualDisplay output back to the ImageReader for headless capture. Called when the
     * Viewer Activity is hidden or destroyed.
     */
    fun switchToImageReader() {
        surfaceController.switchToImageReader()
    }

    /** Current surface mode, for UI to check. */
    fun getSurfaceMode(): VirtualDisplaySurfaceMode = surfaceController.mode()

    /**
     * Forward a touch stream from VirtualDisplayViewerActivity into the virtual display.
     *
     * Primary path uses raw MotionEvent injection when InputEvent#setDisplayId is available.
     * Fallback path uses shell `input tap/swipe --display` when that hidden API is unavailable.
     */
    fun onViewerTouch(
            action: Int,
            x: Float,
            y: Float,
            downTime: Long,
            eventTime: Long,
            viewWidth: Int,
            viewHeight: Int,
    ): Boolean {
        return viewerTouchHandler.onViewerTouch(
                action = action,
                x = x,
                y = y,
                downTime = downTime,
                eventTime = eventTime,
                viewWidth = viewWidth,
                viewHeight = viewHeight
        )
    }

    // ── Screen Capture ──────────────────────────────────────────

    override suspend fun captureScreen(): ScreenSnapshot {
        val timestamp = System.currentTimeMillis()
        val pc = sessionConfig.perceptionConfig

        // 1. Capture a11y tree with trace artifacts (when tracing enabled)
        val a11yResult =
                if (pc.capturesAccessibility)
                        captureCoordinator.captureA11yTreeWithArtifacts()
                else
                        VirtualDisplayCaptureCoordinator.A11yCaptureResult(emptyList(), null, null)

        // 2. Screenshot capture (when config requires it OR trace is enabled for debugging)
        val shouldCaptureScreenshot = pc.capturesScreenshot || traceRecorder.enabled
        val imageCapture =
                if (shouldCaptureScreenshot) captureCoordinator.captureScreenshot() else null

        // 3. Only include screenshot in the snapshot if the perception config wants it
        val image = if (pc.capturesScreenshot) imageCapture?.image else null

        Log.d(TAG, "Captured screen: ${a11yResult.elements.size} elements, screenshot=${image != null}")

        // 4. Build debug info
        val debug =
                if (traceRecorder.enabled) {
                    ScreenSnapshotDebug(
                            rawA11yTreePath = a11yResult.rawTreeArtifactPath,
                            sanitizedA11yTreePath = a11yResult.sanitizedTreeArtifactPath,
                            screenshotPath = imageCapture?.tracePath
                    )
                } else {
                    null
                }

        return ScreenSnapshot(
                timestamp = timestamp,
                elements = a11yResult.elements,
                image = image,
                debug = debug
        )
    }

    override fun allowTapToFocus(): Boolean = false

    override suspend fun performAction(action: UIAction): ActionResult {
        if (displayId == Display.INVALID_DISPLAY) {
            return ActionResult.Failure("Virtual display not started")
        }

        val suppressIme = action.mayTriggerIme() && !isKeyboardVisibleOnMainDisplay()
        if (suppressIme) setKeyboardHidden()

        return try {
            dispatchAction(action)
        } finally {
            if (suppressIme) setKeyboardAuto()
        }
    }

    private suspend fun dispatchAction(action: UIAction): ActionResult =
            when (action) {
                is UIAction.ClickNodeAt ->
                        nodeActionPerformer.performNodeClickAt(action.x, action.y, action.semanticHint)
                is UIAction.LongClickNodeAt ->
                        nodeActionPerformer.performNodeLongClickAt(action.x, action.y, action.semanticHint)
                is UIAction.SetTextOnNodeAt ->
                        nodeActionPerformer.performSetTextOnNodeAt(
                                action.x,
                                action.y,
                                action.text,
                                action.clear
                        )
                is UIAction.SetTextOnFocused ->
                        nodeActionPerformer.performSetTextOnFocused(action.text, action.clear)
                is UIAction.TapAt -> inputInjector.injectTap(action.x, action.y)
                is UIAction.LongPressAt ->
                        inputInjector.injectLongPress(action.x, action.y, action.durationMs)
                is UIAction.ScrollNodeAt ->
                        nodeActionPerformer.performScrollAt(action.x, action.y, action.direction)
                is UIAction.Swipe -> performSwipe(action)
                is UIAction.SystemButton -> inputInjector.injectSystemButton(action.button)
                is UIAction.Wait -> {
                    delay(action.durationMs)
                    ActionResult.Success("Waited ${action.durationMs}ms")
                }
            }

    // ===== Action Helpers =====

    /**
     * Clamp swipe coordinates to virtual display bounds.
     * No edge inset needed — virtual display doesn't have gesture-nav interference.
     */
    private suspend fun performSwipe(action: UIAction.Swipe): ActionResult {
        val maxX = (config.width - 1).coerceAtLeast(0)
        val maxY = (config.height - 1).coerceAtLeast(0)

        val startX = action.startX.coerceIn(0, maxX)
        val startY = action.startY.coerceIn(0, maxY)
        val endX = action.endX.coerceIn(0, maxX)
        val endY = action.endY.coerceIn(0, maxY)

        if (startX != action.startX ||
                        startY != action.startY ||
                        endX != action.endX ||
                        endY != action.endY
        ) {
            Log.w(TAG, "Swipe coordinates clamped to virtual display bounds")
        }

        Log.d(
                TAG,
                "Swipe: ($startX,$startY) -> ($endX,$endY), duration=${action.durationMs}ms"
        )
        return inputInjector.injectSwipe(
                UIAction.Swipe(startX, startY, endX, endY, action.durationMs)
        )
    }

    override fun hasRequiredPermissions(): Boolean {
        return shizuku.isAvailable() && shizuku.hasPermission()
    }

    override fun getCurrentPackageName(): String? {
        val root = windowAccessor.getRootOnDisplay() ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycleCompat()
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
        return appController.getInstalledApps()
    }

    override suspend fun launchApp(packageName: String): ActionResult {
        return appController.launchApp(packageName)
    }

    // ── IME Suppression ──────────────────────────────────────────

    /** Actions that can trigger IME on the wrong display. */
    private fun UIAction.mayTriggerIme(): Boolean =
            this is UIAction.ClickNodeAt ||
                    this is UIAction.TapAt ||
                    this is UIAction.LongClickNodeAt ||
                    this is UIAction.LongPressAt ||
                    this is UIAction.SetTextOnNodeAt ||
                    this is UIAction.SetTextOnFocused

    /** Set SHOW_MODE_HIDDEN to prevent VD-triggered IME from appearing on display 0. */
    private fun setKeyboardHidden() {
        try {
            service.softKeyboardController.setShowMode(
                    AccessibilityService.SHOW_MODE_HIDDEN
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to suppress keyboard", e)
        }
    }

    /** Restore keyboard to auto mode. Safe to call multiple times. */
    private fun setKeyboardAuto() {
        try {
            service.softKeyboardController.setShowMode(
                    AccessibilityService.SHOW_MODE_AUTO
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore keyboard", e)
        }
    }

    /** Check if IME window is visible on the main display (display 0). */
    private fun isKeyboardVisibleOnMainDisplay(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val allWindows = service.getWindowsOnAllDisplays()
                val mainWindows = allWindows.get(Display.DEFAULT_DISPLAY) ?: return false
                mainWindows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            } else {
                service.windows?.any {
                    it.displayId == Display.DEFAULT_DISPLAY &&
                            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                } == true
            }
        } catch (_: Exception) {
            false
        }
    }
}

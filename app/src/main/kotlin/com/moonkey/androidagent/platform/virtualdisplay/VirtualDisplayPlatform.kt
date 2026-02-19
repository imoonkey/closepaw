package com.moonkey.androidagent.platform.virtualdisplay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.media.ImageReader
import android.util.Log
import android.view.Display
import android.view.SurfaceView
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
                    screenshotProcessor = screenshotProcessor
            )
    private val viewerTouchHandler =
            VirtualDisplayViewerTouchHandler(
                    config = config,
                    displayIdProvider = displayIdProvider,
                    inputInjector = inputInjector,
                    shizuku = shizuku
            )

    override suspend fun start() {
        check(displayId == Display.INVALID_DISPLAY) { "Already started (displayId=$displayId)" }

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

        val elements =
                if (pc.capturesAccessibility) captureCoordinator.captureA11yTree() else emptyList()
        val imageCapture = if (pc.capturesScreenshot) captureCoordinator.captureScreenshot() else null

        Log.d(TAG, "Captured screen: ${elements.size} elements, screenshot=${imageCapture != null}")

        val debug =
                imageCapture?.tracePath?.let { path ->
                    ScreenSnapshotDebug(
                            rawA11yTreePath = null,
                            sanitizedA11yTreePath = null,
                            screenshotPath = path
                    )
                }

        return ScreenSnapshot(
                timestamp = timestamp,
                elements = elements,
                image = imageCapture?.image,
                debug = debug
        )
    }

    override fun allowTapToFocus(): Boolean = false

    override suspend fun performAction(action: UIAction): ActionResult {
        if (displayId == Display.INVALID_DISPLAY) {
            return ActionResult.Failure("Virtual display not started")
        }

        val result =
                when (action) {
                    is UIAction.ClickNodeAt ->
                            nodeActionPerformer.performNodeClickAt(action.x, action.y)
                    is UIAction.LongClickNodeAt ->
                            nodeActionPerformer.performNodeLongClickAt(action.x, action.y)
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
                    is UIAction.Swipe -> inputInjector.injectSwipe(action)
                    is UIAction.SystemButton -> inputInjector.injectSystemButton(action.button)
                    is UIAction.Wait -> {
                        delay(action.durationMs)
                        ActionResult.Success("Waited ${action.durationMs}ms")
                    }
                }

        // Safety net: dismiss keyboard on main display after text actions.
        // Even with allowTapToFocus=false, some apps auto-focus fields on window attach.
        if (action is UIAction.SetTextOnNodeAt || action is UIAction.SetTextOnFocused) {
            appController.dismissMainDisplayKeyboard()
        }

        return result
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
}

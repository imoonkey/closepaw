package com.moonkey.androidagent.platform.virtualdisplay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.PixelCopy
import android.view.SurfaceView
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.ScreenImage
import com.moonkey.androidagent.model.ScreenImageSource
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.model.ScreenSnapshotDebug
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.perception.screenshotJpegQuality
import com.moonkey.androidagent.perception.screenshotMaxDimension
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.AppInfo
import com.moonkey.androidagent.platform.AppManager
import com.moonkey.androidagent.platform.BitmapUtils
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.NodeActionPerformer
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.trace.TraceRecorder
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

    private data class VDScreenshotCapture(val image: ScreenImage, val tracePath: String?)

    /** Which surface the VirtualDisplay is currently rendering to. */
    enum class SurfaceMode {
        IMAGE_READER,
        LIVE_PREVIEW
    }

    companion object {
        private const val TAG = "VirtualDisplayPlatform"

        private const val DISPLAY_FLAGS = 0x1 or 0x8 or 0x40 or 0x200 or 0x400 or 0x800

        private const val SURFACE_READY_DELAY_MS = 200L
        private const val IMAGE_READER_MAX_IMAGES = 2

        /** PixelCopy failures before permanently reverting to ImageReader. */
        private const val PIXEL_COPY_MAX_FAILURES = 2
    }

    @Volatile private var displayId: Int = Display.INVALID_DISPLAY
    @Volatile private var imageReader: ImageReader? = null
    private var binderDeadListener: Shizuku.OnBinderDeadListener? = null

    // ── Surface switching (Hybrid Model) ──
    @Volatile private var surfaceMode = SurfaceMode.IMAGE_READER
    @Volatile private var liveSurfaceView: SurfaceView? = null
    @Volatile private var pixelCopyFailCount = 0

    private val displayIdProvider: () -> Int = { displayId }

    private val windowAccessor = VirtualDisplayWindowAccessor(service, displayIdProvider)

    private val nodeActionPerformer =
            NodeActionPerformer(rootProvider = { windowAccessor.getRootOnDisplay() })

    private val inputInjector = VirtualDisplayInputInjector(shizuku, displayIdProvider)

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

        liveSurfaceView = null
        surfaceMode = SurfaceMode.IMAGE_READER

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
        if (surfaceMode == SurfaceMode.LIVE_PREVIEW) return
        val surface =
                surfaceView.holder.surface
                        ?: run {
                            Log.w(
                                    TAG,
                                    "SurfaceView holder has no valid surface, staying on ImageReader"
                            )
                            return
                        }
        if (!surface.isValid) {
            Log.w(TAG, "SurfaceView surface is invalid, staying on ImageReader")
            return
        }
        val ok = shizuku.setVirtualDisplaySurface(displayId, surface)
        if (ok) {
            liveSurfaceView = surfaceView
            surfaceMode = SurfaceMode.LIVE_PREVIEW
            pixelCopyFailCount = 0
            Log.i(TAG, "Switched to live preview surface")
        } else {
            Log.w(TAG, "setSurface failed, staying on ImageReader")
        }
    }

    /**
     * Switch VirtualDisplay output back to the ImageReader for headless capture. Called when the
     * Viewer Activity is hidden or destroyed.
     */
    fun switchToImageReader() {
        if (surfaceMode == SurfaceMode.IMAGE_READER) return
        val reader = imageReader ?: return
        val ok = shizuku.setVirtualDisplaySurface(displayId, reader.surface)
        if (ok) {
            liveSurfaceView = null
            surfaceMode = SurfaceMode.IMAGE_READER
            Log.i(TAG, "Switched to ImageReader surface")
        } else {
            Log.w(TAG, "Failed to switch back to ImageReader — display may be in bad state")
        }
    }

    /** Current surface mode, for UI to check. */
    fun getSurfaceMode(): SurfaceMode = surfaceMode

    // ── Screen Capture ──────────────────────────────────────────

    override suspend fun captureScreen(): ScreenSnapshot {
        val timestamp = System.currentTimeMillis()
        val pc = sessionConfig.perceptionConfig

        val elements = if (pc.capturesAccessibility) captureA11yTree() else emptyList()
        val imageCapture = if (pc.capturesScreenshot) captureScreenshot() else null

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

    private suspend fun captureA11yTree(): List<PerceptionElement> {
        return withContext(Dispatchers.Main) {
            val root = windowAccessor.getRootOnDisplay() ?: return@withContext emptyList()
            try {
                Perceptor.snapshot(root, config.width, config.height).elements
            } catch (e: Exception) {
                Log.w(TAG, "Perceptor.snapshot failed", e)
                emptyList()
            } finally {
                root.recycle()
            }
        }
    }

    private suspend fun captureScreenshot(): VDScreenshotCapture? {
        return when (surfaceMode) {
            SurfaceMode.IMAGE_READER -> captureFromImageReader()
            SurfaceMode.LIVE_PREVIEW -> captureFromPixelCopy()
        }
    }

    private suspend fun captureFromImageReader(): VDScreenshotCapture? {
        val reader = imageReader ?: return null

        return withContext(Dispatchers.Default) {
            val image =
                    try {
                        reader.acquireLatestImage()
                    } catch (e: Exception) {
                        Log.w(TAG, "acquireLatestImage failed", e)
                        return@withContext null
                    }
            if (image == null) return@withContext null

            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                if (pixelStride == 0) return@withContext null
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width

                val bitmap =
                        Bitmap.createBitmap(
                                image.width + rowPadding / pixelStride,
                                image.height,
                                Bitmap.Config.ARGB_8888
                        )
                bitmap.copyPixelsFromBuffer(plane.buffer)

                val cropped =
                        if (bitmap.width > config.width) {
                            Bitmap.createBitmap(bitmap, 0, 0, config.width, config.height).also {
                                if (it !== bitmap) bitmap.recycle()
                            }
                        } else bitmap

                bitmapToScreenImage(cropped)
            } finally {
                image.close()
            }
        }
    }

    /**
     * Capture a screenshot via PixelCopy when surface is pointed at the Viewer's SurfaceView. On
     * consecutive failures, permanently reverts to ImageReader mode.
     */
    private suspend fun captureFromPixelCopy(): VDScreenshotCapture? {
        val sv = liveSurfaceView
        if (sv == null || !sv.holder.surface.isValid) {
            Log.w(TAG, "PixelCopy: no valid SurfaceView, falling back to ImageReader")
            switchToImageReader()
            return captureFromImageReader()
        }

        val bitmap = Bitmap.createBitmap(config.width, config.height, Bitmap.Config.ARGB_8888)

        val result =
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<Int> { cont ->
                        PixelCopy.request(
                                sv,
                                bitmap,
                                { copyResult -> cont.resume(copyResult) },
                                Handler(Looper.getMainLooper())
                        )
                    }
                }

        if (result != PixelCopy.SUCCESS) {
            bitmap.recycle()
            pixelCopyFailCount++
            Log.w(TAG, "PixelCopy failed (result=$result, failCount=$pixelCopyFailCount)")
            if (pixelCopyFailCount >= PIXEL_COPY_MAX_FAILURES) {
                Log.w(TAG, "PixelCopy failed $pixelCopyFailCount times, reverting to ImageReader")
                switchToImageReader()
            }
            return captureFromImageReader()
        }

        pixelCopyFailCount = 0
        return withContext(Dispatchers.Default) { bitmapToScreenImage(bitmap) }
    }

    /** Scale, compress, and wrap a Bitmap into a ScreenImage. */
    private fun bitmapToScreenImage(bitmap: Bitmap): VDScreenshotCapture? {
        val maxDim = sessionConfig.perceptionConfig.screenshotMaxDimension
        val quality = sessionConfig.perceptionConfig.screenshotJpegQuality
        val scaled = BitmapUtils.scaleBitmapIfNeeded(bitmap, maxDim)
        val width = scaled.width
        val height = scaled.height
        val bytes = BitmapUtils.compressJpeg(scaled, quality)

        if (scaled !== bitmap) bitmap.recycle()
        scaled.recycle()

        return bytes?.let {
            // Save to trace if enabled
            val tracePath =
                    if (traceRecorder.enabled) {
                        traceRecorder.storeBytes(
                                        kind = "screenshot",
                                        filenameHint =
                                                "screenshot_${System.currentTimeMillis()}_${width}x${height}.jpg",
                                        bytes = it,
                                        mimeType = "image/jpeg"
                                )
                                ?.path
                    } else {
                        null
                    }

            // Also persist for debug if needed
            if (sessionConfig.debugMode) {
                persistDebugScreenshot(it, width, height)
            }

            val image =
                    ScreenImage(
                            width = width,
                            height = height,
                            mimeType = "image/jpeg",
                            bytes = it,
                            source = ScreenImageSource.VIRTUAL_DISPLAY_CAPTURE
                    )
            VDScreenshotCapture(image, tracePath)
        }
    }

    private fun persistDebugScreenshot(bytes: ByteArray, width: Int, height: Int) {
        try {
            val debugDir = File(service.getExternalFilesDir(null), "debug-output")
            if (!debugDir.exists()) debugDir.mkdirs()
            val file =
                    File(
                            debugDir,
                            "vd_screenshot_${System.currentTimeMillis()}_${width}x${height}.jpg"
                    )
            FileOutputStream(file).use { it.write(bytes) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist debug screenshot", e)
        }
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
            dismissMainDisplayKeyboard()
        }

        return result
    }

    /**
     * Dismiss keyboard on display 0 (real screen). KEYCODE_BACK on display 0 dismisses IME if
     * showing, benign if not.
     */
    private fun dismissMainDisplayKeyboard() {
        try {
            shizuku.executeShellCommand(arrayOf("input", "keyevent", "--display", "0", "4"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dismiss main display keyboard", e)
        }
    }

    override fun hasRequiredPermissions(): Boolean {
        return shizuku.isAvailable() && shizuku.hasPermission()
    }

    override fun getCurrentPackageName(): String? {
        val root = windowAccessor.getRootOnDisplay() ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycle()
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
        return withContext(Dispatchers.IO) {
            try {
                AppManager.getInstalledApps(service.packageManager)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get installed apps", e)
                emptyList()
            }
        }
    }

    override suspend fun launchApp(packageName: String): ActionResult {
        return withContext(Dispatchers.IO) {
            try {
                val launchIntent =
                        service.packageManager.getLaunchIntentForPackage(packageName)
                                ?: return@withContext ActionResult.Failure(
                                        "App not found or not launchable: $packageName"
                                )

                val component = launchIntent.component?.flattenToShortString()
                val shizukuAvailable = shizuku.isAvailable()
                Log.d(TAG, "launchApp: component=$component, shizukuAvailable=$shizukuAvailable")

                if (component != null && shizukuAvailable) {
                    Log.d(TAG, "Launching $component on display $displayId via shell")
                    // Remove -W to avoid Shizuku process wait issues (process hasn't exited
                    // exception)
                    val cmd = arrayOf("am", "start", "-n", component, "--display", "$displayId")
                    val code = shizuku.executeShellCommand(cmd)
                    if (code == 0) {
                        return@withContext ActionResult.Success(
                                "Launched $component on display $displayId (shell)"
                        )
                    }
                    Log.w(TAG, "Shell launch failed (code $code), falling back to intent")
                }

                launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
                shizuku.launchOnDisplay(service, launchIntent, displayId)
                ActionResult.Success("Launched $packageName on display $displayId (intent)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch $packageName on display $displayId", e)
                ActionResult.Failure("Failed to launch $packageName: ${e.message}")
            }
        }
    }
}

package com.moonkey.androidagent.platform.virtualdisplay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.util.Log
import android.view.Display
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.ScreenImage
import com.moonkey.androidagent.model.ScreenImageSource
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.perception.screenshotJpegQuality
import com.moonkey.androidagent.perception.screenshotMaxDimension
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.AppInfo
import com.moonkey.androidagent.platform.BitmapUtils
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.protocol.SessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * VirtualDisplayPlatform — AndroidPlatform running on a Shizuku virtual display.
 *
 * Orchestrator: delegates to VirtualDisplayWindowAccessor (window/root),
 * VirtualDisplayNodeActionPerformer (node actions), and VirtualDisplayInputInjector (input).
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
        private val sessionConfig: SessionConfig
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

    private val displayIdProvider: () -> Int = { displayId }

    private val windowAccessor = VirtualDisplayWindowAccessor(service, displayIdProvider)

    private val nodeActionPerformer = VirtualDisplayNodeActionPerformer(windowAccessor)

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

        if (displayId != Display.INVALID_DISPLAY) {
            shizuku.releaseVirtualDisplay(displayId)
        }

        imageReader?.close()
        imageReader = null
        displayId = Display.INVALID_DISPLAY

        Log.i(TAG, "Stopped")
    }

    override suspend fun captureScreen(): ScreenSnapshot {
        val timestamp = System.currentTimeMillis()
        val pc = sessionConfig.perceptionConfig

        val elements = if (pc.capturesAccessibility) captureA11yTree() else emptyList()
        val image = if (pc.capturesScreenshot) captureScreenshot() else null

        Log.d(TAG, "Captured screen: ${elements.size} elements, screenshot=${image != null}")

        return ScreenSnapshot(timestamp = timestamp, elements = elements, image = image)
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

    private suspend fun captureScreenshot(): ScreenImage? {
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

    override suspend fun performAction(action: UIAction): ActionResult {
        if (displayId == Display.INVALID_DISPLAY) {
            return ActionResult.Failure("Virtual display not started")
        }

        return when (action) {
            is UIAction.ClickNodeAt -> nodeActionPerformer.performNodeClickAt(action.x, action.y)
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
                val pm = service.packageManager
                val intent =
                        Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                        .mapNotNull { info ->
                            val ai = info.activityInfo ?: return@mapNotNull null
                            AppInfo(
                                    packageName = ai.packageName,
                                    label =
                                            info.loadLabel(pm).toString().ifBlank {
                                                ai.packageName
                                            },
                                    isSystemApp =
                                            (ai.applicationInfo.flags and
                                                    android.content.pm.ApplicationInfo
                                                            .FLAG_SYSTEM) != 0
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
                    val cmd =
                            arrayOf("am", "start", "-n", component, "--display", "$displayId", "-W")
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

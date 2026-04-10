package com.moonkey.androidagent.platform

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import com.moonkey.androidagent.model.ScreenImage
import com.moonkey.androidagent.model.ScreenImageSource
import com.moonkey.androidagent.perception.screenshotJpegQuality
import com.moonkey.androidagent.perception.screenshotMaxDimension
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.trace.TraceRecorder
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Accessibility screenshot capture pipeline:
 * 1) a11y screenshot API (bounded — never waits forever)
 * 2) software bitmap conversion
 * 3) scale + jpeg compression
 * 4) optional debug/trace persistence
 */
class AccessibilityScreenshotCapturer(
        private val service: AccessibilityService,
        private val config: SessionConfig,
        private val traceRecorder: TraceRecorder
) {
    companion object {
        private const val TAG = "A11yScreenshotCapturer"
        private const val SCREENSHOT_TIMEOUT_MS = 5_000L
        private const val MAX_DEBUG_SCREENSHOTS = 20
    }

    data class ScreenshotCapture(val image: ScreenImage, val tracePath: String?)

    suspend fun captureIfEnabled(windowId: Int?, enabled: Boolean): ScreenshotCapture? {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }

        val result = takeScreenshotResult(windowId) ?: return null
        return compressScreenshot(result)
    }

    private suspend fun takeScreenshotResult(
            windowId: Int?
    ): AccessibilityService.ScreenshotResult? {
        val windowResult =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    windowId?.let { takeWindowScreenshot(it) }
                } else {
                    null
                }
        return windowResult ?: takeDisplayScreenshot()
    }

    private suspend fun takeDisplayScreenshot(): AccessibilityService.ScreenshotResult? {
        return withContext(Dispatchers.Main) {
            boundedCallback(
                timeoutMs = SCREENSHOT_TIMEOUT_MS,
                label = "takeScreenshot"
            ) { cont ->
                service.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        service.mainExecutor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(
                                    screenshot: AccessibilityService.ScreenshotResult
                            ) {
                                // No isActive check: late resume on a cancelled continuation
                                // is silently discarded by coroutines 1.7.3+. The isActive
                                // check creates a race where cancellation between check and
                                // resume drops the result without passing it to compressScreenshot,
                                // leaking the HardwareBuffer. Without the check, the non-timeout
                                // path always works correctly, and the timeout path has a bounded
                                // leak (one HardwareBuffer, reclaimable by GC).
                                cont.resume(screenshot)
                            }

                            override fun onFailure(errorCode: Int) {
                                Log.w(
                                        TAG,
                                        "takeScreenshot failed: ${formatScreenshotError(errorCode)}"
                                )
                                cont.resume(null)
                            }
                        }
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun takeWindowScreenshot(
            windowId: Int
    ): AccessibilityService.ScreenshotResult? {
        return withContext(Dispatchers.Main) {
            boundedCallback(
                timeoutMs = SCREENSHOT_TIMEOUT_MS,
                label = "takeScreenshotOfWindow($windowId)"
            ) { cont ->
                service.takeScreenshotOfWindow(
                        windowId,
                        service.mainExecutor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(
                                    screenshot: AccessibilityService.ScreenshotResult
                            ) {
                                cont.resume(screenshot)
                            }

                            override fun onFailure(errorCode: Int) {
                                Log.w(
                                        TAG,
                                        "takeScreenshotOfWindow failed: ${formatScreenshotError(errorCode)}"
                                )
                                cont.resume(null)
                            }
                        }
                )
            }
        }
    }

    private suspend fun compressScreenshot(
            screenshot: AccessibilityService.ScreenshotResult
    ): ScreenshotCapture? =
            withContext(Dispatchers.Default) {
                val hardwareBuffer = screenshot.hardwareBuffer
                try {
                    val hardwareBitmap =
                            Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                                    ?: return@withContext null

                    val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBitmap.recycle()
                    if (softwareBitmap == null) {
                        return@withContext null
                    }

                    val scaledBitmap =
                            BitmapUtils.scaleBitmapIfNeeded(
                                    softwareBitmap,
                                    config.perceptionConfig.screenshotMaxDimension
                            )
                    val width = scaledBitmap.width
                    val height = scaledBitmap.height
                    val jpegBytes =
                            BitmapUtils.compressJpeg(
                                    scaledBitmap,
                                    config.perceptionConfig.screenshotJpegQuality
                            )

                    if (scaledBitmap !== softwareBitmap) {
                        softwareBitmap.recycle()
                    }
                    scaledBitmap.recycle()

                    jpegBytes?.let { bytes ->
                        if (config.debugMode) {
                            persistDebugScreenshot(bytes, width, height)
                        }
                        val tracePath =
                                if (traceRecorder.enabled) {
                                    traceRecorder.storeBytes(
                                                    kind = "screenshot",
                                                    filenameHint =
                                                            "screenshot_${System.currentTimeMillis()}_${width}x${height}.jpg",
                                                    bytes = bytes,
                                                    mimeType = "image/jpeg"
                                            )
                                            ?.path
                                } else {
                                    null
                                }
                        val image =
                                ScreenImage(
                                        width = width,
                                        height = height,
                                        mimeType = "image/jpeg",
                                        bytes = bytes,
                                        source = ScreenImageSource.ACCESSIBILITY_SCREENSHOT
                                )
                        ScreenshotCapture(image = image, tracePath = tracePath)
                    }
                } finally {
                    hardwareBuffer.close()
                }
            }

    private fun persistDebugScreenshot(bytes: ByteArray, width: Int, height: Int) {
        val dir = service.getExternalFilesDir("debug-output") ?: return
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create debug-output directory")
            return
        }
        // Enforce retention limit to match VD path
        val files = dir.listFiles { _, name -> name.startsWith("llm_screenshot_") }
        if (files != null && files.size >= MAX_DEBUG_SCREENSHOTS) {
            files.sortBy { it.lastModified() }
            for (i in 0..(files.size - MAX_DEBUG_SCREENSHOTS)) {
                files[i].delete()
            }
        }
        val filename = "llm_screenshot_${System.currentTimeMillis()}_${width}x${height}.jpg"
        val file = File(dir, filename)
        try {
            file.outputStream().use { it.write(bytes) }
            Log.d(TAG, "Saved LLM screenshot: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save LLM screenshot: ${e.message}")
        }
    }

    private fun formatScreenshotError(errorCode: Int): String {
        return when (errorCode) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "interval too short"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal error"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "secure window"
            else -> "error code $errorCode"
        }
    }
}

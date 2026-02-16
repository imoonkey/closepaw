package com.moonkey.androidagent.platform.virtualdisplay

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Log
import com.moonkey.androidagent.model.ScreenImage
import com.moonkey.androidagent.model.ScreenImageSource
import com.moonkey.androidagent.perception.screenshotJpegQuality
import com.moonkey.androidagent.perception.screenshotMaxDimension
import com.moonkey.androidagent.platform.BitmapUtils
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.trace.TraceRecorder
import java.io.File
import java.io.FileOutputStream

internal data class VDScreenshotCapture(val image: ScreenImage, val tracePath: String?)

/** Encodes capture bitmaps into ScreenImage and handles trace/debug persistence. */
internal class VirtualDisplayScreenshotProcessor(
        private val service: AccessibilityService,
        private val sessionConfig: SessionConfig,
        private val traceRecorder: TraceRecorder
) {
        companion object {
                private const val TAG = "VDScreenshotProcessor"
        }

        fun toScreenImage(bitmap: Bitmap): VDScreenshotCapture? {
                val maxDim = sessionConfig.perceptionConfig.screenshotMaxDimension
                val quality = sessionConfig.perceptionConfig.screenshotJpegQuality
                val scaled = BitmapUtils.scaleBitmapIfNeeded(bitmap, maxDim)
                val width = scaled.width
                val height = scaled.height
                val bytes = BitmapUtils.compressJpeg(scaled, quality)

                if (scaled !== bitmap) bitmap.recycle()
                scaled.recycle()

                return bytes?.let {
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

                        val files = debugDir.listFiles { _, name -> name.startsWith("vd_screenshot_") }
                        if (files != null && files.size >= 20) {
                                files.sortBy { it.lastModified() }
                                for (i in 0..(files.size - 20)) {
                                        files[i].delete()
                                }
                        }

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
}

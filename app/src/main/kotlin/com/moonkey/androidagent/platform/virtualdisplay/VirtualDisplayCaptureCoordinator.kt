package com.moonkey.androidagent.platform.virtualdisplay

import android.graphics.Bitmap
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.util.recycleCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Captures a11y tree and screenshots for the virtual display. */
internal class VirtualDisplayCaptureCoordinator(
        private val config: VirtualDisplayConfig,
        private val windowAccessor: VirtualDisplayWindowAccessor,
        private val imageReaderProvider: () -> ImageReader?,
        private val surfaceController: VirtualDisplaySurfaceController,
        private val switchToImageReader: () -> Unit,
        private val screenshotProcessor: VirtualDisplayScreenshotProcessor
) {
        companion object {
                private const val TAG = "VDCaptureCoordinator"
                private const val PIXEL_COPY_MAX_FAILURES = 2
        }

        @Volatile private var pixelCopyFailCount = 0

        fun onLivePreviewActivated() {
                pixelCopyFailCount = 0
        }

        suspend fun captureA11yTree(): List<PerceptionElement> {
                return withContext(Dispatchers.Main) {
                        val root = windowAccessor.getRootOnDisplay() ?: return@withContext emptyList()
                        try {
                                Perceptor.snapshot(root, config.width, config.height).elements
                        } catch (e: Exception) {
                                Log.w(TAG, "Perceptor.snapshot failed", e)
                                emptyList()
                        } finally {
                                root.recycleCompat()
                        }
                }
        }

        suspend fun captureScreenshot(): VDScreenshotCapture? {
                return when (surfaceController.mode()) {
                        VirtualDisplaySurfaceMode.IMAGE_READER -> captureFromImageReader()
                        VirtualDisplaySurfaceMode.LIVE_PREVIEW -> captureFromPixelCopy()
                }
        }

        private suspend fun captureFromImageReader(): VDScreenshotCapture? {
                val reader = imageReaderProvider() ?: return null

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
                                                Bitmap.createBitmap(
                                                                bitmap,
                                                                0,
                                                                0,
                                                                config.width,
                                                                config.height
                                                        )
                                                        .also {
                                                                if (it !== bitmap) bitmap.recycle()
                                                        }
                                        } else {
                                                bitmap
                                        }

                                screenshotProcessor.toScreenImage(cropped)
                        } finally {
                                image.close()
                        }
                }
        }

        private suspend fun captureFromPixelCopy(): VDScreenshotCapture? {
                val sv = surfaceController.liveSurfaceView()
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
                                Log.w(
                                        TAG,
                                        "PixelCopy failed $pixelCopyFailCount times, reverting to ImageReader"
                                )
                                switchToImageReader()
                        }
                        return captureFromImageReader()
                }

                pixelCopyFailCount = 0
                return withContext(Dispatchers.Default) {
                        screenshotProcessor.toScreenImage(bitmap)
                }
        }
}

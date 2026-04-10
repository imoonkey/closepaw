package com.moonkey.androidagent.platform.virtualdisplay

import android.graphics.Bitmap
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.platform.boundedCallback
import com.moonkey.androidagent.trace.A11yTreeDumper
import com.moonkey.androidagent.trace.TraceJson
import com.moonkey.androidagent.trace.TraceRecorder
import com.moonkey.androidagent.util.recycleCompat
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** Captures a11y tree and screenshots for the virtual display. */
internal class VirtualDisplayCaptureCoordinator(
        private val config: VirtualDisplayConfig,
        private val windowAccessor: VirtualDisplayWindowAccessor,
        private val imageReaderProvider: () -> ImageReader?,
        private val surfaceController: VirtualDisplaySurfaceController,
        private val switchToImageReader: () -> Unit,
        private val screenshotProcessor: VirtualDisplayScreenshotProcessor,
        private val traceRecorder: TraceRecorder
) {
        companion object {
                private const val TAG = "VDCaptureCoordinator"
                private const val PIXEL_COPY_MAX_FAILURES = 2
                private const val PIXEL_COPY_TIMEOUT_MS = 3_000L
        }

        @Volatile private var pixelCopyFailCount = 0

        fun onLivePreviewActivated() {
                pixelCopyFailCount = 0
        }

        suspend fun captureA11yTree(): List<PerceptionElement> {
                return captureA11yTreeWithArtifacts().elements
        }

        data class A11yCaptureResult(
                val elements: List<PerceptionElement>,
                val rawTreeArtifactPath: String?,
                val sanitizedTreeArtifactPath: String?
        )

        suspend fun captureA11yTreeWithArtifacts(): A11yCaptureResult {
                // Window/root collection requires Main (accessibility service IPC)
                val roots = withContext(Dispatchers.Main) {
                        windowAccessor.getRootsOnDisplay()
                }
                if (roots.isEmpty()) {
                        return A11yCaptureResult(emptyList(), null, null)
                }
                try {
                        // Perception and serialization run off Main to avoid blocking
                        // the service/viewer main thread during large-tree processing
                        return withContext(Dispatchers.Default) {
                                val rawTreePath = if (traceRecorder.enabled) {
                                        val dump = roots.map { A11yTreeDumper.dump(it) }
                                        val json = TraceJson.instance.encodeToString(dump)
                                        traceRecorder.storeText(
                                                kind = "raw_a11y_tree",
                                                filenameHint = "raw_${System.currentTimeMillis()}.json",
                                                content = json,
                                                mimeType = "application/json"
                                        )?.path
                                } else null

                                val snapshot = Perceptor.snapshot(roots, config.width, config.height)

                                val sanitizedTreePath = if (traceRecorder.enabled) {
                                        val json = Perceptor.toPromptJson(snapshot)
                                        traceRecorder.storeText(
                                                kind = "sanitized_a11y_tree",
                                                filenameHint = "sanitized_${snapshot.timestamp}.json",
                                                content = json,
                                                mimeType = "application/json"
                                        )?.path
                                } else null

                                A11yCaptureResult(
                                        elements = snapshot.elements,
                                        rawTreeArtifactPath = rawTreePath,
                                        sanitizedTreeArtifactPath = sanitizedTreePath
                                )
                        }
                } catch (e: CancellationException) {
                        throw e // Never swallow coroutine cancellation
                } catch (e: Exception) {
                        Log.w(TAG, "Perceptor.snapshot failed", e)
                        return A11yCaptureResult(emptyList(), null, null)
                } finally {
                        roots.forEach { it.recycleCompat() }
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
                                boundedCallback(
                                        timeoutMs = PIXEL_COPY_TIMEOUT_MS,
                                        label = "PixelCopy"
                                        // No onCancel: PixelCopy has no cancellation API and may still
                                        // be writing to the bitmap. Let GC reclaim it after the
                                        // framework callback fires (or is silently dropped).
                                ) { cont ->
                                        PixelCopy.request(
                                                sv,
                                                bitmap,
                                                { copyResult -> cont.resume(copyResult) },
                                                Handler(Looper.getMainLooper())
                                        )
                                }
                        }

                if (result == null || result != PixelCopy.SUCCESS) {
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

package com.moonkey.androidagent.platform.virtualdisplay

import android.media.ImageReader
import android.util.Log
import android.view.SurfaceView

enum class VirtualDisplaySurfaceMode {
        IMAGE_READER,
        LIVE_PREVIEW
}

/**
 * Manages which surface the virtual display renders to.
 *
 * Switches between ImageReader (headless capture) and SurfaceView (live preview).
 */
internal class VirtualDisplaySurfaceController(
        private val shizuku: ShizukuClient,
        private val displayIdProvider: () -> Int,
        private val imageReaderProvider: () -> ImageReader?
) {
        companion object {
                private const val TAG = "VDSurfaceController"
        }

        @Volatile private var mode = VirtualDisplaySurfaceMode.IMAGE_READER
        @Volatile private var liveSurfaceView: SurfaceView? = null

        fun mode(): VirtualDisplaySurfaceMode = mode

        fun liveSurfaceView(): SurfaceView? = liveSurfaceView

        fun reset() {
                liveSurfaceView = null
                mode = VirtualDisplaySurfaceMode.IMAGE_READER
        }

        fun switchToLivePreview(surfaceView: SurfaceView) {
                if (mode == VirtualDisplaySurfaceMode.LIVE_PREVIEW) return
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

                val ok = shizuku.setVirtualDisplaySurface(displayIdProvider(), surface)
                if (ok) {
                        liveSurfaceView = surfaceView
                        mode = VirtualDisplaySurfaceMode.LIVE_PREVIEW
                        Log.i(TAG, "Switched to live preview surface")
                } else {
                        Log.w(TAG, "setSurface failed, staying on ImageReader")
                }
        }

        fun switchToImageReader() {
                if (mode == VirtualDisplaySurfaceMode.IMAGE_READER) return
                val reader = imageReaderProvider() ?: return
                val ok = shizuku.setVirtualDisplaySurface(displayIdProvider(), reader.surface)
                if (ok) {
                        liveSurfaceView = null
                        mode = VirtualDisplaySurfaceMode.IMAGE_READER
                        Log.i(TAG, "Switched to ImageReader surface")
                } else {
                        Log.w(TAG, "Failed to switch back to ImageReader — display may be in bad state")
                }
        }
}

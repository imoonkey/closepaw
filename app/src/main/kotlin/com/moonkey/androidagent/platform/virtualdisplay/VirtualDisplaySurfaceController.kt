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

        private data class SurfaceState(
                val mode: VirtualDisplaySurfaceMode,
                val liveSurfaceView: SurfaceView?
        )

        private val stateLock = Any()
        @Volatile
        private var state = SurfaceState(mode = VirtualDisplaySurfaceMode.IMAGE_READER, liveSurfaceView = null)

        fun mode(): VirtualDisplaySurfaceMode = state.mode

        fun liveSurfaceView(): SurfaceView? = state.liveSurfaceView

        fun reset() {
                synchronized(stateLock) {
                        state =
                                SurfaceState(
                                        mode = VirtualDisplaySurfaceMode.IMAGE_READER,
                                        liveSurfaceView = null
                                )
                }
        }

        fun switchToLivePreview(surfaceView: SurfaceView) {
                synchronized(stateLock) {
                        // Allow surface replacement even when already in LIVE_PREVIEW
                        // (e.g., viewer recreated after config change or surface loss)
                        if (state.mode == VirtualDisplaySurfaceMode.LIVE_PREVIEW &&
                                state.liveSurfaceView === surfaceView) return
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
                                state =
                                        SurfaceState(
                                                mode = VirtualDisplaySurfaceMode.LIVE_PREVIEW,
                                                liveSurfaceView = surfaceView
                                        )
                                Log.i(TAG, "Switched to live preview surface")
                        } else {
                                Log.w(TAG, "setSurface failed, staying on ImageReader")
                        }
                }
        }

        fun switchToImageReader() {
                synchronized(stateLock) {
                        if (state.mode == VirtualDisplaySurfaceMode.IMAGE_READER) return
                        val reader = imageReaderProvider() ?: return
                        val ok = shizuku.setVirtualDisplaySurface(displayIdProvider(), reader.surface)
                        if (ok) {
                                state =
                                        SurfaceState(
                                                mode = VirtualDisplaySurfaceMode.IMAGE_READER,
                                                liveSurfaceView = null
                                        )
                                Log.i(TAG, "Switched to ImageReader surface")
                        } else {
                                Log.w(
                                        TAG,
                                        "Failed to switch back to ImageReader — display may be in bad state"
                                )
                        }
                }
        }
}

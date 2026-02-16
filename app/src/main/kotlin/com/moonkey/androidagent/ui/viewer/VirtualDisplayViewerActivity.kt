package com.moonkey.androidagent.ui.viewer

import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.moonkey.androidagent.app.AgentService

/**
 * VirtualDisplayViewerActivity — Full-screen live preview of the virtual display.
 *
 * Shows the VD output via a SurfaceView (GPU-direct, 60fps). The Smart Capsule
 * overlay (managed by CapsuleOverlayHost via ServiceOverlayController) renders
 * as a system overlay on top of this activity — providing all UI controls.
 *
 * This activity is a pure SurfaceView container with no built-in controls.
 * All interaction (stop, takeover, exit, minimize) is via the Smart Capsule overlay.
 *
 * Lifecycle:
 * - onStart: switch VD output to SurfaceView + notify service (show capsule, hide island)
 * - onStop: switch back to ImageReader + notify service (hide capsule, show island)
 */
class VirtualDisplayViewerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VDViewerActivity"
    }

    private var surfaceView: SurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            VirtualDisplayViewerScreen(
                onSurfaceReady = { sv ->
                    surfaceView = sv
                    AgentService.instance?.notifyViewerVisible(sv)
                },
                onSurfaceDestroyed = {
                    surfaceView = null
                    AgentService.instance?.notifyViewerHidden()
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
        surfaceView?.let { sv ->
            AgentService.instance?.notifyViewerVisible(sv)
        }
        // Notify service: show capsule overlay, hide island, set SCREEN_VIEWING context
        AgentService.instance?.onViewerOpened()
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")
        AgentService.instance?.notifyViewerHidden()
        // Notify service: hide capsule overlay, show island, set BACKGROUND context
        AgentService.instance?.onViewerClosed()
        if (!isChangingConfigurations) {
            // Keep viewer transient; returning to app should land on chat task, not a stale viewer task.
            finish()
        }
    }
}

// ── Compose Screen ──────────────────────────────────────────────

/**
 * Pure full-screen SurfaceView for VD live preview.
 * No built-in controls — Smart Capsule overlay provides all UI.
 */
@Composable
private fun VirtualDisplayViewerScreen(
    onSurfaceReady: (SurfaceView) -> Unit,
    onSurfaceDestroyed: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        LivePreviewSurface(
            onSurfaceReady = onSurfaceReady,
            onSurfaceDestroyed = onSurfaceDestroyed,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ── Components ──────────────────────────────────────────────────

@Composable
private fun LivePreviewSurface(
    onSurfaceReady: (SurfaceView) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        Log.d("VDViewerSurface", "Surface created")
                        onSurfaceReady(this@apply)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        Log.d("VDViewerSurface", "Surface destroyed")
                        onSurfaceDestroyed()
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, width: Int, height: Int
                    ) {
                        Log.d("VDViewerSurface", "Surface changed: ${width}x$height")
                    }
                })
            }
        },
        modifier = modifier
    )
}

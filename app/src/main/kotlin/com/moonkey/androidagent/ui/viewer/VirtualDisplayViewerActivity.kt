package com.moonkey.androidagent.ui.viewer

import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.moonkey.androidagent.app.AgentService
import kotlinx.coroutines.delay

/**
 * VirtualDisplayViewerActivity — Full-screen live preview of the virtual display.
 *
 * Shows the VD output via a SurfaceView (GPU-direct, 60fps). Compose overlays
 * (capsule, exit hint) render on top of the preview inside this Activity — they
 * are NOT system WindowManager overlays, so they can't leak to the real screen.
 *
 * Lifecycle:
 * - onStart: tell VirtualDisplayPlatform to switch surface to our SurfaceView
 * - onStop: switch back to ImageReader for headless capture
 */
class VirtualDisplayViewerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VDViewerActivity"
        /** Swipe distance (dp) to trigger dismiss. */
        internal const val SWIPE_DISMISS_THRESHOLD_DP = 120f
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
                    // Notify platform if we're already started
                    AgentService.instance?.notifyViewerVisible(sv)
                },
                onSurfaceDestroyed = {
                    surfaceView = null
                    AgentService.instance?.notifyViewerHidden()
                },
                onDismiss = { finish() }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
        surfaceView?.let { sv ->
            AgentService.instance?.notifyViewerVisible(sv)
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")
        AgentService.instance?.notifyViewerHidden()
    }
}

// ── Compose Screen ──────────────────────────────────────────────

@Composable
private fun VirtualDisplayViewerScreen(
    onSurfaceReady: (SurfaceView) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onDismiss: () -> Unit
) {
    var showHint by remember { mutableStateOf(true) }

    // Auto-hide exit hint after 2 seconds
    LaunchedEffect(Unit) {
        delay(2000)
        showHint = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. SurfaceView — live VD preview, 60fps GPU-direct
        LivePreviewSurface(
            onSurfaceReady = onSurfaceReady,
            onSurfaceDestroyed = onSurfaceDestroyed,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Swipe-up exit gesture overlay (transparent, captures vertical drags)
        SwipeUpDismissOverlay(
            onDismiss = onDismiss,
            modifier = Modifier.fillMaxSize()
        )

        // 3. Bottom capsule with controls
        ViewerCapsule(
            onDismiss = onDismiss,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )

        // 4. "Swipe up to exit" hint
        AnimatedVisibility(
            visible = showHint,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "Swipe up to exit",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
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

@Composable
private fun SwipeUpDismissOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val thresholdPx = VirtualDisplayViewerActivity.SWIPE_DISMISS_THRESHOLD_DP * density

    Box(
        modifier = modifier.pointerInput(Unit) {
            var totalDrag = 0f
            var dismissed = false
            detectVerticalDragGestures(
                onDragStart = {
                    totalDrag = 0f
                    dismissed = false
                },
                onVerticalDrag = { _, dragAmount ->
                    totalDrag += dragAmount
                    if (totalDrag < -thresholdPx && !dismissed) {
                        dismissed = true
                        onDismiss()
                    }
                }
            )
        }
    )
}

@Composable
private fun ViewerCapsule(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.White.copy(alpha = 0.9f),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 8.dp,
        modifier = modifier
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Close / exit button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(36.dp)
                    .background(Color(0xFFF5F5F5), CircleShape)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Exit viewer",
                    tint = Color(0xFF171717),
                    modifier = Modifier.size(18.dp)
                )
            }

            Text(
                text = "Live Preview",
                color = Color(0xFF171717),
                fontSize = 14.sp
            )
        }
    }
}

package ai.closepaw.ui.overlay.compose

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class VisualizerOverlayHost(
    private val service: AccessibilityService,
    lifecycleOwner: LifecycleOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner,
) {
    companion object {
        private const val TAG = "VisualizerOverlayHost"
        private const val CLICK_DURATION_MS = 500L
        private const val SWIPE_EXTRA_DURATION_MS = 400L
        private const val EDGE_PADDING = 10f
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val composeHost = OverlayComposeHost(
        context = service,
        lifecycleOwner = lifecycleOwner,
        savedStateRegistryOwner = savedStateRegistryOwner,
        windowManager = windowManager,
        tag = TAG,
    )
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val items = MutableStateFlow<List<VisualizationItem>>(emptyList())
    private val nextId = AtomicLong(1)

    private var screenWidth = 0
    private var screenHeight = 0
    private var disposed = false

    fun showClick(x: Float, y: Float, longPress: Boolean) {
        if (disposed) return
        ensureOverlay()
        val item = VisualizationItem.Click(
            id = nextId.getAndIncrement(),
            createdAtMs = SystemClock.uptimeMillis(),
            durationMs = CLICK_DURATION_MS,
            x = clampX(x),
            y = clampY(y),
            longPress = longPress,
        )
        addItem(item)
    }

    fun showSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        scroll: Boolean,
    ) {
        if (disposed) return
        ensureOverlay()
        val item = VisualizationItem.Swipe(
            id = nextId.getAndIncrement(),
            createdAtMs = SystemClock.uptimeMillis(),
            durationMs = durationMs + SWIPE_EXTRA_DURATION_MS,
            startX = clampX(startX),
            startY = clampY(startY),
            endX = clampX(endX),
            endY = clampY(endY),
            scroll = scroll,
        )
        addItem(item)
    }

    fun dispose() {
        disposed = true
        items.value = emptyList()
        composeHost.hide()
        composeHost.dispose()
        scope.cancel()
    }

    private fun ensureOverlay() {
        if (composeHost.isShowing()) return
        updateScreenDimensions()
        composeHost.show(createLayoutParams()) {
            val renderItems by items.collectAsState(initial = emptyList())
            ActionVisualizerCompose(items = renderItems)
        }
        Log.d(TAG, "Visualizer overlay shown")
    }

    private fun addItem(item: VisualizationItem) {
        items.update { it + item }
        scope.launch {
            delay(item.durationMs)
            items.update { list -> list.filterNot { it.id == item.id } }
        }
    }

    private fun updateScreenDimensions() {
        val metrics = service.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    private fun clampX(x: Float): Float {
        if (screenWidth <= 0) return x
        return x.coerceIn(EDGE_PADDING, screenWidth - EDGE_PADDING)
    }

    private fun clampY(y: Float): Float {
        if (screenHeight <= 0) return y
        return y.coerceIn(EDGE_PADDING, screenHeight - EDGE_PADDING)
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
    }
}

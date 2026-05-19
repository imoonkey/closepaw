package ai.closepaw.ui.overlay.visualizer

import android.accessibilityservice.AccessibilityService
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import ai.closepaw.ui.overlay.compose.VisualizerOverlayHost
import ai.closepaw.ui.overlay.model.CapsuleContext
import kotlinx.coroutines.flow.StateFlow

/**
 * Compose-backed touch action visualizer.
 */
class ActionVisualizerManager(
    context: AccessibilityService,
    lifecycleOwner: LifecycleOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner,
    private val renderContext: StateFlow<CapsuleContext>,
) {
    private val overlayHost = VisualizerOverlayHost(
        service = context,
        lifecycleOwner = lifecycleOwner,
        savedStateRegistryOwner = savedStateRegistryOwner,
    )

    @Volatile
    var enabled: Boolean = true

    fun showClick(x: Float, y: Float, longPress: Boolean = false) {
        if (!shouldRender()) return
        overlayHost.showClick(x = x, y = y, longPress = longPress)
    }

    fun showSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ) {
        if (!shouldRender()) return
        overlayHost.showSwipe(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = durationMs,
            scroll = false,
        )
    }

    fun showScrollAsSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ) {
        if (!shouldRender()) return
        overlayHost.showSwipe(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = durationMs,
            scroll = true,
        )
    }

    fun dispose() {
        overlayHost.dispose()
    }

    private fun shouldRender(): Boolean =
        enabled && renderContext.value == CapsuleContext.SCREEN_VIEWING
}

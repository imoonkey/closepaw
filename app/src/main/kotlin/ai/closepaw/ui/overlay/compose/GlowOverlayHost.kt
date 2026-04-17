package ai.closepaw.ui.overlay.compose

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import ai.closepaw.ui.overlay.model.GlowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class GlowOverlayHost(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    lifecycleOwner: LifecycleOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner,
) {
    companion object {
        private const val TAG = "GlowOverlayHost"
        private const val PULSE_DURATION_MS = 800
        private const val FADE_OUT_DURATION_MS = 500L
        private const val SUCCESS_HIDE_DELAY_MS = 2000L
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val composeHost = OverlayComposeHost(
        context = service,
        lifecycleOwner = lifecycleOwner,
        savedStateRegistryOwner = savedStateRegistryOwner,
        windowManager = windowManager,
        tag = TAG,
    )

    private val isVisible = MutableStateFlow(false)
    private val glowState = MutableStateFlow(GlowState.Active)
    private var pendingHideJob: Job? = null
    private var pendingRemoveJob: Job? = null

    fun isShowing(): Boolean = composeHost.isShowing() && isVisible.value

    fun show(state: GlowState = GlowState.Active) {
        pendingHideJob?.cancel()
        pendingRemoveJob?.cancel()
        glowState.value = state

        if (!composeHost.isShowing()) {
            composeHost.show(createLayoutParams()) {
                val visible by isVisible.collectAsState(initial = false)
                val currentState by glowState.collectAsState(initial = GlowState.Active)
                val pulseTransition = rememberInfiniteTransition(label = "glowPulse")
                val pulseAlpha by pulseTransition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 0.85f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(PULSE_DURATION_MS),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "pulseAlpha",
                )
                val staticAlpha by rememberUpdatedState(0.7f)
                val alpha = when (currentState) {
                    GlowState.Active, GlowState.Executing -> pulseAlpha
                    else -> staticAlpha
                }

                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(FADE_OUT_DURATION_MS.toInt())),
                ) {
                    EdgeGlowCompose(
                        state = currentState,
                        alpha = alpha,
                    )
                }
            }
            Log.i(TAG, "Glow overlay shown")
        }

        isVisible.value = true
        if (state == GlowState.Success) {
            scheduleHideAfterDelay(SUCCESS_HIDE_DELAY_MS)
        }
    }

    fun hide() {
        if (!composeHost.isShowing()) return
        pendingHideJob?.cancel()
        isVisible.value = false
        pendingRemoveJob?.cancel()
        pendingRemoveJob = scope.launch {
            delay(FADE_OUT_DURATION_MS)
            composeHost.hide()
        }
    }

    fun hideImmediately() {
        pendingHideJob?.cancel()
        pendingRemoveJob?.cancel()
        isVisible.value = false
        composeHost.hide()
    }

    fun updateState(state: GlowState) {
        if (!composeHost.isShowing()) {
            Log.w(TAG, "updateState called while overlay hidden")
            return
        }
        glowState.value = state
        pendingHideJob?.cancel()
        if (state == GlowState.Success) {
            scheduleHideAfterDelay(SUCCESS_HIDE_DELAY_MS)
        }
    }

    fun dispose() {
        hideImmediately()
        composeHost.dispose()
    }

    private fun scheduleHideAfterDelay(delayMs: Long) {
        pendingHideJob?.cancel()
        pendingHideJob = scope.launch {
            delay(delayMs)
            hide()
        }
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}

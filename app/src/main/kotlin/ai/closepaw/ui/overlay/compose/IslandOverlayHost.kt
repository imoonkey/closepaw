package ai.closepaw.ui.overlay.compose

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import ai.closepaw.protocol.TurnPhase
import ai.closepaw.ui.capsule.surface.toStatusColor
import ai.closepaw.ui.overlay.CapsuleStateHolder
import ai.closepaw.ui.overlay.model.CapsuleMode
import ai.closepaw.ui.overlay.model.deriveGlowState
import kotlinx.coroutines.CoroutineScope

class IslandOverlayHost(
    private val service: AccessibilityService,
    lifecycleOwner: LifecycleOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner,
    private val onExpandCapsule: () -> Unit,
) {
    companion object {
        private const val TAG = "IslandOverlayHost"
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val composeHost = OverlayComposeHost(
        context = service,
        lifecycleOwner = lifecycleOwner,
        savedStateRegistryOwner = savedStateRegistryOwner,
        windowManager = windowManager,
        tag = TAG,
    )

    private var stateHolder: CapsuleStateHolder? = null

    fun show() {
        if (composeHost.isShowing()) return
        composeHost.show(createLayoutParams()) {
            val holder = stateHolder
            if (holder == null) {
                StatusIslandCompose(
                    text = "Working...",
                    dotColor = MaterialTheme.colorScheme.primary,
                    onClick = onExpandCapsule,
                )
            } else {
                val mode by holder.mode.collectAsState(initial = CapsuleMode.Hidden)
                val turnPhase by holder.turnPhase.collectAsState(initial = null)
                val text = modeText(mode)
                val glowState = deriveGlowState(mode, turnPhase)
                StatusIslandCompose(
                    text = if (text.isBlank()) "Working..." else text,
                    dotColor = glowState.toStatusColor(),
                    onClick = onExpandCapsule,
                )
            }
        }
        Log.i(TAG, "Status island shown")
    }

    fun hide() {
        composeHost.hide()
    }

    fun isShowing(): Boolean = composeHost.isShowing()

    fun startObserving(stateHolder: CapsuleStateHolder, @Suppress("UNUSED_PARAMETER") scope: CoroutineScope) {
        this.stateHolder = stateHolder
    }

    fun dispose() {
        hide()
        composeHost.dispose()
    }

    private fun modeText(mode: CapsuleMode): String = when (mode) {
        is CapsuleMode.Running -> mode.thought.take(24)
        is CapsuleMode.TakeoverPending -> "Handing over..."
        is CapsuleMode.Takeover -> "Paused"
        is CapsuleMode.WaitingForInput -> "Awaiting response"
        is CapsuleMode.WaitingForAction -> "Action needed"
        is CapsuleMode.WaitingForApproval -> "Approve action?"
        is CapsuleMode.Done -> "Done: ${mode.message.take(18)}"
        is CapsuleMode.Error -> "Error: ${mode.message.take(18)}"
        is CapsuleMode.Hidden -> ""
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val statusBarHeight = getStatusBarHeight()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight + dp(4)
        }
    }

    private fun getStatusBarHeight(): Int {
        val resId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) service.resources.getDimensionPixelSize(resId) else dp(24)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            service.resources.displayMetrics
        ).toInt()
    }
}

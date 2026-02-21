package com.moonkey.androidagent.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.ViewConfiguration
import com.moonkey.androidagent.ui.overlay.visualizer.ActionVisualizerManager
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Accessibility gesture/global-action executor.
 *
 * Encapsulates gesture construction + dispatch callback handling so
 * AccessibilityPlatform can stay as an orchestrator.
 */
class AccessibilityGestureInjector(
        private val service: AccessibilityService,
        private val visualizer: ActionVisualizerManager? = null
) {
    companion object {
        private const val TAG = "AccessibilityGestureInjector"
        private const val GESTURE_TIMEOUT_MS = 5000L
    }

    suspend fun injectTap(x: Int, y: Int): ActionResult {
        return withContext(Dispatchers.Main) {
            visualizer?.showClick(x.toFloat(), y.toFloat())

            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val tapDuration = ViewConfiguration.getTapTimeout().toLong()
            val stroke = GestureDescription.StrokeDescription(path, 0, tapDuration)
            val gesture = buildGesture(stroke)

            dispatchGesture(gesture)
        }
    }

    suspend fun injectSwipe(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long
    ): ActionResult {
        return withContext(Dispatchers.Main) {
            visualizer?.showSwipe(startX, startY, endX, endY, durationMs)

            val path =
                    Path().apply {
                        moveTo(startX, startY)
                        lineTo(endX, endY)
                    }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = buildGesture(stroke)
            dispatchGesture(gesture)
        }
    }

    suspend fun injectLongPress(x: Float, y: Float, durationMs: Long): ActionResult {
        return withContext(Dispatchers.Main) {
            visualizer?.showClick(x, y, longPress = true)

            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = buildGesture(stroke)
            dispatchGesture(gesture)
        }
    }

    suspend fun injectSystemButton(button: SystemButtonType): ActionResult {
        return withContext(Dispatchers.Main) {
            val globalAction =
                    when (button) {
                        SystemButtonType.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                        SystemButtonType.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                        SystemButtonType.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
                        SystemButtonType.ENTER ->
                                return@withContext ActionResult.Failure(
                                        "ENTER should be handled by NodeActionPerformer"
                                )
                    }

            val result = service.performGlobalAction(globalAction)
            if (result) {
                ActionResult.Success("System button: $button")
            } else {
                ActionResult.Failure("Failed to perform system action: $button")
            }
        }
    }

    private suspend fun dispatchGesture(gesture: GestureDescription): ActionResult {
        return withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val callback =
                        object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                Log.d(TAG, "dispatchGesture completed")
                                if (continuation.isActive) {
                                    continuation.resume(ActionResult.Success("Gesture completed"))
                                }
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                Log.w(TAG, "dispatchGesture cancelled")
                                if (continuation.isActive) {
                                    continuation.resume(ActionResult.Cancelled("Gesture cancelled"))
                                }
                            }
                        }

                val dispatched = service.dispatchGesture(gesture, callback, null)
                Log.d(TAG, "dispatchGesture dispatched=$dispatched, ${describeGesture(gesture)}")
                if (!dispatched && continuation.isActive) {
                    continuation.resume(ActionResult.Failure("Failed to dispatch gesture"))
                }
            }
        }
                ?: run {
                    Log.w(TAG, "Gesture timed out after ${GESTURE_TIMEOUT_MS}ms")
                    ActionResult.Failure("Gesture timed out after ${GESTURE_TIMEOUT_MS}ms")
                }
    }

    private fun buildGesture(stroke: GestureDescription.StrokeDescription): GestureDescription {
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    private fun gestureDisplayId(gesture: GestureDescription?): Int {
        if (gesture == null) return Display.DEFAULT_DISPLAY
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Display.DEFAULT_DISPLAY
        return gesture.displayId
    }

    private fun describeGesture(gesture: GestureDescription): String {
        val parts = mutableListOf<String>()
        val pos = FloatArray(2)
        val pathMeasure = PathMeasure()
        for (i in 0 until gesture.strokeCount) {
            val stroke = gesture.getStroke(i)
            val path = stroke.path
            pathMeasure.setPath(path, false)
            val length = pathMeasure.length
            val hasStart = pathMeasure.getPosTan(0f, pos, null)
            val startX = if (hasStart) pos[0] else Float.NaN
            val startY = if (hasStart) pos[1] else Float.NaN
            val hasEnd = pathMeasure.getPosTan(length, pos, null)
            val endX = if (hasEnd) pos[0] else Float.NaN
            val endY = if (hasEnd) pos[1] else Float.NaN
            parts +=
                    "#$i(start=${stroke.startTime},dur=${stroke.duration},len=${"%.1f".format(length)}," +
                            "from=${"%.1f".format(startX)},${"%.1f".format(startY)}," +
                            "to=${"%.1f".format(endX)},${"%.1f".format(endY)})"
        }
        return parts.joinToString(";")
    }
}

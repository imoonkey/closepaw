package com.moonkey.androidagent.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
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
        private const val DEFAULT_GESTURE_DURATION_MS = 100L
        private const val GESTURE_TIMEOUT_MS = 5000L
    }

    suspend fun injectTap(x: Int, y: Int): ActionResult {
        return withContext(Dispatchers.Main) {
            visualizer?.showClick(x.toFloat(), y.toFloat())

            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture =
                    GestureDescription.Builder()
                            .addStroke(
                                    GestureDescription.StrokeDescription(
                                            path,
                                            0,
                                            DEFAULT_GESTURE_DURATION_MS
                                    )
                            )
                            .build()

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
            val gesture =
                    GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                            .build()
            dispatchGesture(gesture)
        }
    }

    suspend fun injectLongPress(x: Float, y: Float, durationMs: Long): ActionResult {
        return withContext(Dispatchers.Main) {
            visualizer?.showClick(x, y, longPress = true)

            val path = Path().apply { moveTo(x, y) }
            val gesture =
                    GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                            .build()
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
                                if (continuation.isActive) {
                                    continuation.resume(ActionResult.Success("Gesture completed"))
                                }
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) {
                                    continuation.resume(ActionResult.Cancelled("Gesture cancelled"))
                                }
                            }
                        }

                val dispatched = service.dispatchGesture(gesture, callback, null)
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
}

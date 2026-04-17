package ai.closepaw.platform.virtualdisplay

import android.view.Display
import android.view.MotionEvent

/**
 * Handles touch forwarding from VirtualDisplayViewerActivity into the virtual display.
 *
 * Primary path uses raw MotionEvent injection with display targeting. Fallback path uses shell
 * input commands when hidden display-id injection is unavailable.
 */
class VirtualDisplayViewerTouchHandler(
        private val config: VirtualDisplayConfig,
        private val displayIdProvider: () -> Int,
        private val inputInjector: VirtualDisplayInputInjector,
        private val shizuku: ShizukuClient
) {
        companion object {
                private const val VIEWER_SWIPE_THRESHOLD_PX = 18f
        }

        private var viewerDownX = 0f
        private var viewerDownY = 0f
        private var viewerDownTime = 0L
        private var viewerMoved = false

        fun onViewerTouch(
                action: Int,
                x: Float,
                y: Float,
                downTime: Long,
                eventTime: Long,
                viewWidth: Int,
                viewHeight: Int
        ): Boolean {
                val displayId = displayIdProvider()
                if (displayId == Display.INVALID_DISPLAY || viewWidth <= 0 || viewHeight <= 0) {
                        return false
                }

                val targetX =
                        ((x / viewWidth) * config.width).coerceIn(0f, (config.width - 1).toFloat())
                val targetY =
                        ((y / viewHeight) * config.height)
                                .coerceIn(0f, (config.height - 1).toFloat())

                val actionMasked = action and MotionEvent.ACTION_MASK
                if (inputInjector.supportsDisplayIdInjection()) {
                        return inputInjector.injectMotionAction(
                                action = actionMasked,
                                x = targetX,
                                y = targetY,
                                downTime = downTime,
                                eventTime = eventTime
                        )
                }

                return injectViaShell(
                        action = actionMasked,
                        x = targetX,
                        y = targetY,
                        eventTime = eventTime,
                        displayId = displayId
                )
        }

        private fun injectViaShell(
                action: Int,
                x: Float,
                y: Float,
                eventTime: Long,
                displayId: Int
        ): Boolean {
                when (action) {
                        MotionEvent.ACTION_DOWN -> {
                                viewerDownX = x
                                viewerDownY = y
                                viewerDownTime = eventTime
                                viewerMoved = false
                                return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                                if (!viewerMoved) {
                                        val dx = x - viewerDownX
                                        val dy = y - viewerDownY
                                        if (dx * dx + dy * dy >=
                                                        VIEWER_SWIPE_THRESHOLD_PX *
                                                                VIEWER_SWIPE_THRESHOLD_PX
                                        ) {
                                                viewerMoved = true
                                        }
                                }
                                return true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                                viewerMoved = false
                                viewerDownTime = 0L
                                return true
                        }
                        MotionEvent.ACTION_UP -> {
                                val durationMs = (eventTime - viewerDownTime).coerceIn(16L, 1500L)
                                val command =
                                        if (viewerMoved) {
                                                arrayOf(
                                                        "input",
                                                        "-d",
                                                        "$displayId",
                                                        "swipe",
                                                        "${viewerDownX.toInt()}",
                                                        "${viewerDownY.toInt()}",
                                                        "${x.toInt()}",
                                                        "${y.toInt()}",
                                                        "$durationMs"
                                                )
                                        } else {
                                                arrayOf(
                                                        "input",
                                                        "-d",
                                                        "$displayId",
                                                        "tap",
                                                        "${x.toInt()}",
                                                        "${y.toInt()}"
                                                )
                                        }
                                viewerMoved = false
                                viewerDownTime = 0L
                                return shizuku.executeShellCommand(command) == 0
                        }
                        else -> return false
                }
        }
}

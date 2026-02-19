package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.UIAction
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Scroll executor: content-direction scroll with optional element targeting.
 *
 * Pipeline:
 * 1. Determine scroll area (element bounds or full screen)
 * 2. Try accessibility scroll action (ACTION_SCROLL_DOWN etc.)
 * 3. If a11y fails, fall back to gesture swipe (center-to-edge within scroll area)
 * 4. Capture post-action screen, detect scroll boundary
 */
class ScrollExecutor {
    companion object {
        private const val UI_SETTLE_DELAY_MS = 400L
        private const val SWIPE_DURATION_MS = 300L
        private const val EDGE_INSET_RATIO = 0.1f
    }

    suspend fun execute(
        params: JSONObject,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform,
        isCancelled: () -> Boolean
    ): ActionOutcome {
        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before scroll")

        val direction = params.optString("direction", "").trim().lowercase()
        if (direction !in listOf("up", "down", "left", "right")) {
            return ActionOutcome.Failed(
                reason = "Invalid scroll direction: '$direction'. Must be up/down/left/right.",
                attemptTrail = emptyList()
            )
        }

        val scrollArea = resolveScrollArea(params, snapshot, platform)
        val centerX = scrollArea.centerX
        val centerY = scrollArea.centerY
        val attemptTrail = mutableListOf<String>()

        // 1. Try accessibility scroll action
        val a11yResult = platform.performAction(UIAction.ScrollNodeAt(centerX, centerY, direction))
        when (a11yResult) {
            is ActionResult.Success -> {
                attemptTrail += "a11y_scroll: success"
                delay(UI_SETTLE_DELAY_MS)
                val post = runCatching { platform.captureScreen() }.getOrNull()
                val observation = post?.let { buildObservation(it, platform) }
                val boundary = UiChangeDetector.detectScrollBoundary(snapshot, post)
                val message = buildMessage("Scrolled $direction via a11y action", boundary)
                return ActionOutcome.Success(
                    message = message,
                    observation = observation,
                    attemptTrail = attemptTrail,
                    verified = true
                )
            }
            is ActionResult.Cancelled -> return ActionOutcome.Cancelled(a11yResult.reason)
            is ActionResult.Failure -> {
                attemptTrail += "a11y_scroll: ${a11yResult.reason}"
            }
        }

        // 2. Gesture fallback: center-to-edge swipe within scroll area
        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before gesture fallback")
        val swipe = computeGestureFallback(direction, scrollArea)
        val gestureResult = platform.performAction(swipe)
        when (gestureResult) {
            is ActionResult.Success -> {
                attemptTrail += "gesture_swipe: success"
                delay(UI_SETTLE_DELAY_MS)
                val post = runCatching { platform.captureScreen() }.getOrNull()
                val observation = post?.let { buildObservation(it, platform) }
                val boundary = UiChangeDetector.detectScrollBoundary(snapshot, post)
                val message = buildMessage("Scrolled $direction via gesture", boundary)
                return ActionOutcome.Success(
                    message = message,
                    observation = observation,
                    attemptTrail = attemptTrail,
                    verified = true
                )
            }
            is ActionResult.Cancelled -> return ActionOutcome.Cancelled(gestureResult.reason)
            is ActionResult.Failure -> {
                attemptTrail += "gesture_swipe: ${gestureResult.reason}"
                return ActionOutcome.Failed(
                    reason = "Scroll $direction failed: ${gestureResult.reason}",
                    attemptTrail = attemptTrail
                )
            }
        }
    }

    private fun resolveScrollArea(
        params: JSONObject,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform
    ): Bounds {
        if (params.has("element_index") && snapshot != null) {
            val idx = params.getInt("element_index")
            val element = snapshot.elements.getOrNull(idx)
            if (element != null && element.bounds.width > 0 && element.bounds.height > 0) {
                return element.bounds
            }
        }
        val display = platform.getDisplayInfo()
        return Bounds(0, 0, display.widthPixels, display.heightPixels)
    }

    /**
     * Compute center-to-edge swipe gesture within the scroll area.
     *
     * Direction is content direction:
     * - "down" (reveal below) → finger swipes UP → start=center, end=top
     * - "up" (reveal above) → finger swipes DOWN → start=center, end=bottom
     * - "left" (reveal left) → finger swipes RIGHT → start=center, end=right
     * - "right" (reveal right) → finger swipes LEFT → start=center, end=left
     */
    private fun computeGestureFallback(direction: String, area: Bounds): UIAction.Swipe {
        val cx = area.centerX
        val cy = area.centerY
        val insetX = (area.width * EDGE_INSET_RATIO).toInt().coerceAtLeast(1)
        val insetY = (area.height * EDGE_INSET_RATIO).toInt().coerceAtLeast(1)

        val (endX, endY) = when (direction) {
            "down" -> cx to (area.top + insetY)       // finger up
            "up" -> cx to (area.bottom - insetY)      // finger down
            "left" -> (area.right - insetX) to cy     // finger right
            "right" -> (area.left + insetX) to cy     // finger left
            else -> cx to cy
        }
        return UIAction.Swipe(cx, cy, endX, endY, SWIPE_DURATION_MS)
    }

    private fun buildMessage(base: String, boundary: String?): String {
        return if (boundary != null) "$base. $boundary" else base
    }
}

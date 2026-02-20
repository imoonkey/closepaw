package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.UIAction
import kotlinx.coroutines.delay

/**
 * Scroll executor: content-direction scroll with optional element targeting.
 *
 * Cascade: gesture swipe first → a11y scroll fallback (matching click/long_press pattern).
 * Uses TargetResolver for element resolution (unified targeting).
 */
class ScrollExecutor(
    private val targetResolver: TargetResolver = TargetResolver
) {
    companion object {
        private const val UI_SETTLE_DELAY_MS = 400L
        private const val SWIPE_DURATION_MS = 300L
        private const val EDGE_INSET_RATIO = 0.1f
    }

    suspend fun execute(
        target: Target?,
        direction: String,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform,
        isCancelled: () -> Boolean
    ): ActionOutcome {
        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before scroll")

        val scrollArea = resolveScrollArea(target, snapshot, platform)
        val attemptTrail = mutableListOf<String>()

        // 1. Primary: gesture swipe within scroll area
        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before gesture scroll")
        val swipe = computeSwipeGesture(direction, scrollArea)
        val gestureResult = platform.performAction(swipe)
        when (gestureResult) {
            is ActionResult.Success -> {
                attemptTrail += "gesture_swipe: success"
                return buildSuccessOutcome(direction, "gesture_swipe", attemptTrail, platform)
            }
            is ActionResult.Cancelled -> return ActionOutcome.Cancelled(gestureResult.reason)
            is ActionResult.Failure -> {
                attemptTrail += "gesture_swipe: ${gestureResult.reason}"
            }
        }

        // 2. Fallback: a11y scroll action
        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before a11y scroll fallback")
        val a11yResult = platform.performAction(
            UIAction.ScrollNodeAt(scrollArea.centerX, scrollArea.centerY, direction)
        )
        when (a11yResult) {
            is ActionResult.Success -> {
                attemptTrail += "a11y_scroll: success"
                return buildSuccessOutcome(direction, "a11y_scroll", attemptTrail, platform)
            }
            is ActionResult.Cancelled -> return ActionOutcome.Cancelled(a11yResult.reason)
            is ActionResult.Failure -> {
                attemptTrail += "a11y_scroll: ${a11yResult.reason}"
                return ActionOutcome.Failed(
                    reason = "Scroll $direction failed: ${a11yResult.reason}",
                    attemptTrail = attemptTrail
                )
            }
        }
    }

    private fun resolveScrollArea(
        target: Target?,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform
    ): Bounds {
        if (target != null) {
            val resolved = targetResolver.resolve(target, snapshot)
            if (resolved is TargetResolver.ResolveResult.Resolved) {
                val bounds = resolved.bounds
                if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                    return bounds
                }
            }
        }
        val display = platform.getDisplayInfo()
        return Bounds(0, 0, display.widthPixels, display.heightPixels)
    }

    private suspend fun buildSuccessOutcome(
        direction: String,
        channel: String,
        attemptTrail: List<String>,
        platform: AndroidPlatform
    ): ActionOutcome.Success {
        delay(UI_SETTLE_DELAY_MS)
        val post = runCatching { platform.captureScreen() }.getOrNull()
        val observation = post?.let { buildObservation(it, platform) }
        return ActionOutcome.Success(
            message = "Scrolled $direction via $channel",
            observation = observation,
            attemptTrail = attemptTrail,
            verified = true
        )
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
    private fun computeSwipeGesture(direction: String, area: Bounds): UIAction.Swipe {
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
}

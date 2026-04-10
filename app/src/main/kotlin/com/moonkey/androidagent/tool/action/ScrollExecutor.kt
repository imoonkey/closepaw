package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.AppClassifier
/**
 * Scroll executor: content-direction scroll with optional element targeting.
 *
 * Cascade: a11y scroll first → gesture swipe fallback (matching click/long_press pattern).
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
        isCancelled: () -> Boolean,
        appClassifier: AppClassifier? = null
    ): ActionOutcome {
        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before scroll")

        val scrollArea = resolveScrollArea(target, snapshot, platform)
        val attemptTrail = mutableListOf<String>()

        for (channel in ActionPriorityOrder.scroll) {
            if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before scroll attempt")
            when (channel) {
                ActionPriorityOrder.ScrollChannel.GESTURE_SWIPE -> {
                    val swipe = computeSwipeGesture(direction, scrollArea)
                    val gestureResult = platform.performAction(swipe)
                    when (gestureResult) {
                        is ActionResult.Success -> {
                            val outcome = buildSuccessOutcome(
                                direction = direction,
                                channel = "gesture_swipe",
                                attemptTrail = attemptTrail,
                                preSnapshot = snapshot,
                                platform = platform,
                                appClassifier = appClassifier
                            )
                            when (outcome) {
                                is ActionOutcome.Success -> {
                                    attemptTrail += "gesture_swipe: success"
                                    return outcome.copy(attemptTrail = attemptTrail.toList())
                                }
                                is ActionOutcome.Failed -> {
                                    attemptTrail += "gesture_swipe: ${outcome.reason}"
                                }
                                is ActionOutcome.Cancelled -> return outcome
                            }
                        }
                        is ActionResult.Cancelled -> return ActionOutcome.Cancelled(gestureResult.reason)
                        is ActionResult.Failure -> {
                            attemptTrail += "gesture_swipe: ${gestureResult.reason}"
                        }
                    }
                }
                ActionPriorityOrder.ScrollChannel.A11Y_SCROLL -> {
                    val a11yResult = platform.performAction(
                        UIAction.ScrollNodeAt(scrollArea.centerX, scrollArea.centerY, direction)
                    )
                    when (a11yResult) {
                        is ActionResult.Success -> {
                            val outcome = buildSuccessOutcome(
                                direction = direction,
                                channel = "a11y_scroll",
                                attemptTrail = attemptTrail,
                                preSnapshot = snapshot,
                                platform = platform,
                                appClassifier = appClassifier
                            )
                            when (outcome) {
                                is ActionOutcome.Success -> {
                                    attemptTrail += "a11y_scroll: success"
                                    return outcome.copy(attemptTrail = attemptTrail.toList())
                                }
                                is ActionOutcome.Failed -> {
                                    attemptTrail += "a11y_scroll: ${outcome.reason}"
                                }
                                is ActionOutcome.Cancelled -> return outcome
                            }
                        }
                        is ActionResult.Cancelled -> return ActionOutcome.Cancelled(a11yResult.reason)
                        is ActionResult.Failure -> {
                            attemptTrail += "a11y_scroll: ${a11yResult.reason}"
                        }
                    }
                }
            }
        }

        val noEffect = attemptTrail.any { it.contains("no observable effect") }
        return ActionOutcome.Failed(
            reason = if (noEffect) {
                "Scroll $direction had no observable effect after all channels"
            } else {
                "Scroll $direction failed after all channels"
            },
            attemptTrail = attemptTrail
        )
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
        preSnapshot: ScreenSnapshot?,
        platform: AndroidPlatform,
        appClassifier: AppClassifier? = null
    ): ActionOutcome {
        val analysis = capturePostActionAnalysis(
            preSnapshot = preSnapshot,
            platform = platform,
            settleDelayMs = UI_SETTLE_DELAY_MS,
            appClassifier = appClassifier
        )
        if (analysis.changeResult == UiChangeDetector.ChangeResult.Unchanged) {
            return ActionOutcome.Failed(
                reason = "Scroll $direction via $channel had no observable effect",
                attemptTrail = attemptTrail
            )
        }
        return ActionOutcome.Success(
            message = formatActionMessage("Scrolled $direction via $channel", analysis.warnings),
            observation = analysis.observation,
            attemptTrail = attemptTrail,
            verified = analysis.changeResult == UiChangeDetector.ChangeResult.Changed
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

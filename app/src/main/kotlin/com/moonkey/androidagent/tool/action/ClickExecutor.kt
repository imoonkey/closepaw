package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.UIAction
import kotlinx.coroutines.delay

/**
 * Click executor: resolve target once.
 *
 * Primary path for semantic targets: node ACTION_CLICK (a11y tree dependent).
 * Fallback: gesture tap (works on any visible element).
 * Coordinate targets: gesture tap only (node_click skipped).
 */
class ClickExecutor(
    private val targetResolver: TargetResolver = TargetResolver
) {
    companion object {
        private const val UI_SETTLE_DELAY_MS = 300L
    }

    suspend fun execute(
        target: Target,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform,
        isCancelled: () -> Boolean
    ): ActionOutcome {
        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before click")
        val displayInfo = platform.getDisplayInfo()
        val resolvedTarget = targetResolver.resolve(target, snapshot)
        val resolvedWarnings: List<String>
        val point = when (resolvedTarget) {
            is TargetResolver.ResolveResult.Resolved -> {
                resolvedWarnings = resolvedTarget.warnings
                resolvedTarget.point
            }
            is TargetResolver.ResolveResult.NotFound -> {
                return ActionOutcome.Failed(
                    reason = resolvedTarget.reason,
                    attemptTrail = emptyList()
                )
            }
        }

        if (!isWithinDisplayBounds(point, displayInfo)) {
            return ActionOutcome.Failed(
                reason =
                    "Resolved click target (${point.x},${point.y}) is outside display bounds " +
                        "${displayInfo.widthPixels}x${displayInfo.heightPixels}",
                attemptTrail = emptyList()
            )
        }

        val attemptTrail = mutableListOf<String>()
        var lastFailChannel = ""
        var lastFailReason = ""

        for (channel in ActionPriorityOrder.click) {
            if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before click attempt")
            when (channel) {
                ActionPriorityOrder.ClickChannel.GESTURE_TAP -> {
                    val tapResult = platform.performAction(UIAction.TapAt(point.x, point.y))
                    when (tapResult) {
                        is ActionResult.Success -> {
                            attemptTrail += "gesture_tap: success"
                            return buildSuccessOutcome(
                                point = point,
                                channel = "gesture_tap",
                                warnings = resolvedWarnings,
                                attemptTrail = attemptTrail,
                                platform = platform
                            )
                        }
                        is ActionResult.Cancelled -> return ActionOutcome.Cancelled(tapResult.reason)
                        is ActionResult.Failure -> {
                            lastFailChannel = "gesture_tap"
                            lastFailReason = tapResult.reason
                            attemptTrail += "gesture_tap: ${tapResult.reason}"
                        }
                    }
                }
                ActionPriorityOrder.ClickChannel.NODE_CLICK -> {
                    if (!target.isSemantic()) continue
                    val nodeResult = platform.performAction(UIAction.ClickNodeAt(point.x, point.y))
                    when (nodeResult) {
                        is ActionResult.Success -> {
                            attemptTrail += "node_action_click: success"
                            return buildSuccessOutcome(
                                point = point,
                                channel = "node_action_click",
                                warnings = resolvedWarnings,
                                attemptTrail = attemptTrail,
                                platform = platform
                            )
                        }
                        is ActionResult.Cancelled -> return ActionOutcome.Cancelled(nodeResult.reason)
                        is ActionResult.Failure -> {
                            lastFailChannel = "node_action_click"
                            lastFailReason = nodeResult.reason
                            attemptTrail += "node_action_click: ${nodeResult.reason}"
                        }
                    }
                }
            }
        }

        return ActionOutcome.Failed(
            reason = formatFailure(point, lastFailChannel, lastFailReason, resolvedWarnings),
            attemptTrail = attemptTrail
        )
    }

    private suspend fun buildSuccessOutcome(
        point: Point,
        channel: String,
        warnings: List<String>,
        attemptTrail: List<String>,
        platform: AndroidPlatform
    ): ActionOutcome.Success {
        delay(UI_SETTLE_DELAY_MS)
        val postResult = runCatching { platform.captureScreen() }
        val postSnapshot = postResult.getOrNull()
        val observation = postSnapshot?.let { buildObservation(it, platform) }
        val captureWarning = postResult.exceptionOrNull()?.message?.let { reason ->
            "Post-action capture failed: $reason"
        }
        val allWarnings = buildList {
            addAll(warnings)
            if (captureWarning != null) add(captureWarning)
        }
        return ActionOutcome.Success(
            message = formatSuccess(point, channel, allWarnings),
            observation = observation,
            attemptTrail = attemptTrail,
            verified = true
        )
    }

    private fun isWithinDisplayBounds(point: Point, displayInfo: DisplayInfo): Boolean {
        if (displayInfo.widthPixels <= 0 || displayInfo.heightPixels <= 0) return true
        return point.x in 0 until displayInfo.widthPixels &&
            point.y in 0 until displayInfo.heightPixels
    }

    private fun formatSuccess(point: Point, channel: String, warnings: List<String>): String {
        val verb = if (channel == "gesture_tap") "Tapped" else "Clicked"
        val base = "$verb (${point.x},${point.y}) via $channel"
        if (warnings.isEmpty()) return base
        return buildString {
            append(base)
            warnings.forEach { warning -> append("\nWarning: $warning") }
        }
    }

    private fun formatFailure(
        point: Point,
        channel: String,
        reason: String,
        warnings: List<String>
    ): String {
        val base = "Click at (${point.x},${point.y}) via $channel failed: $reason"
        if (warnings.isEmpty()) return base
        return buildString {
            append(base)
            warnings.forEach { warning -> append("\nWarning: $warning") }
        }
    }

    private fun Target.isSemantic(): Boolean = this is Target.ElementIndex || this is Target.Text
}

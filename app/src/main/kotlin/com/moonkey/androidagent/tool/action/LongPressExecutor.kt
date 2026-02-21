package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.UIAction
import kotlinx.coroutines.delay

/**
 * Long press executor: resolve target once, then:
 * 1) primary: gesture long-press at resolved coordinates
 * 2) fallback for semantic targets: ACTION_LONG_CLICK on the resolved node
 */
class LongPressExecutor(
    private val targetResolver: TargetResolver = TargetResolver
) {
    companion object {
        private const val UI_SETTLE_DELAY_MS = 300L
    }

    suspend fun execute(
        target: Target,
        durationMs: Long,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform,
        isCancelled: () -> Boolean
    ): ActionOutcome {
        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before long press")
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
                    "Resolved long_press target (${point.x},${point.y}) is outside display bounds " +
                        "${displayInfo.widthPixels}x${displayInfo.heightPixels}",
                attemptTrail = emptyList()
            )
        }
        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before dispatch")

        val attemptTrail = mutableListOf<String>()
        var lastFailChannel = ""
        var lastFailReason = ""

        for (channel in ActionPriorityOrder.longPress) {
            if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before long press attempt")
            when (channel) {
                ActionPriorityOrder.LongPressChannel.GESTURE_LONG_PRESS -> {
                    val actionResult = platform.performAction(
                        UIAction.LongPressAt(x = point.x, y = point.y, durationMs = durationMs)
                    )
                    when (actionResult) {
                        is ActionResult.Success -> {
                            attemptTrail += "gesture_long_press: success"
                            return buildSuccessOutcome(
                                point = point,
                                durationMs = durationMs,
                                channel = "gesture_long_press",
                                resolvedWarnings = resolvedWarnings,
                                attemptTrail = attemptTrail,
                                platform = platform
                            )
                        }
                        is ActionResult.Cancelled -> return ActionOutcome.Cancelled(actionResult.reason)
                        is ActionResult.Failure -> {
                            lastFailChannel = "gesture_long_press"
                            lastFailReason = actionResult.reason
                            attemptTrail += "gesture_long_press: ${actionResult.reason}"
                        }
                    }
                }
                ActionPriorityOrder.LongPressChannel.NODE_LONG_CLICK -> {
                    if (!target.isSemantic()) continue
                    val nodeResult = platform.performAction(UIAction.LongClickNodeAt(point.x, point.y))
                    when (nodeResult) {
                        is ActionResult.Success -> {
                            attemptTrail += "node_action_long_click: success"
                            return buildSuccessOutcome(
                                point = point,
                                durationMs = durationMs,
                                channel = "node_action_long_click",
                                resolvedWarnings = resolvedWarnings,
                                attemptTrail = attemptTrail,
                                platform = platform
                            )
                        }
                        is ActionResult.Cancelled -> return ActionOutcome.Cancelled(nodeResult.reason)
                        is ActionResult.Failure -> {
                            lastFailChannel = "node_action_long_click"
                            lastFailReason = nodeResult.reason
                            attemptTrail += "node_action_long_click: ${nodeResult.reason}"
                        }
                    }
                }
            }
        }

        return ActionOutcome.Failed(
            reason = formatFailure(point, durationMs, lastFailChannel, lastFailReason, resolvedWarnings),
            attemptTrail = attemptTrail
        )
    }

    private fun isWithinDisplayBounds(point: Point, displayInfo: DisplayInfo): Boolean {
        if (displayInfo.widthPixels <= 0 || displayInfo.heightPixels <= 0) return true
        return point.x in 0 until displayInfo.widthPixels &&
            point.y in 0 until displayInfo.heightPixels
    }

    private suspend fun buildSuccessOutcome(
        point: Point,
        durationMs: Long,
        channel: String,
        resolvedWarnings: List<String>,
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
            addAll(resolvedWarnings)
            if (captureWarning != null) add(captureWarning)
        }
        return ActionOutcome.Success(
            message = formatSuccess(point, durationMs, channel, allWarnings),
            observation = observation,
            attemptTrail = attemptTrail,
            verified = true
        )
    }

    private fun formatSuccess(point: Point, durationMs: Long, channel: String, warnings: List<String>): String {
        val base =
            "Long pressed (${point.x},${point.y}) for ${durationMs}ms via $channel"
        if (warnings.isEmpty()) return base
        return buildString {
            append(base)
            warnings.forEach { warning -> append("\nWarning: $warning") }
        }
    }

    private fun formatFailure(
        point: Point,
        durationMs: Long,
        channel: String,
        reason: String,
        warnings: List<String>
    ): String {
        val base =
            "Long press at (${point.x},${point.y}) for ${durationMs}ms via $channel failed: $reason"
        if (warnings.isEmpty()) return base
        return buildString {
            append(base)
            warnings.forEach { warning -> append("\nWarning: $warning") }
        }
    }

    private fun Target.isSemantic(): Boolean = this is Target.ElementIndex || this is Target.Text
}

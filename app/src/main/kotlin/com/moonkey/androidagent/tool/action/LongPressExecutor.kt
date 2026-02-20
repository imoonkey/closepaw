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
 * 1) for semantic targets, prefer ACTION_LONG_CLICK on the resolved node
 * 2) fallback to gesture long-press at resolved coordinates
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

        if (target.isSemantic()) {
            val nodeResult = platform.performAction(UIAction.LongClickNodeAt(point.x, point.y))
            when (nodeResult) {
                is ActionResult.Success -> {
                    attemptTrail += "node_action_long_click: success"
                    return buildSuccessOutcome(
                        point = point,
                        durationMs = durationMs,
                        channel = "node_action_long_click",
                        preSnapshot = snapshot,
                        resolvedWarnings = resolvedWarnings,
                        attemptTrail = attemptTrail,
                        platform = platform
                    )
                }
                is ActionResult.Cancelled -> return ActionOutcome.Cancelled(nodeResult.reason)
                is ActionResult.Failure -> {
                    attemptTrail += "node_action_long_click: ${nodeResult.reason}"
                }
            }
        }

        val actionResult = platform.performAction(
            UIAction.LongPressAt(
                x = point.x,
                y = point.y,
                durationMs = durationMs
            )
        )

        when (actionResult) {
            is ActionResult.Failure -> {
                attemptTrail += "gesture_long_press: ${actionResult.reason}"
                return ActionOutcome.Failed(
                    reason = formatFailure(point, durationMs, "gesture_long_press", actionResult.reason, resolvedWarnings),
                    attemptTrail = attemptTrail
                )
            }
            is ActionResult.Cancelled -> return ActionOutcome.Cancelled(actionResult.reason)
            is ActionResult.Success -> {
                attemptTrail += "gesture_long_press: success"
                return buildSuccessOutcome(
                    point = point,
                    durationMs = durationMs,
                    channel = "gesture_long_press",
                    preSnapshot = snapshot,
                    resolvedWarnings = resolvedWarnings,
                    attemptTrail = attemptTrail,
                    platform = platform
                )
            }
        }
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
        preSnapshot: ScreenSnapshot?,
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
        val warnings = buildList {
            addAll(resolvedWarnings)
            if (captureWarning != null) add(captureWarning)
        }
        val changeResult = UiChangeDetector.compare(preSnapshot, postSnapshot)
        val unchangedWarning =
            if (changeResult == UiChangeDetector.ChangeResult.Unchanged) {
                "Screen content unchanged after long press - action may have had no effect"
            } else {
                null
            }
        val allWarnings = buildList {
            addAll(warnings)
            if (unchangedWarning != null) add(unchangedWarning)
        }
        val verified = unchangedWarning == null
        return ActionOutcome.Success(
            message = formatSuccess(point, durationMs, channel, allWarnings),
            observation = observation,
            attemptTrail = attemptTrail,
            verified = verified
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

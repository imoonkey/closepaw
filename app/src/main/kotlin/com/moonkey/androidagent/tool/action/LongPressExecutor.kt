package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.UIAction
import kotlinx.coroutines.delay

/**
 * Long press executor: resolve target once, dispatch one swipe-to-self gesture, capture once.
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

        val actionResult = platform.performAction(
            UIAction.Swipe(
                startX = point.x,
                startY = point.y,
                endX = point.x,
                endY = point.y,
                durationMs = durationMs
            )
        )

        when (actionResult) {
            is ActionResult.Failure -> {
                return ActionOutcome.Failed(
                    reason = formatFailure(point, durationMs, actionResult.reason, resolvedWarnings),
                    attemptTrail = listOf("swipe_to_self: ${actionResult.reason}")
                )
            }
            is ActionResult.Cancelled -> return ActionOutcome.Cancelled(actionResult.reason)
            is ActionResult.Success -> {
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
                return ActionOutcome.Success(
                    message = formatSuccess(point, durationMs, warnings),
                    observation = observation,
                    attemptTrail = listOf("swipe_to_self: success"),
                    verified = true
                )
            }
        }
    }

    private fun isWithinDisplayBounds(point: Point, displayInfo: DisplayInfo): Boolean {
        if (displayInfo.widthPixels <= 0 || displayInfo.heightPixels <= 0) return true
        return point.x in 0 until displayInfo.widthPixels &&
            point.y in 0 until displayInfo.heightPixels
    }

    private fun formatSuccess(point: Point, durationMs: Long, warnings: List<String>): String {
        val base =
            "Long pressed (${point.x},${point.y}) for ${durationMs}ms via swipe_to_self"
        if (warnings.isEmpty()) return base
        return buildString {
            append(base)
            warnings.forEach { warning -> append("\nWarning: $warning") }
        }
    }

    private fun formatFailure(
        point: Point,
        durationMs: Long,
        reason: String,
        warnings: List<String>
    ): String {
        val base =
            "Long press at (${point.x},${point.y}) for ${durationMs}ms failed: $reason"
        if (warnings.isEmpty()) return base
        return buildString {
            append(base)
            warnings.forEach { warning -> append("\nWarning: $warning") }
        }
    }
}

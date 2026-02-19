package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.UIAction
import kotlinx.coroutines.delay

/**
 * Click executor: resolve target once, dispatch one gesture tap, capture once.
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
        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before dispatch")

        val actionResult = platform.performAction(UIAction.TapAt(point.x, point.y))
        when (actionResult) {
            is ActionResult.Failure -> {
                return ActionOutcome.Failed(
                    reason = formatFailure(point, actionResult.reason, resolvedWarnings),
                    attemptTrail = listOf("gesture_tap: ${actionResult.reason}")
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
                    message = formatSuccess(point, warnings),
                    observation = observation,
                    attemptTrail = listOf("gesture_tap: success"),
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

    private fun formatSuccess(point: Point, warnings: List<String>): String {
        val base = "Tapped (${point.x},${point.y}) via gesture_tap"
        if (warnings.isEmpty()) return base
        return buildString {
            append(base)
            warnings.forEach { warning -> append("\nWarning: $warning") }
        }
    }

    private fun formatFailure(point: Point, reason: String, warnings: List<String>): String {
        val base = "Tap at (${point.x},${point.y}) failed: $reason"
        if (warnings.isEmpty()) return base
        return buildString {
            append(base)
            warnings.forEach { warning -> append("\nWarning: $warning") }
        }
    }
}

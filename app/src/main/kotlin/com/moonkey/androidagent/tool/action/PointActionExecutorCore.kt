package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.UIAction
import kotlinx.coroutines.delay

/**
 * Channel attempt descriptor for the point-action fallback loop.
 *
 * @param displayName Human-readable channel name for logging/messages (e.g. "gesture_tap")
 * @param requiresSemantic If true, this channel is skipped for coordinate-only targets
 * @param createAction Factory that produces the UIAction for the given resolved point
 */
internal data class ChannelAttempt(
    val displayName: String,
    val requiresSemantic: Boolean,
    val createAction: (Point) -> UIAction
)

private const val UI_SETTLE_DELAY_MS = 300L

/**
 * Core executor for point-based actions (click, long press).
 *
 * Shared execution path: resolve target → bounds check → channel fallback loop → post-capture.
 * [ClickExecutor] and [LongPressExecutor] are thin wrappers over this function.
 */
internal suspend fun executePointAction(
    actionName: String,
    channels: List<ChannelAttempt>,
    target: Target,
    snapshot: ScreenSnapshot?,
    platform: AndroidPlatform,
    isCancelled: () -> Boolean,
    formatSuccess: (point: Point, channelName: String, warnings: List<String>) -> String,
    formatFailure: (point: Point, channelName: String, reason: String, warnings: List<String>) -> String,
    targetResolver: TargetResolver = TargetResolver
): ActionOutcome {
    if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before $actionName")

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
            reason = "Resolved $actionName target (${point.x},${point.y}) is outside display bounds " +
                "${displayInfo.widthPixels}x${displayInfo.heightPixels}",
            attemptTrail = emptyList()
        )
    }

    if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before dispatch")

    val attemptTrail = mutableListOf<String>()
    var lastFailChannel = ""
    var lastFailReason = ""

    for (channel in channels) {
        if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before $actionName attempt")
        if (channel.requiresSemantic && !target.isSemantic()) continue

        val result = platform.performAction(channel.createAction(point))
        when (result) {
            is ActionResult.Success -> {
                attemptTrail += "${channel.displayName}: success"
                return buildPointActionSuccess(
                    point = point,
                    channelName = channel.displayName,
                    resolvedWarnings = resolvedWarnings,
                    attemptTrail = attemptTrail,
                    platform = platform,
                    formatSuccess = formatSuccess
                )
            }
            is ActionResult.Cancelled -> return ActionOutcome.Cancelled(result.reason)
            is ActionResult.Failure -> {
                lastFailChannel = channel.displayName
                lastFailReason = result.reason
                attemptTrail += "${channel.displayName}: ${result.reason}"
            }
        }
    }

    return ActionOutcome.Failed(
        reason = formatFailure(point, lastFailChannel, lastFailReason, resolvedWarnings),
        attemptTrail = attemptTrail
    )
}

/**
 * Append warnings to a base message string. Shared by click/long-press formatters.
 */
internal fun formatActionMessage(base: String, warnings: List<String>): String {
    if (warnings.isEmpty()) return base
    return buildString {
        append(base)
        warnings.forEach { warning -> append("\nWarning: $warning") }
    }
}

private suspend fun buildPointActionSuccess(
    point: Point,
    channelName: String,
    resolvedWarnings: List<String>,
    attemptTrail: List<String>,
    platform: AndroidPlatform,
    formatSuccess: (Point, String, List<String>) -> String
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
        message = formatSuccess(point, channelName, allWarnings),
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

private fun Target.isSemantic(): Boolean = this is Target.ElementIndex || this is Target.Text

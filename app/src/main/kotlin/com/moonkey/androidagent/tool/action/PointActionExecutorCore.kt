package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.SemanticTargetHint
import com.moonkey.androidagent.platform.UIAction
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
    val createAction: (Point, SemanticTargetHint?) -> UIAction
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
    val semanticHint: SemanticTargetHint?
    val point = when (resolvedTarget) {
        is TargetResolver.ResolveResult.Resolved -> {
            val refinedTarget = refinePointActionTarget(target, resolvedTarget, snapshot)
            resolvedWarnings = refinedTarget.warnings
            semanticHint = refinedTarget.semanticHint
            refinedTarget.point
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

        val result = platform.performAction(channel.createAction(point, semanticHint))
        when (result) {
            is ActionResult.Success -> {
                val outcome = buildPointActionOutcome(
                    actionName = actionName,
                    point = point,
                    channelName = channel.displayName,
                    resolvedWarnings = resolvedWarnings,
                    attemptTrail = attemptTrail,
                    preSnapshot = snapshot,
                    platform = platform,
                    formatSuccess = formatSuccess
                )
                when (outcome) {
                    is ActionOutcome.Success -> {
                        attemptTrail += "${channel.displayName}: success"
                        return outcome.copy(attemptTrail = attemptTrail.toList())
                    }
                    is ActionOutcome.Failed -> {
                        lastFailChannel = channel.displayName
                        lastFailReason = outcome.reason
                        attemptTrail += "${channel.displayName}: ${outcome.reason}"
                    }
                    is ActionOutcome.Cancelled -> return outcome
                }
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
        reason = if (attemptTrail.any { it.contains("no observable effect") }) {
            formatActionMessage(
                "${actionName.replace('_', ' ').replaceFirstChar { it.uppercase() }} at " +
                    "(${point.x},${point.y}) had no observable effect after all channels",
                resolvedWarnings
            )
        } else {
            formatFailure(point, lastFailChannel, lastFailReason, resolvedWarnings)
        },
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

private suspend fun buildPointActionOutcome(
    actionName: String,
    point: Point,
    channelName: String,
    resolvedWarnings: List<String>,
    attemptTrail: List<String>,
    preSnapshot: ScreenSnapshot?,
    platform: AndroidPlatform,
    formatSuccess: (Point, String, List<String>) -> String
): ActionOutcome {
    val analysis = capturePostActionAnalysis(
        preSnapshot = preSnapshot,
        platform = platform,
        settleDelayMs = UI_SETTLE_DELAY_MS
    )
    if (analysis.changeResult == UiChangeDetector.ChangeResult.Unchanged) {
        return ActionOutcome.Failed(
            reason = "${actionName.replace('_', ' ')} via $channelName had no observable effect",
            attemptTrail = attemptTrail
        )
    }
    val allWarnings = buildList {
        addAll(resolvedWarnings)
        addAll(analysis.warnings)
    }
    return ActionOutcome.Success(
        message = formatSuccess(point, channelName, allWarnings),
        observation = analysis.observation,
        attemptTrail = attemptTrail,
        verified = analysis.changeResult == UiChangeDetector.ChangeResult.Changed
    )
}

private fun isWithinDisplayBounds(point: Point, displayInfo: DisplayInfo): Boolean {
    if (displayInfo.widthPixels <= 0 || displayInfo.heightPixels <= 0) return true
    return point.x in 0 until displayInfo.widthPixels &&
        point.y in 0 until displayInfo.heightPixels
}

private fun Target.isSemantic(): Boolean = this is Target.ElementIndex || this is Target.Text

private fun refinePointActionTarget(
    target: Target,
    resolved: TargetResolver.ResolveResult.Resolved,
    snapshot: ScreenSnapshot?
): TargetResolver.ResolveResult.Resolved {
    val snap = snapshot ?: return resolved
    val element = findResolvedElement(target, resolved, snap.elements) ?: return resolved
    if (element.isClickable || element.isLongClickable) return resolved
    val container = findBestActionableContainer(element.bounds, snap.elements) ?: return resolved
    val hint = SemanticTargetHint(
        resourceId = container.resourceId,
        text = container.text,
        description = container.description,
        className = container.className,
        bounds = container.bounds
    )
    return TargetResolver.ResolveResult.Resolved(
        point = container.center,
        bounds = container.bounds,
        semanticHint = hint,
        warnings = resolved.warnings
    )
}

private fun findResolvedElement(
    target: Target,
    resolved: TargetResolver.ResolveResult.Resolved,
    elements: List<PerceptionElement>
): PerceptionElement? {
    return when (target) {
        is Target.Coordinate -> null
        is Target.ElementIndex -> elements.firstOrNull { it.index == target.index }
        is Target.Text -> {
            val bounds = resolved.bounds
            elements.firstOrNull { it.bounds == bounds } ?: elements.firstOrNull {
                it.text.equals(target.text, ignoreCase = true) ||
                    it.description.equals(target.text, ignoreCase = true)
            }
        }
    }
}

private fun findBestActionableContainer(
    childBounds: Bounds,
    elements: List<PerceptionElement>
): PerceptionElement? {
    return elements
        .asSequence()
        .filter { it.isClickable || it.isLongClickable }
        .filter { it.bounds.contains(childBounds) }
        .filterNot { it.bounds == childBounds }
        .minWithOrNull(
            compareBy<PerceptionElement> { it.bounds.area() }
                .thenBy { it.index }
        )
}

private fun Bounds.contains(other: Bounds): Boolean {
    return left <= other.left &&
        top <= other.top &&
        right >= other.right &&
        bottom >= other.bottom
}

private fun Bounds.area(): Long {
    return (right - left).toLong() * (bottom - top).toLong()
}

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
import com.moonkey.androidagent.tool.AppClassifier
import android.util.Log

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
private const val TAG = "PointActionCore"
/** Max child-to-container area ratio. Excludes near-full-size overlays. */
private const val MAX_CHILD_AREA_FRACTION = 8L // out of 10 → 80%
/** If runner-up is within this ratio of the nearest, selection is ambiguous → fall back to container. */
private const val AMBIGUITY_DISTANCE_RATIO = 2L // nearest must be < 1/2 the runner-up distance (squared)

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
    targetResolver: TargetResolver = TargetResolver,
    appClassifier: AppClassifier? = null
): ActionOutcome {
    if (isCancelled()) return ActionOutcome.Cancelled("Cancelled before $actionName")

    val displayInfo = platform.getDisplayInfo()
    val resolvedTarget = targetResolver.resolve(target, snapshot)
    val resolvedWarnings: List<String>
    val semanticHint: SemanticTargetHint?
    val point = when (resolvedTarget) {
        is TargetResolver.ResolveResult.Resolved -> {
            val refinedTarget = refinePointActionTarget(actionName, target, resolvedTarget, snapshot)
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
                    formatSuccess = formatSuccess,
                    appClassifier = appClassifier
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
    formatSuccess: (Point, String, List<String>) -> String,
    appClassifier: AppClassifier? = null
): ActionOutcome {
    val analysis = capturePostActionAnalysis(
        preSnapshot = preSnapshot,
        platform = platform,
        settleDelayMs = UI_SETTLE_DELAY_MS,
        appClassifier = appClassifier
    )
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
    actionName: String,
    target: Target,
    resolved: TargetResolver.ResolveResult.Resolved,
    snapshot: ScreenSnapshot?
): TargetResolver.ResolveResult.Resolved {
    val snap = snapshot ?: return resolved
    val element = findResolvedElement(target, resolved, snap.elements) ?: return resolved
    if (element.isClickable || element.isLongClickable) return resolved
    val container = findBestActionableContainer(element.bounds, snap.elements) ?: return resolved

    val child = findBestActionableChild(actionName, resolved.point, container, snap.elements)
    val finalTarget = child ?: container
    val source = if (child != null) "child" else "container"

    Log.d(TAG, "refinePointActionTarget: original=(${resolved.point.x},${resolved.point.y}) " +
        "element=[${element.bounds}] container=[${container.bounds}] " +
        "final=$source [${finalTarget.bounds}] point=(${finalTarget.center.x},${finalTarget.center.y})")

    val hint = SemanticTargetHint(
        resourceId = finalTarget.resourceId,
        text = finalTarget.text,
        description = finalTarget.description,
        className = finalTarget.className,
        bounds = finalTarget.bounds
    )
    return TargetResolver.ResolveResult.Resolved(
        point = finalTarget.center,
        bounds = finalTarget.bounds,
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

/**
 * Within a promoted container, find the closest actionable child to [originalPoint].
 *
 * Only considers children materially smaller than the container (< 80% area)
 * to avoid picking a near-full-size overlay or the container itself.
 *
 * Action-specific: for click, only clickable children; for long_press, clickable or
 * long-clickable (matching platform's clickable fallback in NodeActionPerformer).
 * Ambiguity guard: if the nearest and runner-up are similarly close, falls back to null
 * (caller uses container) to avoid misrouting onto overflow/toggle controls.
 */
private fun findBestActionableChild(
    actionName: String,
    originalPoint: Point,
    container: PerceptionElement,
    elements: List<PerceptionElement>
): PerceptionElement? {
    val containerArea = container.bounds.area()
    if (containerArea <= 0) return null
    val isLongPress = actionName == "long_press"
    val candidates = elements
        .asSequence()
        .filter { if (isLongPress) (it.isClickable || it.isLongClickable) else it.isClickable }
        .filter { container.bounds.contains(it.bounds) }
        .filterNot { it.bounds == container.bounds }
        .filter { it.bounds.area() < containerArea * MAX_CHILD_AREA_FRACTION / 10 }
        .map { it to distanceSq(originalPoint, it.center) }
        .sortedBy { it.second }
        .take(2)
        .toList()

    if (candidates.isEmpty()) return null
    val (nearest, nearestDist) = candidates[0]
    // Single candidate: unambiguous
    if (candidates.size == 1) return nearest
    // Multiple: nearest must be clearly closer than runner-up (< half the distance squared)
    val (_, runnerUpDist) = candidates[1]
    if (nearestDist * AMBIGUITY_DISTANCE_RATIO <= runnerUpDist) return nearest
    Log.d(TAG, "findBestActionableChild: ambiguous (nearest=$nearestDist, runnerUp=$runnerUpDist), using container")
    return null
}

private fun distanceSq(a: Point, b: Point): Long {
    val dx = (a.x - b.x).toLong()
    val dy = (a.y - b.y).toLong()
    return dx * dx + dy * dy
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

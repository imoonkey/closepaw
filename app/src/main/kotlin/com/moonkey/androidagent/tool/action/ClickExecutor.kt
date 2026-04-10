package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.AppClassifier

/**
 * Click executor: thin wrapper over [executePointAction].
 *
 * Primary path for semantic targets: node ACTION_CLICK (a11y tree dependent).
 * Fallback: gesture tap (works on any visible element).
 * Coordinate targets: gesture tap only (node_click skipped).
 */
class ClickExecutor(
    private val targetResolver: TargetResolver = TargetResolver
) {
    suspend fun execute(
        target: Target,
        snapshot: ScreenSnapshot?,
        platform: AndroidPlatform,
        isCancelled: () -> Boolean,
        appClassifier: AppClassifier? = null
    ): ActionOutcome = executePointAction(
        actionName = "click",
        channels = ActionPriorityOrder.click.map { channel ->
            when (channel) {
                ActionPriorityOrder.ClickChannel.NODE_CLICK -> ChannelAttempt(
                    displayName = "node_action_click",
                    requiresSemantic = true
                ) { pt, hint -> UIAction.ClickNodeAt(pt.x, pt.y, hint) }
                ActionPriorityOrder.ClickChannel.GESTURE_TAP -> ChannelAttempt(
                    displayName = "gesture_tap",
                    requiresSemantic = false
                ) { pt, _ -> UIAction.TapAt(pt.x, pt.y) }
            }
        },
        target = target,
        snapshot = snapshot,
        platform = platform,
        isCancelled = isCancelled,
        formatSuccess = { point, channel, warnings ->
            val verb = if (channel == "gesture_tap") "Tapped" else "Clicked"
            formatActionMessage("$verb (${point.x},${point.y}) via $channel", warnings)
        },
        formatFailure = { point, channel, reason, warnings ->
            formatActionMessage("Click at (${point.x},${point.y}) via $channel failed: $reason", warnings)
        },
        targetResolver = targetResolver,
        appClassifier = appClassifier
    )
}

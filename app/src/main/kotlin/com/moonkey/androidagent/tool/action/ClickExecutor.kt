package com.moonkey.androidagent.tool.action

import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.UIAction

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
        isCancelled: () -> Boolean
    ): ActionOutcome = executePointAction(
        actionName = "click",
        channels = ActionPriorityOrder.click.map { channel ->
            when (channel) {
                ActionPriorityOrder.ClickChannel.NODE_CLICK -> ChannelAttempt(
                    displayName = "node_action_click",
                    requiresSemantic = true
                ) { UIAction.ClickNodeAt(it.x, it.y) }
                ActionPriorityOrder.ClickChannel.GESTURE_TAP -> ChannelAttempt(
                    displayName = "gesture_tap",
                    requiresSemantic = false
                ) { UIAction.TapAt(it.x, it.y) }
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
        targetResolver = targetResolver
    )
}

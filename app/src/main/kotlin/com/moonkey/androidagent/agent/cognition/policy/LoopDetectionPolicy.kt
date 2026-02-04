package com.moonkey.androidagent.agent.cognition.policy

import com.moonkey.androidagent.agent.cognition.context.LoopWarning
import com.moonkey.androidagent.agent.cognition.context.LoopWarningSeverity
import com.moonkey.androidagent.agent.cognition.context.NavigationState
import com.moonkey.androidagent.agent.cognition.context.ScreenSignature

internal data class LoopDetectionConfig(
    val similarityThreshold: Double = 0.90,
    val repeatedScreenWindow: Int = 3,
    val repeatedActionWindow: Int = 3,
    val maxConsecutiveScrollActions: Int = 5
)

internal class LoopDetectionPolicy(
    private val config: LoopDetectionConfig = LoopDetectionConfig()
) {
    fun detect(state: NavigationState): LoopWarning? {
        val latestSignatures = state.recentSignatures.takeLast(config.repeatedScreenWindow)
        if (latestSignatures.size == config.repeatedScreenWindow && latestSignatures.isStable(config.similarityThreshold)) {
            return LoopWarning(
                message = "Screen state looks unchanged for ${config.repeatedScreenWindow} turns. Try a different strategy (back, search, filter, or open menu).",
                severity = LoopWarningSeverity.CRITICAL
            )
        }

        if (state.consecutiveScrollActions >= config.maxConsecutiveScrollActions) {
            return LoopWarning(
                message = "Detected ${state.consecutiveScrollActions} consecutive scroll attempts with limited progress. Avoid more scrolling and switch strategy.",
                severity = LoopWarningSeverity.WARNING
            )
        }

        val latestActions = state.recentActions.takeLast(config.repeatedActionWindow)
        if (latestActions.size == config.repeatedActionWindow && latestActions.distinct().size == 1) {
            return LoopWarning(
                message = "Same action repeated ${config.repeatedActionWindow} times (${latestActions.first()}). Pick an alternative action.",
                severity = LoopWarningSeverity.WARNING
            )
        }

        return null
    }
}

private fun List<ScreenSignature>.isStable(threshold: Double): Boolean {
    if (size < 2) return false
    return zipWithNext().all { (left, right) ->
        left.similarityTo(right) >= threshold
    }
}

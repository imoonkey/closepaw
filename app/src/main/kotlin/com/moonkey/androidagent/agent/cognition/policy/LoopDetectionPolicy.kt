package com.moonkey.androidagent.agent.cognition.policy

import com.moonkey.androidagent.agent.cognition.context.LoopWarning
import com.moonkey.androidagent.agent.cognition.context.LoopWarningSeverity
import com.moonkey.androidagent.agent.cognition.context.NavigationState
import com.moonkey.androidagent.agent.cognition.context.ScreenSignature

/**
 * Thresholds for deciding whether the agent is looping on UI navigation.
 */
internal data class LoopDetectionConfig(
    val similarityThreshold: Double = 0.90,
    val repeatedScreenWindow: Int = 3,
    val repeatedActionWindow: Int = 3,
    val maxConsecutiveScrollActions: Int = 5,
    val cycleMatchThreshold: Double = 0.85,
    val cycleMinOccurrences: Int = 3
)

/**
 * Detects "stuck" patterns from navigation history and emits a warning hint.
 */
internal class LoopDetectionPolicy(
    private val config: LoopDetectionConfig = LoopDetectionConfig()
) {
    /**
     * Returns a warning when one of the loop heuristics is triggered:
     * - screen signatures stay almost unchanged
     * - too many scroll actions in a row
     * - same action repeated in a short window
     */
    fun detect(state: NavigationState): LoopWarning? {
        // Multi-state cycle detection: check if the current screen has appeared
        // multiple times in recent history (catches A-B-C-A-B-C style loops where
        // consecutive screens are all different from each other).
        if (state.recentSignatures.size >= config.cycleMinOccurrences) {
            val current = state.recentSignatures.last()
            val matchCount = state.recentSignatures.count {
                it.similarityTo(current) >= config.cycleMatchThreshold
            }
            if (matchCount >= config.cycleMinOccurrences) {
                return LoopWarning(
                    message = "Cycle detected: this screen has appeared $matchCount times " +
                            "in the last ${state.recentSignatures.size} turns. " +
                            "Your actions are not making progress. Try a completely " +
                            "different approach or abandon this sub-goal.",
                    severity = LoopWarningSeverity.CRITICAL
                )
            }
        }

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

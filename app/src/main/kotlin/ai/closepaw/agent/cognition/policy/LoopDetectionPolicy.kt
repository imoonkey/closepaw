package ai.closepaw.agent.cognition.policy

import ai.closepaw.agent.cognition.context.LoopWarning
import ai.closepaw.agent.cognition.context.NavigationState
import ai.closepaw.agent.cognition.context.ScreenSignature

/**
 * Thresholds for deciding whether the agent is stuck on UI navigation.
 *
 * Only detects stable-screen (near-identical screens for N consecutive turns).
 * All advisory warnings (cycle detection, tool dominance, scroll spam, action repetition)
 * have been removed — they caused false positives that poisoned multi-item workflows.
 */
internal data class LoopDetectionConfig(
    val similarityThreshold: Double = 0.95,
    val stableScreenWindow: Int = 5
)

/** Combined result of loop detection: the warning (if any). */
internal data class LoopDetectionResult(
    val warning: LoopWarning?
)

/**
 * Detects "stuck" patterns from navigation history.
 *
 * Single check: if the last [LoopDetectionConfig.stableScreenWindow] screens are nearly identical
 * (Jaccard similarity >= [LoopDetectionConfig.similarityThreshold]), emit a factual warning.
 * The warning states a fact ("screen has not changed") — no strategy suggestions.
 * The LLM decides what to do with the information.
 *
 * Turn limit is the only hard stop mechanism. Advisory warnings are facts, not opinions.
 */
internal class LoopDetectionPolicy(
    private val config: LoopDetectionConfig = LoopDetectionConfig()
) {
    fun detect(state: NavigationState): LoopDetectionResult {
        val recent = state.recentSignatures.takeLast(config.stableScreenWindow)
        if (recent.size == config.stableScreenWindow && recent.isStable(config.similarityThreshold)) {
            return LoopDetectionResult(
                warning = LoopWarning(
                    message = "Screen has not changed for ${config.stableScreenWindow} turns."
                )
            )
        }
        return LoopDetectionResult(warning = null)
    }
}

private fun List<ScreenSignature>.isStable(threshold: Double): Boolean {
    if (size < 2) return false
    return zipWithNext().all { (left, right) ->
        left.similarityTo(right) >= threshold
    }
}

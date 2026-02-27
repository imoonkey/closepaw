package com.moonkey.androidagent.agent.cognition.policy

import com.moonkey.androidagent.agent.cognition.context.LoopWarning
import com.moonkey.androidagent.agent.cognition.context.LoopWarningSeverity
import com.moonkey.androidagent.agent.cognition.context.NavigationState
import com.moonkey.androidagent.agent.cognition.context.ScreenSignature

/**
 * Thresholds for deciding whether the agent is looping on UI navigation.
 */
internal data class LoopDetectionConfig(
    val similarityThreshold: Double = 0.85,
    val repeatedScreenWindow: Int = 5,
    val repeatedActionWindow: Int = 3,
    val maxConsecutiveScrollActions: Int = 4,
    val cycleMatchThreshold: Double = 0.75,
    val cycleMinOccurrences: Int = 2,
    /** Trigger warning when one tool type dominates recent action history. */
    val toolRepetitionThreshold: Int = 3,
    /** Consecutive CRITICAL-warning turns before Tier 2 (action blocking). */
    val blockEscalationThreshold: Int = 2,
    /** Consecutive CRITICAL-warning turns before Tier 3 (forced failure completion). */
    val forceCompleteEscalationThreshold: Int = 5
)

/** Escalation level for loop intervention. */
internal enum class EscalationLevel {
    /** No loop detected. */
    NONE,
    /** Tier 1: warning injected into prompt. */
    ADVISORY,
    /** Tier 2: repeated actions blocked + mandatory strategy-change directive. */
    BLOCK,
    /** Tier 3: force complete_task(status=failure). */
    FORCE_COMPLETE
}

/** Combined result of loop detection: the warning (if any) and the escalation level. */
internal data class LoopDetectionResult(
    val warning: LoopWarning?,
    val escalation: EscalationLevel
)

/**
 * Detects "stuck" patterns from navigation history and decides escalation level.
 *
 * Three tiers of intervention:
 * - Tier 1 (ADVISORY): warning text injected into LLM prompt — current behavior.
 * - Tier 2 (BLOCK): after [LoopDetectionConfig.blockEscalationThreshold] consecutive CRITICAL
 *   loop turns, block the repeated action signatures via [TurnToolPolicy] and inject a stronger
 *   strategy-change directive.
 * - Tier 3 (FORCE_COMPLETE): after [LoopDetectionConfig.forceCompleteEscalationThreshold]
 *   consecutive loop turns, force task completion with failure status.
 */
internal class LoopDetectionPolicy(
    private val config: LoopDetectionConfig = LoopDetectionConfig()
) {
    /**
     * Detect loop patterns and compute escalation level.
     *
     * [state.consecutiveLoopTurns] tracks how many prior turns had CRITICAL warnings with
     * no screen progress. The caller ([AgentTurnRunner.prepareTurn]) is responsible for
     * updating this field on [NavigationState] based on the returned result.
     */
    fun detect(state: NavigationState): LoopDetectionResult {
        val warning = detectWarning(state) ?: return LoopDetectionResult(null, EscalationLevel.NONE)

        // Escalation is only driven by CRITICAL warnings + no screen progress.
        // WARNING-level issues (scroll count, tool dominance) stay advisory.
        if (warning.severity != LoopWarningSeverity.CRITICAL) {
            return LoopDetectionResult(warning, EscalationLevel.ADVISORY)
        }

        val loopTurns = state.consecutiveLoopTurns
        val escalation = when {
            loopTurns >= config.forceCompleteEscalationThreshold -> EscalationLevel.FORCE_COMPLETE
            loopTurns >= config.blockEscalationThreshold -> EscalationLevel.BLOCK
            else -> EscalationLevel.ADVISORY
        }
        return LoopDetectionResult(warning, escalation)
    }

    /**
     * Core heuristic checks — unchanged from original implementation.
     * Returns a [LoopWarning] when a loop pattern is detected, null otherwise.
     */
    private fun detectWarning(state: NavigationState): LoopWarning? {
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

        // Tool-type dominance: same tool type (e.g. "shell") used too many times
        // in recent history, even with other actions interleaved.
        if (state.recentActions.size >= config.toolRepetitionThreshold) {
            val toolCounts = state.recentActions
                .groupingBy { it.substringBefore(":") }
                .eachCount()
            val dominant = toolCounts.maxByOrNull { it.value }
            if (dominant != null && dominant.value >= config.toolRepetitionThreshold) {
                return LoopWarning(
                    message = "Tool '${dominant.key}' used ${dominant.value} times in the " +
                            "last ${state.recentActions.size} actions without progress. " +
                            "This approach is not working — try a fundamentally different strategy.",
                    severity = LoopWarningSeverity.WARNING
                )
            }
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

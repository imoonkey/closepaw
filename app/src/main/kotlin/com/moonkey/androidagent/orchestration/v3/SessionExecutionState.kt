package com.moonkey.androidagent.orchestration.v3

import com.moonkey.androidagent.domain.models.AgentAction
import com.moonkey.androidagent.domain.models.ValidationOutcome
import java.util.Collections

/**
 * SessionExecutionState - State management for MobileV3Orchestration.
 * 
 * This is the equivalent of InfoPool from the domain layer, but designed
 * specifically for the new orchestration. It tracks:
 * 
 * - The user's instruction (goal)
 * - Current plan from Manager agent
 * - Current subgoal being worked on
 * - Action history for context
 * - Validation outcomes from Reflector
 * - Error state for replanning triggers
 * - Memory for long-term context (optional)
 * 
 * Thread-safe for concurrent access.
 */
data class SessionExecutionState(
    /** The original user instruction/goal */
    val instruction: String,
    
    /** Current high-level plan from Manager */
    var plan: String = "",
    
    /** Current subgoal being executed */
    var currentSubgoal: String = "",
    
    /** History of actions taken (thread-safe) */
    val actionHistory: MutableList<AgentAction> = Collections.synchronizedList(mutableListOf()),
    
    /** History of validation outcomes (thread-safe) */
    val outcomes: MutableList<ValidationOutcome> = Collections.synchronizedList(mutableListOf()),
    
    /** Error descriptions for debugging (thread-safe) */
    val errorDescriptions: MutableList<String> = Collections.synchronizedList(mutableListOf()),
    
    /** Long-term memory/notes (optional) */
    var memory: String = "",
    
    /** Turn counter */
    var turnCount: Int = 0
) {
    
    // ===== Derived State =====
    
    /**
     * Flag indicating whether replanning is needed due to errors.
     * 
     * Set to true when:
     * - Last 3 consecutive outcomes are failures
     * - Critical error occurred
     * - User requested replanning
     */
    var errorFlagPlan: Boolean = false
        private set
    
    /**
     * Flag indicating task is complete.
     */
    var isFinished: Boolean = false
        private set
    
    // ===== State Mutations =====
    
    /**
     * Record an action that was executed.
     */
    fun recordAction(action: AgentAction) {
        actionHistory.add(action)
        turnCount++
        
        // Check if this was a finish action
        if (action is AgentAction.FinishAction) {
            // Don't set isFinished here - let Manager confirm
        }
    }
    
    /**
     * Record a validation outcome from Reflector.
     * 
     * Automatically triggers error flag if too many consecutive failures.
     */
    fun recordOutcome(outcome: ValidationOutcome) {
        outcomes.add(outcome)
        updateErrorFlag()
    }
    
    /**
     * Update the plan from Manager.
     */
    fun updatePlan(newPlan: String, subgoal: String = "") {
        plan = newPlan
        currentSubgoal = subgoal
        errorFlagPlan = false  // Reset error flag after replanning
        
        // Check if Manager says we're done
        if (newPlan.equals("Finished", ignoreCase = true) || 
            newPlan.contains("Finished", ignoreCase = true)) {
            isFinished = true
        }
    }
    
    /**
     * Record an error.
     */
    fun recordError(error: String) {
        errorDescriptions.add(error)
        errorFlagPlan = true  // Trigger replanning on error
    }
    
    /**
     * Mark task as finished.
     */
    fun markFinished() {
        isFinished = true
    }
    
    /**
     * Force replanning on next turn.
     */
    fun triggerReplan() {
        errorFlagPlan = true
    }
    
    /**
     * Reset error state after successful action.
     */
    fun clearErrors() {
        errorFlagPlan = false
        errorDescriptions.clear()
    }
    
    /**
     * Add to memory.
     */
    fun appendMemory(note: String) {
        if (memory.isNotEmpty()) {
            memory += "\n"
        }
        memory += note
    }
    
    // ===== Queries =====
    
    /**
     * Check if we should replan.
     */
    fun shouldReplan(): Boolean {
        return plan.isEmpty() || 
               errorFlagPlan ||
               actionHistory.lastOrNull() is AgentAction.FinishAction
    }
    
    /**
     * Get the last N actions for context.
     */
    fun getRecentActions(n: Int = 5): List<AgentAction> {
        return actionHistory.takeLast(n)
    }
    
    /**
     * Get the last outcome.
     */
    fun getLastOutcome(): ValidationOutcome? {
        return outcomes.lastOrNull()
    }
    
    /**
     * Get the last action.
     */
    fun getLastAction(): AgentAction? {
        return actionHistory.lastOrNull()
    }
    
    /**
     * Get success rate of recent actions.
     */
    fun getRecentSuccessRate(n: Int = 5): Float {
        val recent = outcomes.takeLast(n)
        if (recent.isEmpty()) return 1.0f
        
        val successes = recent.count { it is ValidationOutcome.Success }
        return successes.toFloat() / recent.size
    }
    
    /**
     * Check if stuck (many consecutive failures).
     */
    fun isStuck(threshold: Int = 3): Boolean {
        if (outcomes.size < threshold) return false
        return outcomes.takeLast(threshold).all { it !is ValidationOutcome.Success }
    }
    
    // ===== Private Helpers =====
    
    /**
     * Update error flag based on recent outcomes.
     */
    private fun updateErrorFlag() {
        // If last 3 outcomes are all failures, set error flag
        if (outcomes.size >= 3) {
            val last3 = outcomes.takeLast(3)
            val allFailures = last3.all { it !is ValidationOutcome.Success }
            if (allFailures) {
                errorFlagPlan = true
            }
        }
    }
    
    // ===== Debug =====
    
    /**
     * Get a summary for debugging.
     */
    fun getSummary(): String {
        return buildString {
            appendLine("=== SessionExecutionState ===")
            appendLine("Instruction: $instruction")
            appendLine("Plan: ${plan.take(100)}${if (plan.length > 100) "..." else ""}")
            appendLine("Current Subgoal: $currentSubgoal")
            appendLine("Turn Count: $turnCount")
            appendLine("Actions: ${actionHistory.size}")
            appendLine("Outcomes: ${outcomes.size}")
            appendLine("Error Flag: $errorFlagPlan")
            appendLine("Is Finished: $isFinished")
            if (errorDescriptions.isNotEmpty()) {
                appendLine("Errors: ${errorDescriptions.takeLast(3)}")
            }
        }
    }
}

/**
 * TurnPhase - Phases within a single turn.
 */
enum class TurnPhase {
    /** Capturing screen state */
    PERCEPTION,
    
    /** Verifying previous action's effect */
    REFLECTION,
    
    /** Creating/updating the plan */
    PLANNING,
    
    /** Selecting and executing an action */
    EXECUTION,
    
    /** Waiting for UI to settle */
    SETTLING
}

/**
 * TurnResult - Result of a single turn execution.
 */
sealed class TurnResult {
    /** 
     * Turn completed successfully, continue to next.
     * 
     * @param action The action that was executed
     * @param beforeSnapshot The screen state BEFORE the action was executed.
     *                       This will be used by the next turn's Reflector to compare
     *                       with the new screen state (after the action).
     */
    data class Continue(
        val action: AgentAction,
        val beforeSnapshot: com.moonkey.androidagent.domain.models.ScreenSnapshot
    ) : TurnResult()
    
    /** Task is complete */
    data class Finished(val reason: String) : TurnResult()
    
    /** Error occurred, may retry */
    data class Error(val message: String, val recoverable: Boolean) : TurnResult()
    
    /** Turn was interrupted */
    data object Interrupted : TurnResult()
    
    /** Turn was cancelled */
    data object Cancelled : TurnResult()
}



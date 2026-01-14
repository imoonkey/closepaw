package com.moonkey.androidagent.domain.state

import com.moonkey.androidagent.domain.models.AgentAction
import com.moonkey.androidagent.domain.models.ValidationOutcome
import java.util.Collections

/**
 * InfoPool - Centralized state management for the agent session. Modeled after Mobile-Agent-v3
 * InfoPool.
 */
data class InfoPool(
        val instruction: String,

        // Planning
        var plan: String = "",
        var currentSubgoal: String = "",

        // History (Thread-safe lists)
        val actionHistory: MutableList<AgentAction> = Collections.synchronizedList(mutableListOf()),
        val summaryHistory: MutableList<String> = Collections.synchronizedList(mutableListOf()),
        val outcomes: MutableList<ValidationOutcome> =
                Collections.synchronizedList(mutableListOf()),
        val errorDescriptions: MutableList<String> = Collections.synchronizedList(mutableListOf()),

        // Memory
        var textMemory: String = "", // Important notes

        // Status flags
        var errorFlagPlan: Boolean = false // If multiple errors occur, trigger heavy replanning
) {
    fun recordOutcome(outcome: ValidationOutcome) {
        outcomes.add(outcome)
        // If we have N failures in a row, set errorFlagPlan
        // basic heuristic: last 3 are failures
        if (outcomes.size >= 3) {
            val last3 = outcomes.takeLast(3)
            val allFailures = last3.all { it !is ValidationOutcome.Success }
            if (allFailures) {
                errorFlagPlan = true
            }
        } else {
            errorFlagPlan = false
        }
    }
}

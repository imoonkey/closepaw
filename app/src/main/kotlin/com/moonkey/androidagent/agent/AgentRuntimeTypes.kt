package com.moonkey.androidagent.agent

import com.moonkey.androidagent.agent.cognition.context.NavigationState

/** Reason why the agent stopped. */
sealed class AgentStopReason {
    data object GoalAchieved : AgentStopReason()
    data object UserRequested : AgentStopReason()
    data object MaxTurnsReached : AgentStopReason()
    data class Error(val message: String) : AgentStopReason()
}

/** Outcome of a single turn. */
sealed class TurnOutcome {
    data object Continue : TurnOutcome()
    data class Complete(val message: String) : TurnOutcome()
    data class Error(val message: String, val recoverable: Boolean) : TurnOutcome()
    data object Cancelled : TurnOutcome()
}

/**
 * Mutable runtime state carried across turns.
 *
 * This is intentionally small:
 * - `navigationState` powers loop detection heuristics
 * - `previousActionSignature` helps detect repeated actions
 */
internal data class TurnRunnerState(
    val navigationState: NavigationState = NavigationState(),
    val previousActionSignature: String? = null
)

/**
 * Full output of one `AgentTurnRunner.executeTurn()` call:
 * - `outcome`: control decision for the outer Agent loop
 * - `nextState`: state to feed into the next turn
 */
internal data class TurnExecutionResult(
    val outcome: TurnOutcome,
    val nextState: TurnRunnerState
)

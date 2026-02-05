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

internal data class TurnRunnerState(
    val navigationState: NavigationState = NavigationState(),
    val previousActionSignature: String? = null
)

internal data class TurnExecutionResult(
    val outcome: TurnOutcome,
    val nextState: TurnRunnerState
)

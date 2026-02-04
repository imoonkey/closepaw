package com.moonkey.androidagent.agent

import com.moonkey.androidagent.agent.cognition.context.NavigationState

internal data class TurnRunnerState(
    val navigationState: NavigationState = NavigationState(),
    val previousActionSignature: String? = null
)

internal data class TurnExecutionResult(
    val outcome: TurnOutcome,
    val nextState: TurnRunnerState
)

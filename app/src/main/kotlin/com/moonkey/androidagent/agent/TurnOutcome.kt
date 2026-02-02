package com.moonkey.androidagent.agent

/**
 * Outcome of a single turn.
 */
sealed class TurnOutcome {
    data object Continue : TurnOutcome()
    data class Complete(val message: String) : TurnOutcome()
    data class Error(val message: String, val recoverable: Boolean) : TurnOutcome()
    data object Cancelled : TurnOutcome()
}


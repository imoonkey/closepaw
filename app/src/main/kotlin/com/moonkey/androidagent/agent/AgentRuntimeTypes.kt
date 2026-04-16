package com.moonkey.androidagent.agent

import com.moonkey.androidagent.agent.cognition.context.NavigationState
import com.moonkey.androidagent.agent.cognition.policy.ToolArbitrationResult
import com.moonkey.androidagent.agent.cognition.policy.TurnToolPolicy
import com.moonkey.androidagent.tool.ToolCallResult
import com.moonkey.androidagent.tool.ToolName

/** Reason why the agent stopped. */
sealed class AgentStopReason {
    data class GoalAchieved(val message: String = "Goal achieved") : AgentStopReason()
    data object UserRequested : AgentStopReason()
    data object MaxTurnsReached : AgentStopReason()
    data class TaskImpossible(val message: String) : AgentStopReason()
    data class Error(val message: String) : AgentStopReason()
}

/** Outcome of a single turn. */
sealed class TurnOutcome {
    data object Continue : TurnOutcome()
    data class Complete(val message: String, val success: Boolean = true) : TurnOutcome()
    data class Error(val message: String, val recoverable: Boolean) : TurnOutcome()
    data object Cancelled : TurnOutcome()
}

/**
 * Mutable runtime state carried across turns.
 *
 * `navigationState` powers loop detection (stable-screen warning).
 */
internal data class TurnRunnerState(
    val navigationState: NavigationState = NavigationState()
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

/**
 * Outcome of executing the selected tool calls for a turn.
 *
 * Tracks which tools actually reached a terminal state (success/failure/cancelled)
 * so callers can distinguish "planned but not executed" from "executed and succeeded".
 */
internal data class ExecutionPhaseResult(
    val executedToolIds: Set<String>,
    val terminatedEarly: Boolean,
    val lastTerminalResult: ToolCallResult?
) {
    companion object {
        val EMPTY = ExecutionPhaseResult(
            executedToolIds = emptySet(),
            terminatedEarly = false,
            lastTerminalResult = null
        )
    }
}

/**
 * Maps the planning + execution results to the control-loop outcome.
 *
 * Only emits [TurnOutcome.Complete] when `complete_task` was planned AND actually executed.
 * If the execution loop aborted early (failure or cancellation) before reaching
 * `complete_task`, emits [TurnOutcome.Error] or [TurnOutcome.Cancelled] instead.
 */
internal fun decideTurnOutcome(
    policy: TurnToolPolicy,
    turnResult: TurnResult,
    arbitration: ToolArbitrationResult,
    execution: ExecutionPhaseResult
): TurnOutcome {
    if (execution.terminatedEarly) {
        return when (val last = execution.lastTerminalResult) {
            is ToolCallResult.Cancelled -> TurnOutcome.Cancelled
            is ToolCallResult.Error -> TurnOutcome.Error(
                message = last.error,
                recoverable = true
            )
            else -> TurnOutcome.Error(
                message = "Tool execution aborted before completion",
                recoverable = true
            )
        }
    }
    val completeTaskCall = turnResult.toolCalls.find { it.name == ToolName.CompleteTask.raw }
    if (completeTaskCall != null && completeTaskCall.id !in execution.executedToolIds) {
        return TurnOutcome.Error(
            message = "complete_task was planned but did not execute",
            recoverable = true
        )
    }
    val decision = policy.decideCompletion(turnResult, arbitration)
    if (!decision.shouldComplete) return TurnOutcome.Continue
    return TurnOutcome.Complete(
        message = decision.summary ?: "Goal achieved",
        success = decision.success
    )
}

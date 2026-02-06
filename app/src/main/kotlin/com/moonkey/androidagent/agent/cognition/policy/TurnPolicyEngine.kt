package com.moonkey.androidagent.agent.cognition.policy

import com.moonkey.androidagent.agent.ToolCallRequest
import com.moonkey.androidagent.agent.TurnResult

private const val COMPLETE_TASK_TOOL = "complete_task"

/**
 * Result of choosing which tool calls from one LLM turn should actually execute.
 *
 * The runtime currently executes at most one tool call per turn.
 */
internal data class ToolArbitrationResult(
    val selectedToolCalls: List<ToolCallRequest>,
    val selectedTool: ToolCallRequest?,
    val hasCompletionTool: Boolean,
    val hasNonCompletionTool: Boolean,
    val droppedToolCalls: List<ToolCallRequest>
)

/**
 * Result of deciding whether the current turn should end the whole task.
 */
internal data class CompletionDecision(
    val shouldComplete: Boolean,
    val summary: String?
)

/**
 * Turn-level policy for two questions:
 * 1) If the model returned multiple tool calls, which one do we execute?
 * 2) Should this turn be treated as task completion?
 */
internal class TurnPolicyEngine {
    /**
     * Arbitration rule:
     * - Prefer a non-`complete_task` action.
     * - Fallback to first tool call when completion is the only call.
     */
    fun arbitrateToolCalls(
        toolCalls: List<ToolCallRequest>
    ): ToolArbitrationResult {
        val hasCompletionTool = toolCalls.any { it.name == COMPLETE_TASK_TOOL }
        val hasNonCompletionTool = toolCalls.any { it.name != COMPLETE_TASK_TOOL }
        val selectedTool =
            toolCalls.firstOrNull { it.name != COMPLETE_TASK_TOOL }
                ?: toolCalls.firstOrNull()
        val selectedToolCalls = selectedTool?.let(::listOf) ?: emptyList()
        val droppedToolCalls = toolCalls.filterNot { call -> selectedToolCalls.contains(call) }
        return ToolArbitrationResult(
            selectedToolCalls = selectedToolCalls,
            selectedTool = selectedTool,
            hasCompletionTool = hasCompletionTool,
            hasNonCompletionTool = hasNonCompletionTool,
            droppedToolCalls = droppedToolCalls
        )
    }

    /**
     * Completion rule:
     * - Only complete when model says complete AND there is no remaining non-completion action.
     */
    fun decideCompletion(
        turnResult: TurnResult,
        arbitration: ToolArbitrationResult
    ): CompletionDecision {
        val shouldComplete = turnResult.isComplete && !arbitration.hasNonCompletionTool
        if (!shouldComplete) {
            return CompletionDecision(shouldComplete = false, summary = null)
        }
        val completeTaskCall = turnResult.toolCalls.find { it.name == COMPLETE_TASK_TOOL }
        val summary =
            completeTaskCall?.arguments?.optString("answer")?.takeIf { it.isNotBlank() }
                ?: completeTaskCall?.arguments?.optString("summary")
                ?: turnResult.content
                ?: "Goal achieved"
        return CompletionDecision(shouldComplete = true, summary = summary)
    }
}

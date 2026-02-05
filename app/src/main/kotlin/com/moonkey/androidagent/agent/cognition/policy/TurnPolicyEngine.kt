package com.moonkey.androidagent.agent.cognition.policy

import com.moonkey.androidagent.agent.ToolCallRequest
import com.moonkey.androidagent.agent.TurnResult
import com.moonkey.androidagent.agent.cognition.profile.CognitionProfile
import com.moonkey.androidagent.agent.cognition.profile.TurnPolicyMode

private const val COMPLETE_TASK_TOOL = "complete_task"

internal data class ToolArbitrationResult(
    val selectedToolCalls: List<ToolCallRequest>,
    val selectedTool: ToolCallRequest?,
    val hasCompletionTool: Boolean,
    val hasNonCompletionTool: Boolean,
    val droppedToolCalls: List<ToolCallRequest>
)

internal data class CompletionDecision(
    val shouldComplete: Boolean,
    val summary: String?
)

internal class TurnPolicyEngine {
    fun arbitrateToolCalls(
        toolCalls: List<ToolCallRequest>,
        profile: CognitionProfile
    ): ToolArbitrationResult {
        val hasCompletionTool = toolCalls.any { it.name == COMPLETE_TASK_TOOL }
        val hasNonCompletionTool = toolCalls.any { it.name != COMPLETE_TASK_TOOL }
        val selectedTool =
            when (profile.turnPolicyMode) {
                TurnPolicyMode.BASELINE,
                TurnPolicyMode.PREFER_NON_COMPLETION_SINGLE_TOOL -> {
                    toolCalls.firstOrNull { it.name != COMPLETE_TASK_TOOL }
                        ?: toolCalls.firstOrNull()
                }
            }
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

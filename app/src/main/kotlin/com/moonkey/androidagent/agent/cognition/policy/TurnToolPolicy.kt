package com.moonkey.androidagent.agent.cognition.policy

import com.moonkey.androidagent.agent.ToolCallRequest
import com.moonkey.androidagent.agent.TurnResult
import com.moonkey.androidagent.tool.ToolName

private val COMPLETE_TASK_TOOL = ToolName.CompleteTask.raw

/**
 * Result of choosing which tool calls from one LLM turn should actually execute.
 *
 * The runtime may execute multiple cognitive and screen-changing tools per turn.
 * Navigation isolation (single-action for click/back/open_app) is enforced at the prompt layer.
 */
internal data class ToolArbitrationResult(
        val selectedToolCalls: List<ToolCallRequest>,
        val hasCompletionTool: Boolean,
        val hasScreenAction: Boolean,
        val droppedToolCalls: List<ToolCallRequest>
)

/** Result of deciding whether the current turn should end the whole task. */
internal data class CompletionDecision(
        val shouldComplete: Boolean,
        val summary: String?,
        val success: Boolean
)

/**
 * Turn-level policy for two questions: 1) If the model returned multiple tool calls, which one do
 * we execute? 2) Should this turn be treated as task completion?
 */
internal class TurnToolPolicy {
    /**
     * Arbitration rule:
     * - Keep all non-screen-changing (cognitive) tools.
     * - Keep all screen-changing tools (multi-action per turn).
     * - Keep `complete_task` only when no screen-changing tool is selected.
     *
     * Navigation isolation (click-to-navigate, back, open_app should be alone)
     * is enforced at the prompt layer, not here.
     */
    fun arbitrateToolCalls(
        toolCalls: List<ToolCallRequest>
    ): ToolArbitrationResult {
        if (toolCalls.isEmpty()) {
            return ToolArbitrationResult(
                selectedToolCalls = emptyList(),
                hasCompletionTool = false,
                hasScreenAction = false,
                droppedToolCalls = emptyList()
            )
        }

        val hasCompletionTool = toolCalls.any { it.name == COMPLETE_TASK_TOOL }
        val completionCall = toolCalls.find { it.name == COMPLETE_TASK_TOOL }
        val screenCalls =
                toolCalls.filter { call ->
                        call.name != COMPLETE_TASK_TOOL && ToolName.from(call.name).isScreenChanging
                }
        val cognitiveCalls =
                toolCalls.filter { call ->
                        call.name != COMPLETE_TASK_TOOL &&
                                !ToolName.from(call.name).isScreenChanging
                }

        val hasScreenAction = screenCalls.isNotEmpty()
        val selectedCompletion = if (!hasScreenAction) completionCall else null
        val selectedToolCalls =
                buildList {
                        addAll(cognitiveCalls)
                        addAll(screenCalls)
                        selectedCompletion?.let(::add)
                }

        val selectedToolIds = selectedToolCalls.map { it.id }.toSet()
        val droppedToolCalls = toolCalls.filterNot { it.id in selectedToolIds }

        return ToolArbitrationResult(
                selectedToolCalls = selectedToolCalls,
                hasCompletionTool = hasCompletionTool,
                hasScreenAction = hasScreenAction,
                droppedToolCalls = droppedToolCalls
        )
    }

    /**
     * Completion rule:
     * - Only complete when model says complete AND no screen action is selected this turn.
     */
    fun decideCompletion(
            turnResult: TurnResult,
            arbitration: ToolArbitrationResult
    ): CompletionDecision {
        val shouldComplete = turnResult.isComplete && !arbitration.hasScreenAction
        if (!shouldComplete) {
            return CompletionDecision(shouldComplete = false, summary = null, success = false)
        }
        val completeTaskCall = turnResult.toolCalls.find { it.name == COMPLETE_TASK_TOOL }
        val status = completeTaskCall?.arguments?.optString("status", "success")?.trim()?.lowercase()
        val success = status != "failure"
        val summary =
                completeTaskCall?.arguments?.optString("answer")?.takeIf { it.isNotBlank() }
                        ?: completeTaskCall?.arguments?.optString("summary") ?: turnResult.content
                                ?: "Goal achieved"
        return CompletionDecision(shouldComplete = true, summary = summary, success = success)
    }
}

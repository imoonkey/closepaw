package ai.closepaw.agent.cognition.policy

import ai.closepaw.agent.ToolCallRequest
import ai.closepaw.agent.TurnResult
import ai.closepaw.tool.ToolName

private val COMPLETE_TASK_TOOL = ToolName.CompleteTask.raw
private val HOISTABLE_TOOL_NAMES =
        setOf(
                ToolName.Scratchpad.canonical,
                ToolName.WriteTodos.canonical,
                ToolName.RememberExperience.canonical
        )

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
     * - Hoist only cognitive tools.
     * - Keep other selected tools in model order.
     * - Keep `complete_task` only when no screen-changing tool is selected.
     *
     * Shell-like tools are not hoistable; they keep the LLM's ordering.
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

        val completionCall = toolCalls.find { it.name == COMPLETE_TASK_TOOL }
        val hasCompletionTool = completionCall != null
        val nonCompletionCalls = toolCalls.filter { it.name != COMPLETE_TASK_TOOL }
        val screenCalls =
                nonCompletionCalls.filter { call ->
                        ToolName.from(call.name).isScreenChanging
                }
        val hoistableCalls =
                nonCompletionCalls.filter { call ->
                        ToolName.from(call.name).canonical in HOISTABLE_TOOL_NAMES
                }
        val orderedCalls =
                nonCompletionCalls.filterNot { call ->
                        ToolName.from(call.name).canonical in HOISTABLE_TOOL_NAMES
                }

        val hasScreenAction = screenCalls.isNotEmpty()
        val selectedCompletion = if (!hasScreenAction) completionCall else null
        val selectedToolCalls =
                buildList {
                        addAll(hoistableCalls)
                        addAll(orderedCalls)
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

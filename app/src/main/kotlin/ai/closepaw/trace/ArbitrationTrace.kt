package ai.closepaw.trace

import ai.closepaw.agent.ToolCallRequest

internal data class ArbitrationDecision(
    val selectedTools: List<ToolCallRequest>,
    val droppedToolCalls: List<DroppedToolCall>,
    val selectedToolCount: Int,
    val originalToolCount: Int
)

internal data class DroppedToolCall(
    val toolName: String,
    val reason: DropReason
)

internal enum class DropReason {
    COMPLETE_TASK_DEFERRED,
    DUPLICATE_TOOL,
    POLICY_REJECTION,
    MAX_TOOLS_EXCEEDED
}

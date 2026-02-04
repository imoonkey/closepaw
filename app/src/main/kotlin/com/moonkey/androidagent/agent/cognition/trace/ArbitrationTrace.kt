package com.moonkey.androidagent.agent.cognition.trace

import com.moonkey.androidagent.agent.ToolCallRequest
import com.moonkey.androidagent.agent.cognition.profile.TurnPolicyMode

internal data class ArbitrationDecision(
    val selectedTool: ToolCallRequest?,
    val droppedToolCalls: List<DroppedToolCall>,
    val policyMode: TurnPolicyMode,
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

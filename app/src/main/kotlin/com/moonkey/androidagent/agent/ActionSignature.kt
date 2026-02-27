package com.moonkey.androidagent.agent

import com.moonkey.androidagent.tool.MobileActionName
import com.moonkey.androidagent.tool.ToolName

/**
 * Produces a stable action signature string for a tool call.
 *
 * Used by loop detection and tool arbitration to block repeated failing actions.
 * Signatures should be specific enough to avoid over-blocking unrelated actions.
 *
 * Format examples:
 * - "mobile_action:click"
 * - "mobile_action:click:idx=12"
 * - "mobile_action:click:text=save"
 * - "scroll:down"
 * - "open_app:markor"
 * - "shell"
 */
internal fun classifyActionSignature(toolCall: ToolCallRequest): String {
        when (ToolName.from(toolCall.name)) {
                ToolName.Wait -> return "mobile_action:wait"
                ToolName.SystemButton -> {
                        val button = toolCall.arguments.optString("button", "").trim().lowercase()
                        return "mobile_action:system_button:${button.ifBlank { "unknown" }}"
                }
                ToolName.OpenApp -> {
                        val appName = toolCall.arguments.optString("app_name", "").trim().lowercase()
                        return "open_app:${appName.ifBlank { "unknown" }}"
                }
                else -> Unit
        }

        if (toolCall.name != ToolName.MobileAction.raw) {
                return toolCall.name.lowercase()
        }

        val action = toolCall.arguments.optString("action", "").trim().lowercase()
        val mobileActionName = MobileActionName.from(action)
        return when (mobileActionName) {
                MobileActionName.Scroll -> {
                        val direction =
                                toolCall.arguments
                                        .optString("direction", "")
                                        .trim()
                                        .lowercase()
                        "scroll:${direction.ifBlank { "unknown" }}"
                }
                MobileActionName.Click -> {
                        val targetSuffix = actionTargetSuffix(toolCall)
                        if (targetSuffix != null) "mobile_action:click:$targetSuffix" else "mobile_action:click"
                }
                MobileActionName.Type -> {
                        val targetSuffix = actionTargetSuffix(toolCall)
                        if (targetSuffix != null) "mobile_action:type:$targetSuffix" else "mobile_action:type"
                }
                MobileActionName.Swipe -> "mobile_action:swipe"
                else -> "mobile_action:${mobileActionName.canonical}"
        }
}

/**
 * Picks the action signature that should feed loop detection for the next turn.
 *
 * Priority:
 * 1) first screen-changing tool call
 * 2) first non-screen-changing tool call (fallback)
 */
internal fun selectActionSignatureForNextTurn(toolCallsToExecute: List<ToolCallRequest>): String? {
        if (toolCallsToExecute.isEmpty()) return null

        val firstScreenChanging =
                toolCallsToExecute.firstOrNull { ToolName.from(it.name).isScreenChanging }
        return when {
                firstScreenChanging != null -> classifyActionSignature(firstScreenChanging)
                else -> classifyActionSignature(toolCallsToExecute.first())
        }
}

private fun actionTargetSuffix(toolCall: ToolCallRequest): String? {
        if (toolCall.arguments.has("element_index")) {
                return "idx=${toolCall.arguments.optInt("element_index", -1)}"
        }
        val text = toolCall.arguments.optString("text", "").trim().lowercase()
        if (text.isNotBlank()) {
                return "text=${text.take(32)}"
        }
        val x = toolCall.arguments.optInt("x", Int.MIN_VALUE)
        val y = toolCall.arguments.optInt("y", Int.MIN_VALUE)
        if (x != Int.MIN_VALUE && y != Int.MIN_VALUE) {
                return "xy=$x,$y"
        }
        return null
}

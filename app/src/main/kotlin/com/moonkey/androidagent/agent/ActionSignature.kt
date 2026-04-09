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
                        val suffix = actionTargetSuffix(toolCall)
                        if (suffix != null) "mobile_action:click:$suffix" else "mobile_action:click"
                }
                MobileActionName.Type -> {
                        val suffix = actionTargetSuffix(toolCall)
                        if (suffix != null) "mobile_action:type:$suffix" else "mobile_action:type"
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
        val action = toolCall.arguments.optString("action", "").trim().lowercase()
        val target = decodeActionTarget(toolCall.arguments, action)
        return when {
                target.elementIndex != null -> "idx=${target.elementIndex}"
                target.text.isNotEmpty() -> "text=${target.text.lowercase().take(32)}"
                target.point != null -> "xy=${target.point.x},${target.point.y}"
                else -> null
        }
}

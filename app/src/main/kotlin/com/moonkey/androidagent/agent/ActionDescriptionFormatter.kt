package com.moonkey.androidagent.agent

import org.json.JSONObject

/**
 * Format a human-readable description for a tool call.
 */
object ActionDescriptionFormatter {
    fun format(toolCall: ToolCallRequest): String {
        return when (toolCall.name.lowercase()) {
            "mobile_action" -> formatMobileAction(toolCall.arguments)
            "app_control" -> formatAppControl(toolCall.arguments)
            "complete_task" -> formatCompleteTask(toolCall.arguments)
            else -> "Execute ${toolCall.name}"
        }
    }

    private fun formatMobileAction(args: JSONObject): String {
        val action = args.optString("action", "")
        return when (action) {
            "click" -> "Click element ${args.optInt("element_index", -1)}"
            "long_press" -> "Long press element ${args.optInt("element_index", -1)}"
            "type" -> {
                val text = args.optString("text", "").take(30)
                "Type \"$text\""
            }
            "swipe" -> {
                val start = args.optJSONArray("start")
                val end = args.optJSONArray("end")
                if (start != null && end != null) {
                    "Swipe from (${start.optInt(0)},${start.optInt(1)}) to (${end.optInt(0)},${end.optInt(1)})"
                } else {
                    "Swipe gesture"
                }
            }
            "system_button" -> "Press ${args.optString("button", "")} button"
            "wait" -> "Wait ${args.optLong("duration_ms", 1000)}ms"
            else -> "Mobile action: $action"
        }
    }

    private fun formatAppControl(args: JSONObject): String {
        val action = args.optString("action", "")
        return when (action) {
            "list_apps" -> {
                val filter = args.optString("filter", "")
                if (filter.isNotEmpty()) "List apps matching '$filter'" else "List all apps"
            }
            "open_app" -> {
                val name = args.optString("app_name", "")
                val pkg = args.optString("package_name", "")
                "Open app: ${name.ifEmpty { pkg }}"
            }
            else -> "App control: $action"
        }
    }

    private fun formatCompleteTask(args: JSONObject): String {
        val status = args.optString("status", "")
        val answer = args.optString("answer", "").take(50)
        return "Complete ($status): $answer"
    }
}

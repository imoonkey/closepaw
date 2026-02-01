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
            "click" -> {
                val resourceId = args.optString("resource_id", "").trim()
                val text = args.optString("text", "").trim()
                val hasBounds = args.has("x1") && args.has("y1") && args.has("x2") && args.has("y2")
                val hasPoint = args.has("x") && args.has("y")
                when {
                    resourceId.isNotEmpty() ->
                        "Click resource_id '$resourceId' (index ${args.optInt("resource_id_index", 0)})"
                    text.isNotEmpty() ->
                        "Click text \"$text\" (index ${args.optInt("text_index", 0)})"
                    hasBounds -> {
                        val x1 = args.optInt("x1", -1)
                        val y1 = args.optInt("y1", -1)
                        val x2 = args.optInt("x2", -1)
                        val y2 = args.optInt("y2", -1)
                        "Click bounds ($x1,$y1)-($x2,$y2)"
                    }
                    hasPoint -> "Click at (${args.optInt("x", -1)},${args.optInt("y", -1)})"
                    else -> "Click element ${args.optInt("element_index", -1)}"
                }
            }
            "long_press" -> "Long press element ${args.optInt("element_index", -1)}"
            "type" -> {
                val text = args.optString("text", "").take(30)
                val clear = args.optBoolean("clear", false)
                "Type \"${text}\"${if (clear) " (clear first)" else ""}"
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

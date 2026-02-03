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
            "long_press" -> {
                val resourceId = args.optString("resource_id", "").trim()
                val text = args.optString("text", "").trim()
                val hasBounds = args.has("x1") && args.has("y1") && args.has("x2") && args.has("y2")
                val hasPoint = args.has("x") && args.has("y")
                val durationMs = args.optLong("duration_ms", 1000)
                when {
                    resourceId.isNotEmpty() ->
                        "Long press resource_id '$resourceId' (index ${args.optInt("resource_id_index", 0)}) for ${durationMs}ms"
                    text.isNotEmpty() ->
                        "Long press text \"$text\" (index ${args.optInt("text_index", 0)}) for ${durationMs}ms"
                    hasBounds -> {
                        val x1 = args.optInt("x1", -1)
                        val y1 = args.optInt("y1", -1)
                        val x2 = args.optInt("x2", -1)
                        val y2 = args.optInt("y2", -1)
                        "Long press bounds ($x1,$y1)-($x2,$y2) for ${durationMs}ms"
                    }
                    hasPoint -> "Long press at (${args.optInt("x", -1)},${args.optInt("y", -1)}) for ${durationMs}ms"
                    else -> "Long press element ${args.optInt("element_index", -1)} for ${durationMs}ms"
                }
            }
            "type" -> {
                val text = args.optString("text", "").take(30)
                val clear = args.optBoolean("clear", false)
                val resourceId = args.optString("resource_id", "").trim()
                val targetText = args.optString("target_text", "").trim()
                val hasBounds = args.has("x1") && args.has("y1") && args.has("x2") && args.has("y2")
                val hasPoint = args.has("x") && args.has("y")
                val hasElementIndex = args.has("element_index") && args.optInt("element_index", -1) >= 0
                val target = when {
                    resourceId.isNotEmpty() ->
                        "resource_id '$resourceId' (index ${args.optInt("resource_id_index", 0)})"
                    targetText.isNotEmpty() ->
                        "target_text \"$targetText\" (index ${args.optInt("target_text_index", args.optInt("text_index", 0))})"
                    hasBounds -> {
                        val x1 = args.optInt("x1", -1)
                        val y1 = args.optInt("y1", -1)
                        val x2 = args.optInt("x2", -1)
                        val y2 = args.optInt("y2", -1)
                        "bounds ($x1,$y1)-($x2,$y2)"
                    }
                    hasPoint -> "coordinates (${args.optInt("x", -1)},${args.optInt("y", -1)})"
                    hasElementIndex -> "element ${args.optInt("element_index", -1)}"
                    else -> "focused field"
                }
                "Type \"${text}\" into $target${if (clear) " (clear first)" else ""}"
            }
            "swipe" -> {
                val direction = args.optString("direction", "").trim()
                val start = args.optJSONArray("start")
                val end = args.optJSONArray("end")
                if (start != null && end != null && direction.isEmpty()) {
                    "Swipe from (${start.optInt(0)},${start.optInt(1)}) to (${end.optInt(0)},${end.optInt(1)})"
                } else if (direction.isNotEmpty()) {
                    val distance = args.optString("distance", "medium").trim().ifEmpty { "medium" }
                    val target = when {
                        args.optString("resource_id", "").trim().isNotEmpty() ->
                            "resource_id '${args.optString("resource_id", "").trim()}' (index ${args.optInt("resource_id_index", 0)})"
                        args.optString("text", "").trim().isNotEmpty() ->
                            "text \"${args.optString("text", "").trim()}\" (index ${args.optInt("text_index", 0)})"
                        args.has("x1") && args.has("y1") && args.has("x2") && args.has("y2") -> {
                            val x1 = args.optInt("x1", -1)
                            val y1 = args.optInt("y1", -1)
                            val x2 = args.optInt("x2", -1)
                            val y2 = args.optInt("y2", -1)
                            "bounds ($x1,$y1)-($x2,$y2)"
                        }
                        args.has("x") && args.has("y") -> "coordinates (${args.optInt("x", -1)},${args.optInt("y", -1)})"
                        args.has("element_index") -> "element ${args.optInt("element_index", -1)}"
                        else -> ""
                    }
                    if (target.isNotEmpty()) {
                        "Swipe $direction ($distance) from $target"
                    } else {
                        "Swipe $direction ($distance)"
                    }
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

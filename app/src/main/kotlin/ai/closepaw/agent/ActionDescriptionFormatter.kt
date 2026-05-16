package ai.closepaw.agent

import org.json.JSONObject

/**
 * Format a human-readable description for a tool call.
 */
object ActionDescriptionFormatter {
    fun format(toolCall: ToolCallRequest): String {
        return when (toolCall.name.lowercase()) {
            "mobile_action" -> formatMobileAction(toolCall.arguments)
            "wait" -> "Wait ${toolCall.arguments.optLong("duration_ms", 1000)}ms"
            "system_button" -> "Press ${toolCall.arguments.optString("button", "")} button"
            "open_app" -> formatOpenApp(toolCall.arguments)
            "complete_task" -> formatCompleteTask(toolCall.arguments)
            else -> "Execute ${toolCall.name}"
        }
    }

    /**
     * Convert an [ActionTarget] to a human-readable string.
     *
     * @param pointLabel  word used before point coordinates ("at" for click, "coordinates" for type/swipe)
     * @param fallback    string when no target fields are present (null → "element -1")
     */
    private fun targetToString(
        target: ActionTarget,
        pointLabel: String = "at",
        fallback: String? = null,
    ): String = when {
        target.elementIndex != null ->
            "element ${target.elementIndex}"
        target.text.isNotEmpty() ->
            "text \"${target.text}\" (index ${target.textIndex})"
        target.bounds != null ->
            "bounds (${target.bounds.x1},${target.bounds.y1})-(${target.bounds.x2},${target.bounds.y2})"
        target.point != null ->
            "$pointLabel (${target.point.x},${target.point.y})"
        else ->
            fallback ?: "element -1"
    }

    private fun formatMobileAction(args: JSONObject): String {
        val action = args.optString("action", "")
        return when (action) {
            "click" -> {
                val target = decodeActionTarget(args)
                "Click ${targetToString(target)}"
            }
            "long_press" -> {
                val target = decodeActionTarget(args)
                "Long press ${targetToString(target)} for ${args.optLong("duration_ms", 1000)}ms"
            }
            "type" -> {
                val target = decodeActionTarget(args, action = "type")
                val hasInputText = args.has("input_text")
                val textToType = args.optString(if (hasInputText) "input_text" else "text", "").take(30)
                val clear = args.optBoolean("clear", false)
                val targetStr = targetToString(target, pointLabel = "coordinates", fallback = "focused field")
                "Type \"$textToType\" into $targetStr${if (clear) " (clear first)" else ""}"
            }
            "swipe" -> formatSwipeAction(args)
            else -> "Mobile action: $action"
        }
    }

    private fun formatOpenApp(args: JSONObject): String {
        val name = args.optString("app_name", "")
        return if (name.isNotEmpty()) "Open app: $name" else "Open app"
    }

    private fun formatSwipeAction(args: JSONObject): String {
        val direction = args.optString("direction", "").trim()
        val start = args.optJSONArray("start")
        val end = args.optJSONArray("end")

        if (start != null && end != null && direction.isEmpty()) {
            return "Swipe from (${start.optInt(0)},${start.optInt(1)}) to (${end.optInt(0)},${end.optInt(1)})"
        }
        if (direction.isEmpty()) return "Swipe gesture"

        val distance = args.optString("distance", "medium").trim().ifEmpty { "medium" }
        val target = decodeActionTarget(args)
        val targetStr = targetToString(target, pointLabel = "coordinates", fallback = "")

        return if (targetStr.isNotEmpty()) {
            "Swipe $direction ($distance) from $targetStr"
        } else {
            "Swipe $direction ($distance)"
        }
    }

    private fun formatCompleteTask(args: JSONObject): String {
        val status = args.optString("status", "")
        val answer = args.optString("answer", "")
        return "Complete ($status): $answer"
    }
}

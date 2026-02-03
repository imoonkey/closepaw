package com.moonkey.androidagent.tool.impl.mobileaction

import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.handlers.ActionHandler
import com.moonkey.androidagent.tool.handlers.SwipeTargetInvocation
import com.moonkey.androidagent.tool.handlers.UIActionInvocation
import org.json.JSONObject

/**
 * Swipe action - either explicit start/end coordinates or semantic direction + distance.
 */
class SwipeActionHandler : ActionHandler {
    override val actionName = "swipe"

    companion object {
        private const val DEFAULT_SWIPE_DURATION_MS = 300L
        private val VALID_DIRECTIONS = setOf("up", "down", "left", "right")
        private val VALID_DISTANCES = setOf("short", "medium", "long")
    }

    override fun validate(params: JSONObject): ValidationResult {
        val hasStart = params.has("start")
        val hasEnd = params.has("end")
        val direction = params.optString("direction", "").trim().lowercase()
        val hasDirection = direction.isNotEmpty()

        if ((hasStart || hasEnd) && hasDirection) {
            return ValidationResult.Invalid("Provide either start/end or direction, not both")
        }

        // Check start coordinate
        if (hasStart || hasEnd) {
            if (!hasStart) {
                return ValidationResult.Invalid("swipe action requires start coordinate [x, y]")
            }
            val start = params.optJSONArray("start")
            if (start == null || start.length() != 2) {
                return ValidationResult.Invalid("start must be an array of [x, y]")
            }

            // Check end coordinate
            if (!hasEnd) {
                return ValidationResult.Invalid("swipe action requires end coordinate [x, y]")
            }
            val end = params.optJSONArray("end")
            if (end == null || end.length() != 2) {
                return ValidationResult.Invalid("end must be an array of [x, y]")
            }

            // Validate coordinates are non-negative
            try {
                val sx = start.getInt(0)
                val sy = start.getInt(1)
                val ex = end.getInt(0)
                val ey = end.getInt(1)

                if (sx < 0 || sy < 0 || ex < 0 || ey < 0) {
                    return ValidationResult.Invalid("Coordinates must be non-negative")
                }
            } catch (e: Exception) {
                return ValidationResult.Invalid("Coordinates must be integers")
            }

            return ValidationResult.Valid
        }

        if (!hasDirection) {
            return ValidationResult.Invalid("swipe action requires start/end coordinates or direction")
        }

        if (!VALID_DIRECTIONS.contains(direction)) {
            return ValidationResult.Invalid("direction must be one of: up, down, left, right")
        }

        val distance = params.optString("distance", "").trim().lowercase()
        if (distance.isNotEmpty() && !VALID_DISTANCES.contains(distance)) {
            return ValidationResult.Invalid("distance must be one of: short, medium, long")
        }

        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val hasStart = params.has("start")
        val hasEnd = params.has("end")
        val hasDirection = params.optString("direction", "").trim().isNotEmpty()
        val description = buildDescription(params)

        if (hasStart && hasEnd && !hasDirection) {
            val start = params.getJSONArray("start")
            val end = params.getJSONArray("end")
            val durationMs = params.optLong("duration_ms", DEFAULT_SWIPE_DURATION_MS)

            val sx = start.getInt(0)
            val sy = start.getInt(1)
            val ex = end.getInt(0)
            val ey = end.getInt(1)

            return UIActionInvocation(
                toolName = "mobile_action",
                params = params,
                description = description,
                uiAction = UIAction.Swipe(sx, sy, ex, ey, durationMs)
            )
        }

        return SwipeTargetInvocation(params = params, description = description)
    }

    private fun buildDescription(params: JSONObject): String {
        val direction = params.optString("direction", "").trim().lowercase()
        val distance = params.optString("distance", "medium").trim().lowercase()
        val start = params.optJSONArray("start")
        val end = params.optJSONArray("end")
        if (start != null && end != null && direction.isEmpty()) {
            return "Swipe from (${start.optInt(0)},${start.optInt(1)}) to (${end.optInt(0)},${end.optInt(1)})"
        }

        if (direction.isEmpty()) {
            return "Swipe gesture"
        }

        val target = when {
            params.optString("resource_id", "").trim().isNotEmpty() -> {
                val resourceId = params.optString("resource_id", "").trim()
                "resource_id '$resourceId' (index ${params.optInt("resource_id_index", 0)})"
            }
            params.optString("text", "").trim().isNotEmpty() -> {
                val text = params.optString("text", "").trim()
                "text \"$text\" (index ${params.optInt("text_index", 0)})"
            }
            params.has("x1") && params.has("y1") && params.has("x2") && params.has("y2") -> {
                val x1 = params.optInt("x1", -1)
                val y1 = params.optInt("y1", -1)
                val x2 = params.optInt("x2", -1)
                val y2 = params.optInt("y2", -1)
                "bounds ($x1,$y1)-($x2,$y2)"
            }
            params.has("x") && params.has("y") -> {
                "coordinates (${params.optInt("x", -1)},${params.optInt("y", -1)})"
            }
            params.has("element_index") -> {
                "element ${params.optInt("element_index", -1)}"
            }
            else -> ""
        }

        val distanceLabel = if (distance.isNotEmpty()) distance else "medium"
        return if (target.isNotEmpty()) {
            "Swipe $direction ($distanceLabel) from $target"
        } else {
            "Swipe $direction ($distanceLabel)"
        }
    }
}

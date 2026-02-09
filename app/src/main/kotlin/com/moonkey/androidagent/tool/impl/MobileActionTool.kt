package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolSpec
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.action.ClickExecutor
import com.moonkey.androidagent.tool.action.LongPressExecutor
import com.moonkey.androidagent.tool.action.SwipeExecutor
import com.moonkey.androidagent.tool.action.Target
import com.moonkey.androidagent.tool.action.TypeExecutor
import org.json.JSONArray
import org.json.JSONObject

/**
 * MobileActionTool — consolidated tool for all touch interactions.
 *
 * Implements ToolSpec directly. No base class, no ActionHandler indirection.
 * Validation is inline; execution is delegated to per-action executors.
 *
 * Key design: single targeting. One action, one target.
 * element_index OR text OR x,y. Multiple = validation error.
 */
class MobileActionTool : ToolSpec {

    override val name: String = "mobile_action"

    override val description: String = """
Perform touch interactions on the mobile device screen.

Targeting (for click, long_press, type):
Specify EXACTLY ONE targeting method per action:
- element_index: index from current screen state (preferred)
- text + text_index: visible text on screen
- x, y: absolute pixel coordinates (last resort)

Actions:
- click: Tap target. Example: {"action":"click","element_index":3}
- long_press: Long press target. Example: {"action":"long_press","text":"Delete"}
- type: Type text. Example: {"action":"type","input_text":"hello","element_index":5}
- swipe: Swipe gesture. Example: {"action":"swipe","direction":"up"}
""".trimIndent()

    override val parameterSchema: JSONObject by lazy { buildSchema() }

    override fun validate(params: JSONObject): ValidationResult {
        val action = params.optString("action", "")
        if (action.isEmpty()) return ValidationResult.Invalid("Missing required parameter: action")

        // Reject legacy bounds selector — removed in v2 design
        if (params.has("x1") || params.has("y1") || params.has("x2") || params.has("y2")) {
            return ValidationResult.Invalid(
                "Bounds selector (x1/y1/x2/y2) is no longer supported. " +
                "Use element_index, text, or x/y instead."
            )
        }

        return when (action) {
            "click", "long_press" -> validateTargetedAction(params, action, required = true)
            "type" -> validateTypeAction(params)
            "swipe" -> validateSwipeAction(params)
            else -> ValidationResult.Invalid("Unknown action: '$action'. Valid: click, long_press, type, swipe")
        }
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val action = params.getString("action")
        val target = parseOptionalTarget(params)
        val description = buildDescription(action, target, params)

        return MobileActionInvocation(params, description) { platform, snapshot, isCancelled ->
            when (action) {
                "click" -> ClickExecutor().execute(target!!, snapshot, platform, isCancelled)
                "long_press" -> LongPressExecutor().execute(
                    target!!, params.optLong("duration_ms", 1000), snapshot, platform, isCancelled
                )
                "type" -> TypeExecutor().execute(
                    target, params.getString("input_text"),
                    params.optBoolean("clear", false), snapshot, platform, isCancelled
                )
                "swipe" -> SwipeExecutor().execute(params, snapshot, platform, isCancelled)
                else -> error("Unreachable: validated above")
            }
        }
    }

    // ============================================================
    // Validation: one-of target enforcement
    // ============================================================

    private fun validateTargetedAction(
        params: JSONObject, action: String, required: Boolean
    ): ValidationResult {
        val hasElement = params.has("element_index")
        val hasText = params.optString("text", "").trim().isNotEmpty()
        val hasCoords = params.has("x") || params.has("y")
        val count = listOf(hasElement, hasText, hasCoords).count { it }

        if (count == 0 && required) {
            return ValidationResult.Invalid(
                "$action requires one of: element_index, text, or x/y coordinates"
            )
        }
        if (count > 1) {
            return ValidationResult.Invalid(
                "$action accepts only ONE targeting method. Got: ${targetNames(hasElement, hasText, hasCoords)}"
            )
        }

        if (hasElement) {
            val idx = params.optInt("element_index", -1)
            if (idx < 0) return ValidationResult.Invalid("element_index must be >= 0")
        }
        if (hasCoords) {
            if (!params.has("x") || !params.has("y")) {
                return ValidationResult.Invalid("$action requires both x and y when using coordinates")
            }
            if (params.optInt("x", -1) < 0 || params.optInt("y", -1) < 0) {
                return ValidationResult.Invalid("x and y must be >= 0")
            }
        }
        if (hasText && params.has("text_index") && !hasText) {
            return ValidationResult.Invalid("text_index requires text")
        }

        if (action == "long_press") {
            val duration = params.optLong("duration_ms", 1000)
            if (duration < 0) return ValidationResult.Invalid("duration_ms must be non-negative")
            if (duration > 30000) return ValidationResult.Invalid("duration_ms must be <= 30000")
        }

        return ValidationResult.Valid
    }

    private fun validateTypeAction(params: JSONObject): ValidationResult {
        if (!params.has("input_text")) {
            return ValidationResult.Invalid("type action requires input_text")
        }
        // Type allows no target (types into focused field)
        return validateTargetedAction(params, "type", required = false)
    }

    private fun validateSwipeAction(params: JSONObject): ValidationResult {
        val hasStart = params.has("start")
        val hasEnd = params.has("end")
        val direction = params.optString("direction", "").trim().lowercase()
        val hasDirection = direction.isNotEmpty()

        if ((hasStart || hasEnd) && hasDirection) {
            return ValidationResult.Invalid("Provide either start/end or direction, not both")
        }

        if (hasStart || hasEnd) {
            if (!hasStart) return ValidationResult.Invalid("swipe requires start coordinate [x, y]")
            val start = params.optJSONArray("start")
            if (start == null || start.length() != 2) {
                return ValidationResult.Invalid("start must be an array of [x, y]")
            }
            if (!hasEnd) return ValidationResult.Invalid("swipe requires end coordinate [x, y]")
            val end = params.optJSONArray("end")
            if (end == null || end.length() != 2) {
                return ValidationResult.Invalid("end must be an array of [x, y]")
            }
            try {
                val sx = start.getInt(0); val sy = start.getInt(1)
                val ex = end.getInt(0); val ey = end.getInt(1)
                if (sx < 0 || sy < 0 || ex < 0 || ey < 0) {
                    return ValidationResult.Invalid("Coordinates must be non-negative")
                }
            } catch (e: Exception) {
                return ValidationResult.Invalid("Coordinates must be integers")
            }
            return ValidationResult.Valid
        }

        if (!hasDirection) {
            return ValidationResult.Invalid("swipe requires start/end coordinates or direction")
        }
        if (direction !in setOf("up", "down", "left", "right")) {
            return ValidationResult.Invalid("direction must be one of: up, down, left, right")
        }
        val distance = params.optString("distance", "").trim().lowercase()
        if (distance.isNotEmpty() && distance !in setOf("short", "medium", "long")) {
            return ValidationResult.Invalid("distance must be one of: short, medium, long")
        }
        return ValidationResult.Valid
    }

    // ============================================================
    // Target parsing + description building
    // ============================================================

    private fun parseOptionalTarget(params: JSONObject): Target? = when {
        params.has("element_index") ->
            Target.ElementIndex(params.getInt("element_index"))
        params.optString("text", "").trim().isNotEmpty() ->
            Target.Text(params.getString("text"), params.optInt("text_index", 0))
        params.has("x") && params.has("y") ->
            Target.Coordinate(params.getInt("x"), params.getInt("y"))
        else -> null
    }

    private fun buildDescription(action: String, target: Target?, params: JSONObject): String {
        val targetDesc = when (target) {
            is Target.ElementIndex -> "element ${target.index}"
            is Target.Text -> "text \"${target.text}\" (index ${target.textIndex})"
            is Target.Coordinate -> "(${target.x},${target.y})"
            null -> if (action == "type") "focused field" else ""
        }
        return when (action) {
            "click" -> "Click $targetDesc"
            "long_press" -> "Long press $targetDesc for ${params.optLong("duration_ms", 1000)}ms"
            "type" -> {
                val input = params.optString("input_text", "").take(30)
                val clear = if (params.optBoolean("clear", false)) " (clear first)" else ""
                "Type \"$input\" into $targetDesc$clear"
            }
            "swipe" -> {
                val dir = params.optString("direction", "").ifEmpty { "explicit" }
                val dist = params.optString("distance", "medium").ifEmpty { "medium" }
                if (targetDesc.isNotEmpty()) "Swipe $dir ($dist) from $targetDesc"
                else "Swipe $dir ($dist)"
            }
            else -> "$action $targetDesc"
        }
    }

    private fun targetNames(hasElement: Boolean, hasText: Boolean, hasCoords: Boolean): String {
        return buildList {
            if (hasElement) add("element_index")
            if (hasText) add("text")
            if (hasCoords) add("x/y")
        }.joinToString(", ")
    }

    // ============================================================
    // Schema
    // ============================================================

    private fun buildSchema(): JSONObject {
        val properties = JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("click", "long_press", "swipe", "type")))
                put("description", "The action to perform")
            })
            put("agent_thought", JSONObject().apply {
                put("type", "string")
                put("description", "Brief reason for why this action is being performed")
            })
            put("element_index", JSONObject().apply {
                put("type", "integer")
                put("description", "Index from current screen state. Preferred selector when available.")
            })
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "Target element by visible text. Use text_index for disambiguation.")
            })
            put("text_index", JSONObject().apply {
                put("type", "integer")
                put("description", "Zero-based index when multiple elements match text (default 0)")
            })
            put("x", JSONObject().apply {
                put("type", "integer")
                put("description", "Target X coordinate in pixels")
            })
            put("y", JSONObject().apply {
                put("type", "integer")
                put("description", "Target Y coordinate in pixels")
            })
            put("input_text", JSONObject().apply {
                put("type", "string")
                put("description", "Text to type (type action only)")
            })
            put("clear", JSONObject().apply {
                put("type", "boolean")
                put("description", "Clear field before typing (type action, default false)")
            })
            put("start", JSONObject().apply {
                put("type", "array")
                put("description", "Swipe start coordinate [x, y] in pixels")
                put("items", JSONObject().put("type", "integer"))
            })
            put("end", JSONObject().apply {
                put("type", "array")
                put("description", "Swipe end coordinate [x, y] in pixels")
                put("items", JSONObject().put("type", "integer"))
            })
            put("direction", JSONObject().apply {
                put("type", "string")
                put("description", "Swipe direction. up/down scroll content opposite direction.")
                put("enum", JSONArray(listOf("up", "down", "left", "right")))
            })
            put("distance", JSONObject().apply {
                put("type", "string")
                put("description", "Directional swipe distance: short=1/4, medium=1/2, long=3/4 screen (default medium)")
                put("enum", JSONArray(listOf("short", "medium", "long")))
            })
            put("duration_ms", JSONObject().apply {
                put("type", "integer")
                put("description", "Hold duration for long_press in milliseconds (default 1000)")
            })
        }

        return JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            put("required", JSONArray(listOf("action")))
            put("additionalProperties", false)
        }
    }
}

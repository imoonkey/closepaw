package com.moonkey.androidagent.tool.impl.mobileaction

import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.handlers.ActionHandler
import com.moonkey.androidagent.tool.handlers.LongPressTargetInvocation
import org.json.JSONObject

/**
 * Long press action - press and hold on an element using multi-selector targeting.
 */
class LongPressActionHandler : ActionHandler {
    override val actionName = "long_press"

    companion object {
        private const val DEFAULT_DURATION_MS = 1000L
        private const val MAX_DURATION_MS = 30000L
    }

    override fun validate(params: JSONObject): ValidationResult {
        val hasBounds = params.has("x1") || params.has("y1") || params.has("x2") || params.has("y2")
        val hasPoint = params.has("x") || params.has("y")
        val hasElementIndex = params.has("element_index")
        val resourceId = params.optString("resource_id", "").trim()
        val text = params.optString("text", "").trim()

        if (!hasBounds && !hasPoint && !hasElementIndex && resourceId.isEmpty() && text.isEmpty()) {
            return ValidationResult.Invalid(
                "long_press action requires one of: bounds (x1,y1,x2,y2), x/y, resource_id, text, or element_index"
            )
        }

        if (hasElementIndex) {
            val idx = params.optInt("element_index", -1)
            if (idx < 0) {
                return ValidationResult.Invalid("element_index must be >= 0")
            }
        }

        if (hasPoint) {
            if (!params.has("x") || !params.has("y")) {
                return ValidationResult.Invalid("long_press action requires both x and y when using coordinates")
            }
            val x = params.optInt("x", -1)
            val y = params.optInt("y", -1)
            if (x < 0 || y < 0) {
                return ValidationResult.Invalid("x and y must be >= 0")
            }
        }

        if (hasBounds) {
            val required = listOf("x1", "y1", "x2", "y2")
            val missing = required.filterNot { params.has(it) }
            if (missing.isNotEmpty()) {
                return ValidationResult.Invalid("long_press bounds require ${missing.joinToString()}")
            }
            val x1 = params.optInt("x1", -1)
            val y1 = params.optInt("y1", -1)
            val x2 = params.optInt("x2", -1)
            val y2 = params.optInt("y2", -1)
            if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
                return ValidationResult.Invalid("x1, y1, x2, y2 must be >= 0")
            }
            if (x2 < x1 || y2 < y1) {
                return ValidationResult.Invalid("x2 must be >= x1 and y2 must be >= y1")
            }
        }

        if (params.has("resource_id_index") && resourceId.isEmpty()) {
            return ValidationResult.Invalid("resource_id_index requires resource_id")
        }

        if (params.has("text_index") && text.isEmpty()) {
            return ValidationResult.Invalid("text_index requires text")
        }

        val durationMs = params.optLong("duration_ms", DEFAULT_DURATION_MS)
        if (durationMs < 0) {
            return ValidationResult.Invalid("duration_ms must be non-negative")
        }
        if (durationMs > MAX_DURATION_MS) {
            return ValidationResult.Invalid("duration_ms must be <= $MAX_DURATION_MS")
        }

        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val durationMs = params.optLong("duration_ms", DEFAULT_DURATION_MS)
        val description = buildLongPressDescription(params, durationMs)
        return LongPressTargetInvocation(
            params = params,
            description = description
        )
    }
}

internal fun buildLongPressDescription(params: JSONObject, durationMs: Long): String {
    val resourceId = params.optString("resource_id", "").trim()
    val text = params.optString("text", "").trim()
    val hasBounds = params.has("x1") && params.has("y1") && params.has("x2") && params.has("y2")
    val hasPoint = params.has("x") && params.has("y")
    return when {
        resourceId.isNotEmpty() ->
            "Long press resource_id '$resourceId' (index ${params.optInt("resource_id_index", 0)}) for ${durationMs}ms"
        text.isNotEmpty() ->
            "Long press text \"$text\" (index ${params.optInt("text_index", 0)}) for ${durationMs}ms"
        hasBounds -> {
            val x1 = params.optInt("x1", -1)
            val y1 = params.optInt("y1", -1)
            val x2 = params.optInt("x2", -1)
            val y2 = params.optInt("y2", -1)
            "Long press bounds ($x1,$y1)-($x2,$y2) for ${durationMs}ms"
        }
        hasPoint -> "Long press at (${params.optInt("x", -1)},${params.optInt("y", -1)}) for ${durationMs}ms"
        else -> "Long press element ${params.optInt("element_index", -1)} for ${durationMs}ms"
    }
}


package com.moonkey.androidagent.tool.impl.mobileaction

import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.handlers.ActionHandler
import com.moonkey.androidagent.tool.handlers.TypeTargetInvocation
import org.json.JSONObject

/**
 * Type action - input text into a field using multi-selector targeting to focus first.
 */
class TypeActionHandler : ActionHandler {
    override val actionName = "type"

    override fun validate(params: JSONObject): ValidationResult {
        if (!params.has("input_text")) {
            return ValidationResult.Invalid("type action requires input_text")
        }

        val hasBounds = params.has("x1") || params.has("y1") || params.has("x2") || params.has("y2")
        val hasPoint = params.has("x") || params.has("y")
        val resourceId = params.optString("resource_id", "").trim()
        val targetText = params.optString("text", "").trim()

        // element_index is optional (can type into currently focused field)
        if (params.has("element_index")) {
            val idx = params.optInt("element_index", -1)
            if (idx < 0) {
                return ValidationResult.Invalid("element_index must be >= 0 if provided")
            }
        }

        if (hasPoint) {
            if (!params.has("x") || !params.has("y")) {
                return ValidationResult.Invalid("type action requires both x and y when using coordinates")
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
                return ValidationResult.Invalid("type bounds require ${missing.joinToString()}")
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

        if (params.has("text_index") && targetText.isEmpty()) {
            return ValidationResult.Invalid("text_index requires text")
        }

        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val description = buildTypeDescription(params)
        return TypeTargetInvocation(
            params = params,
            description = description
        )
    }
}

internal fun buildTypeDescription(params: JSONObject): String {
    val input = params.optString("input_text", "")
    val clear = params.optBoolean("clear", false)
    val resourceId = params.optString("resource_id", "").trim()
    val targetText = params.optString("text", "").trim()
    val hasBounds = params.has("x1") && params.has("y1") && params.has("x2") && params.has("y2")
    val hasPoint = params.has("x") && params.has("y")
    val hasElementIndex = params.has("element_index") && params.optInt("element_index", -1) >= 0

    val inputPreview = input.take(30)
    val target = when {
        resourceId.isNotEmpty() ->
            "resource_id '$resourceId' (index ${params.optInt("resource_id_index", 0)})"
        targetText.isNotEmpty() ->
            "text \"$targetText\" (index ${params.optInt("text_index", 0)})"
        hasBounds -> {
            val x1 = params.optInt("x1", -1)
            val y1 = params.optInt("y1", -1)
            val x2 = params.optInt("x2", -1)
            val y2 = params.optInt("y2", -1)
            "bounds ($x1,$y1)-($x2,$y2)"
        }
        hasPoint -> "coordinates (${params.optInt("x", -1)},${params.optInt("y", -1)})"
        hasElementIndex -> "element ${params.optInt("element_index", -1)}"
        else -> "focused field"
    }

    return "Type \"$inputPreview\" into $target${if (clear) " (clear first)" else ""}"
}

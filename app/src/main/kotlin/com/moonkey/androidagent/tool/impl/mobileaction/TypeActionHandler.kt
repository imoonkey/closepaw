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

        val hasPoint = params.has("x") || params.has("y")
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

        val hasBounds =
            params.has("x1") || params.has("y1") || params.has("x2") || params.has("y2")
        if (hasBounds) {
            return ValidationResult.Invalid(
                "type action no longer accepts bounds (x1,y1,x2,y2); use element_index, text, or x/y"
            )
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
    val targetText = params.optString("text", "").trim()
    val hasPoint = params.has("x") && params.has("y")
    val hasElementIndex = params.has("element_index") && params.optInt("element_index", -1) >= 0

    val inputPreview = input.take(30)
    val target = when {
        targetText.isNotEmpty() ->
            "text \"$targetText\" (index ${params.optInt("text_index", 0)})"
        hasPoint -> "coordinates (${params.optInt("x", -1)},${params.optInt("y", -1)})"
        hasElementIndex -> "element ${params.optInt("element_index", -1)}"
        else -> "focused field"
    }

    return "Type \"$inputPreview\" into $target${if (clear) " (clear first)" else ""}"
}

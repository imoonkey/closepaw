package com.moonkey.androidagent.tool.impl.mobileaction

import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.handlers.ActionHandler
import com.moonkey.androidagent.tool.handlers.ClickTargetInvocation
import org.json.JSONObject

/**
 * Click action with explicit selector and API fallback.
 */
class ClickActionHandler : ActionHandler {
    override val actionName = "click"

    override fun validate(params: JSONObject): ValidationResult {
        val hasPoint = params.has("x") || params.has("y")
        val hasElementIndex = params.has("element_index")
        val text = params.optString("text", "").trim()

        if (!hasPoint && !hasElementIndex && text.isEmpty()) {
            return ValidationResult.Invalid(
                "click action requires one of: element_index, text, or x/y coordinates"
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
                return ValidationResult.Invalid("click action requires both x and y when using coordinates")
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
                "click action no longer accepts bounds (x1,y1,x2,y2); use element_index, text, or x/y"
            )
        }

        if (params.has("text_index") && text.isEmpty()) {
            return ValidationResult.Invalid("text_index requires text")
        }

        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val description = buildClickDescription(params)
        return ClickTargetInvocation(
            params = params,
            description = description
        )
    }
}

internal fun buildClickDescription(params: JSONObject): String {
    val text = params.optString("text", "").trim()
    val hasPoint = params.has("x") && params.has("y")
    return when {
        params.has("element_index") -> "Click element ${params.optInt("element_index", -1)}"
        text.isNotEmpty() -> "Click text \"$text\" (index ${params.optInt("text_index", 0)})"
        hasPoint -> "Click at (${params.optInt("x", -1)},${params.optInt("y", -1)})"
        else -> "Click target"
    }
}

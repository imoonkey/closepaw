package com.moonkey.androidagent.tool.impl.mobileaction

import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.handlers.ActionHandler
import com.moonkey.androidagent.tool.handlers.ClickTargetInvocation
import org.json.JSONObject

/**
 * Click action - tap using multi-selector targeting with fallback order.
 */
class ClickActionHandler : ActionHandler {
    override val actionName = "click"

    override fun validate(params: JSONObject): ValidationResult {
        val hasBounds = params.has("x1") || params.has("y1") || params.has("x2") || params.has("y2")
        val hasPoint = params.has("x") || params.has("y")
        val hasElementIndex = params.has("element_index")
        val text = params.optString("text", "").trim()

        if (!hasBounds && !hasPoint && !hasElementIndex && text.isEmpty()) {
            return ValidationResult.Invalid(
                "click action requires one of: bounds (x1,y1,x2,y2), x/y, text, or element_index"
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

        if (hasBounds) {
            val required = listOf("x1", "y1", "x2", "y2")
            val missing = required.filterNot { params.has(it) }
            if (missing.isNotEmpty()) {
                return ValidationResult.Invalid("click bounds require ${missing.joinToString()}")
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
    val hasBounds = params.has("x1") && params.has("y1") && params.has("x2") && params.has("y2")
    val hasPoint = params.has("x") && params.has("y")
    return when {
        text.isNotEmpty() -> "Click text \"$text\" (index ${params.optInt("text_index", 0)})"
        hasBounds -> {
            val x1 = params.optInt("x1", -1)
            val y1 = params.optInt("y1", -1)
            val x2 = params.optInt("x2", -1)
            val y2 = params.optInt("y2", -1)
            "Click bounds ($x1,$y1)-($x2,$y2)"
        }
        hasPoint -> "Click at (${params.optInt("x", -1)},${params.optInt("y", -1)})"
        else -> "Click element ${params.optInt("element_index", -1)}"
    }
}

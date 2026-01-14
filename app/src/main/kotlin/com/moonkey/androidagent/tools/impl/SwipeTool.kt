package com.moonkey.androidagent.tools.impl

import com.moonkey.androidagent.infra.tools.ValidationResult
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tools.base.BaseTool
import org.json.JSONObject

/**
 * SwipeTool - Swipe from one point to another on the screen.
 */
class SwipeTool : BaseTool() {
    
    override val name: String = "swipe"
    
    override val description: String = 
        "Swipe from one screen coordinate to another."
    
    override val parameterSchema: JSONObject = createSchema(
        properties = mapOf(
            "start_x" to ("integer" to "Starting X coordinate"),
            "start_y" to ("integer" to "Starting Y coordinate"),
            "end_x" to ("integer" to "Ending X coordinate"),
            "end_y" to ("integer" to "Ending Y coordinate"),
            "duration_ms" to ("integer" to "Duration of swipe in milliseconds (optional, default 300)")
        ),
        required = listOf("start_x", "start_y", "end_x", "end_y")
    )
    
    override fun validate(params: JSONObject): ValidationResult {
        val errors = mutableListOf<String>()
        
        validateRequiredInt(params, "start_x", errors)
        validateRequiredInt(params, "start_y", errors)
        validateRequiredInt(params, "end_x", errors)
        validateRequiredInt(params, "end_y", errors)
        
        val durationMs = params.optInt("duration_ms", 300)
        if (durationMs < 0) {
            errors.add("duration_ms must be non-negative")
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }
    
    override fun createUIAction(params: JSONObject): UIAction? {
        val startX = params.optInt("start_x", -1)
        val startY = params.optInt("start_y", -1)
        val endX = params.optInt("end_x", -1)
        val endY = params.optInt("end_y", -1)
        val durationMs = params.optLong("duration_ms", 300)
        
        if (startX < 0 || startY < 0 || endX < 0 || endY < 0) return null
        
        return UIAction.Swipe(startX, startY, endX, endY, durationMs)
    }
    
    override fun getActionDescription(params: JSONObject): String {
        val startX = params.optInt("start_x", 0)
        val startY = params.optInt("start_y", 0)
        val endX = params.optInt("end_x", 0)
        val endY = params.optInt("end_y", 0)
        return "Swipe from ($startX, $startY) to ($endX, $endY)"
    }
}


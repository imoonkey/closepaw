package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.platform.ScrollDirection
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.BaseTool
import org.json.JSONObject

/**
 * ScrollTool - Scroll the screen in a direction.
 */
class ScrollTool : BaseTool() {
    
    override val name: String = "scroll"
    
    override val description: String = 
        "Scroll the screen in a specified direction (up, down, left, right)."
    
    override val parameterSchema: JSONObject = createSchema(
        properties = mapOf(
            "direction" to ("string" to "Direction to scroll: up, down, left, or right")
        ),
        required = listOf("direction")
    )
    
    override fun validate(params: JSONObject): ValidationResult {
        val errors = mutableListOf<String>()
        
        val direction = validateRequiredString(params, "direction", errors)
        if (direction != null) {
            val validDirections = listOf("up", "down", "left", "right")
            if (direction.lowercase() !in validDirections) {
                errors.add("direction must be one of: ${validDirections.joinToString(", ")}")
            }
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }
    
    override fun createUIAction(params: JSONObject): UIAction? {
        val directionStr = params.optString("direction", "down")
        val direction = when (directionStr.lowercase()) {
            "up" -> ScrollDirection.UP
            "down" -> ScrollDirection.DOWN
            "left" -> ScrollDirection.LEFT
            "right" -> ScrollDirection.RIGHT
            else -> ScrollDirection.DOWN
        }
        return UIAction.Scroll(direction)
    }
    
    override fun getActionDescription(params: JSONObject): String {
        val direction = params.optString("direction", "down")
        return "Scroll $direction"
    }
}


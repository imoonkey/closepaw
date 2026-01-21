package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.BaseTool
import org.json.JSONObject

/**
 * WaitTool - Wait for a specified duration.
 */
class WaitTool : BaseTool() {
    
    companion object {
        private const val DEFAULT_DURATION_MS = 1000L
        private const val MAX_DURATION_MS = 30000L
    }
    
    override val name: String = "wait"
    
    override val description: String = 
        "Wait for a specified duration in milliseconds (default: 1000ms, max: 30000ms)."
    
    override val parameterSchema: JSONObject = createSchema(
        properties = mapOf(
            "duration_ms" to ("integer" to "Duration to wait in milliseconds (optional, default 1000)")
        ),
        required = emptyList()
    )
    
    override fun validate(params: JSONObject): ValidationResult {
        val errors = mutableListOf<String>()
        
        // M8: Validate type if parameter is present
        // Check for specific numeric types since JSONObject returns Int, Long, or Double
        if (params.has("duration_ms")) {
            val value = params.get("duration_ms")
            if (value !is Int && value !is Long && value !is Double) {
                errors.add("duration_ms must be a number, got ${value::class.simpleName}")
            }
        }
        
        val durationMs = params.optLong("duration_ms", DEFAULT_DURATION_MS)
        if (durationMs < 0) {
            errors.add("duration_ms must be non-negative")
        }
        if (durationMs > MAX_DURATION_MS) {
            errors.add("duration_ms must not exceed $MAX_DURATION_MS")
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors)
        }
    }
    
    override fun createUIAction(params: JSONObject): UIAction {
        val durationMs = params.optLong("duration_ms", DEFAULT_DURATION_MS)
            .coerceIn(0, MAX_DURATION_MS)
        return UIAction.Wait(durationMs)
    }
    
    override fun getActionDescription(params: JSONObject): String {
        val durationMs = params.optLong("duration_ms", DEFAULT_DURATION_MS)
        return "Wait for ${durationMs}ms"
    }
}


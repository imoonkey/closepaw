package com.moonkey.androidagent.tool

import com.moonkey.androidagent.tool.handlers.ActionHandler
import org.json.JSONArray
import org.json.JSONObject

/**
 * MultiActionTool - Base class for tools that dispatch to multiple action handlers.
 * 
 * This pattern is inspired by Mobile-Agent-v3's consolidated tool approach,
 * where a single tool like "mobile_use" can perform multiple actions
 * (click, type, swipe, etc.) based on an "action" parameter.
 * 
 * Benefits:
 * - Reduces prefill context length (one tool schema instead of many)
 * - Cleaner semantic grouping for the LLM
 * - Easier to extend with new actions
 * 
 * Subclasses define:
 * - actionHandlers: Map of action name to handler
 * - description: Tool description including all action descriptions
 * - parameterSchema: Combined schema with action enum and all params
 */
abstract class MultiActionTool : ToolSpec {
    
    /**
     * Map of action names to their handlers.
     * 
     * Example:
     * ```
     * override val actionHandlers = mapOf(
     *     "click" to ClickActionHandler(),
     *     "type" to TypeActionHandler(),
     *     "swipe" to SwipeActionHandler()
     * )
     * ```
     */
    protected abstract val actionHandlers: Map<String, ActionHandler>
    
    override fun validate(params: JSONObject): ValidationResult {
        // 1. Check action parameter exists
        val action = params.optString("action", "")
        if (action.isEmpty()) {
            return ValidationResult.Invalid("Missing required parameter: action")
        }
        
        // 2. Check action is valid
        val handler = actionHandlers[action]
        if (handler == null) {
            val validActions = actionHandlers.keys.sorted().joinToString(", ")
            return ValidationResult.Invalid(
                "Unknown action: '$action'. Valid actions: $validActions"
            )
        }
        
        // 3. Delegate to action-specific validation
        return handler.validate(params)
    }
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        val action = params.getString("action")
        return actionHandlers[action]!!.createInvocation(params)
    }
    
    // =================================================================
    // Schema Helpers
    // =================================================================
    
    /**
     * Create a parameter schema with action enum and additional properties.
     * 
     * Automatically generates the action enum from actionHandlers keys.
     */
    protected fun createActionSchema(
        actionDescription: String,
        additionalProperties: Map<String, PropertySpec>
    ): JSONObject {
        val properties = JSONObject().apply {
            // Action enum
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(actionHandlers.keys.sorted()))
                put("description", actionDescription)
            })
            
            // Additional properties
            additionalProperties.forEach { (name, spec) ->
                put(name, spec.toJSON())
            }
        }
        
        return JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            put("required", JSONArray(listOf("action")))
            put("additionalProperties", false)
        }
    }
    
    /**
     * Property specification for schema generation.
     */
    data class PropertySpec(
        val type: String,
        val description: String,
        val enum: List<String>? = null,
        val items: JSONObject? = null  // For array types
    ) {
        fun toJSON(): JSONObject = JSONObject().apply {
            put("type", type)
            put("description", description)
            enum?.let { put("enum", JSONArray(it)) }
            items?.let { put("items", it) }
        }
    }
}

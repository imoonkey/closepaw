package com.moonkey.androidagent.tool.handlers

import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ValidationResult
import org.json.JSONObject

/**
 * ActionHandler - Handler for a specific action within a multi-action tool.
 * 
 * This pattern is inspired by Mobile-Agent-v3's consolidated tool approach,
 * where a single tool like "mobile_use" dispatches to different action handlers
 * based on an "action" parameter.
 * 
 * Each action handler is responsible for:
 * 1. Validating action-specific parameters
 * 2. Creating the appropriate ToolInvocation
 */
interface ActionHandler {
    
    /**
     * The name of this action (e.g., "click", "type", "swipe").
     */
    val actionName: String
    
    /**
     * Validate the action-specific parameters.
     * 
     * The "action" parameter itself has already been validated by the parent tool.
     * This should validate any other required parameters for this specific action.
     * 
     * @param params Full parameters including action
     * @return ValidationResult
     */
    fun validate(params: JSONObject): ValidationResult
    
    /**
     * Create a ToolInvocation for this action.
     * 
     * Called after validation passes.
     * 
     * @param params Full parameters including action
     * @return ToolInvocation ready to execute
     */
    fun createInvocation(params: JSONObject): ToolInvocation
}

package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolSpec
import com.moonkey.androidagent.tool.ValidationResult
import org.json.JSONObject

/**
 * CompleteTaskTool - Signals that the agent has completed the task.
 * 
 * This tool should be called when the goal has been achieved.
 * It provides a structured way for the agent to indicate completion
 * instead of relying on string pattern matching in the response.
 */
class CompleteTaskTool : ToolSpec {
    
    override val name: String = "complete_task"
    
    override val description: String = 
        "Call this tool when you have successfully completed the user's goal. " +
        "Provide a summary of what was accomplished."
    
    override val parameterSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("summary", JSONObject().apply {
                put("type", "string")
                put("description", "A brief summary of what was accomplished to achieve the goal")
            })
        })
        put("required", org.json.JSONArray().apply {
            put("summary")
        })
        put("additionalProperties", false)  // Required for strict mode
    }
    
    override fun validate(params: JSONObject): ValidationResult {
        if (!params.has("summary")) {
            return ValidationResult.Invalid("Missing required parameter: summary")
        }
        val summary = params.optString("summary", "")
        if (summary.isBlank()) {
            return ValidationResult.Invalid("summary cannot be empty")
        }
        return ValidationResult.Valid
    }
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        return CompleteTaskInvocation(params)
    }
}

/**
 * Invocation for CompleteTaskTool.
 * This doesn't perform any UI action - it just signals completion.
 */
class CompleteTaskInvocation(
    override val params: JSONObject
) : ToolInvocation {
    
    override val toolName: String = "complete_task"
    
    override fun getDescription(): String {
        val summary = params.optString("summary", "Task completed")
        return "Complete task: $summary"
    }
    
    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        val summary = params.optString("summary", "Task completed")
        return ToolExecutionResult.Success(
            output = "Task completed: $summary",
            data = mapOf("completed" to true, "summary" to summary)
        )
    }
}

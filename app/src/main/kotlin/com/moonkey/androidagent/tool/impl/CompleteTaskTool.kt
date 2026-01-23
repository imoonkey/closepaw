package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolSpec
import com.moonkey.androidagent.tool.ValidationResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * CompleteTaskTool - Agent metatool for finishing a task.
 * 
 * This tool should be called when the agent has finished working on the task,
 * whether successfully or not. It provides:
 * 
 * 1. A structured way to signal task completion
 * 2. Success/failure status for tracking
 * 3. An answer to return to the user
 * 
 * The answer is ALWAYS returned to the user, regardless of status.
 * For failures, the reason should explain why the task couldn't be completed.
 */
class CompleteTaskTool : ToolSpec {
    
    override val name: String = "complete_task"
    
    override val description: String = """
Call this when you have finished working on the task.

Parameters:
- status: "success" if the goal was achieved, "failure" if it cannot be completed
- answer: The response to return to the user (always required)
- reason: If status is "failure", explain why (optional but recommended)

Always provide a helpful answer even when failing - explain what you tried and why it didn't work.
""".trimIndent()
    
    override val parameterSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("status", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("success", "failure")))
                put("description", "Whether the task succeeded or failed")
            })
            put("answer", JSONObject().apply {
                put("type", "string")
                put("description", "The answer or result to return to the user")
            })
            put("reason", JSONObject().apply {
                put("type", "string")
                put("description", "If failure, explain why the task could not be completed")
            })
        })
        put("required", JSONArray(listOf("status", "answer")))
        put("additionalProperties", false)
    }
    
    override fun validate(params: JSONObject): ValidationResult {
        // Check status
        if (!params.has("status")) {
            return ValidationResult.Invalid("Missing required parameter: status")
        }
        val status = params.optString("status", "")
        if (status !in listOf("success", "failure")) {
            return ValidationResult.Invalid("status must be 'success' or 'failure'")
        }
        
        // Check answer
        if (!params.has("answer")) {
            return ValidationResult.Invalid("Missing required parameter: answer")
        }
        val answer = params.optString("answer", "")
        if (answer.isBlank()) {
            return ValidationResult.Invalid("answer cannot be empty")
        }
        
        return ValidationResult.Valid
    }
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        return CompleteTaskInvocation(params)
    }
}

/**
 * Invocation for CompleteTaskTool.
 * 
 * This doesn't perform any UI action - it signals task completion
 * and provides data for the agent loop to extract.
 */
class CompleteTaskInvocation(
    override val params: JSONObject
) : ToolInvocation {
    
    override val toolName: String = "complete_task"
    
    override fun getDescription(): String {
        val status = params.optString("status", "unknown")
        val answer = params.optString("answer", "")
        val preview = if (answer.length > 50) answer.take(50) + "..." else answer
        return "Complete task ($status): $preview"
    }
    
    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        val status = params.optString("status", "success")
        val answer = params.optString("answer", "Task completed")
        val reason = if (params.has("reason")) params.optString("reason", "") else null
        
        val isSuccess = status == "success"
        
        // Build output message
        val output = buildString {
            if (isSuccess) {
                append("Task completed successfully.\n")
            } else {
                append("Task failed.\n")
                if (reason != null) {
                    append("Reason: $reason\n")
                }
            }
            append("\nAnswer: $answer")
        }
        
        return ToolExecutionResult.Success(
            output = output,
            data = mapOf(
                "completed" to true,
                "success" to isSuccess,
                "answer" to answer,
                "reason" to reason
            )
        )
    }
}

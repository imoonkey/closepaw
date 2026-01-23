package com.moonkey.androidagent.tool.handlers

import android.util.Log
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolObservation
import org.json.JSONObject

/**
 * DataQueryInvocation - A ToolInvocation that queries data without UI interaction.
 * 
 * Used for tools like list_apps that return data to the LLM rather than
 * performing screen interactions.
 * 
 * Unlike UIActionInvocation, this does NOT capture post-action screen state
 * since no screen changes occur.
 */
class DataQueryInvocation(
    override val toolName: String,
    override val params: JSONObject,
    private val description: String,
    private val queryFn: suspend (ToolExecutionContext) -> String
) : ToolInvocation {
    
    companion object {
        private const val TAG = "DataQueryInvocation"
    }
    
    override fun getDescription(): String = description
    
    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) {
            return ToolExecutionResult.Cancelled("Cancelled before execution")
        }
        
        return try {
            val result = queryFn(context)
            ToolExecutionResult.Success(
                output = result,
                observation = ToolObservation.TextOutput(result)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Data query failed", e)
            ToolExecutionResult.Failure(
                error = "Query failed: ${e.message}",
                exception = e
            )
        }
    }
}

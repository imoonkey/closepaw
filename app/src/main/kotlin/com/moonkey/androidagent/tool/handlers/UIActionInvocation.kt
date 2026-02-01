package com.moonkey.androidagent.tool.handlers

import android.util.Log
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolObservation
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * UIActionInvocation - A ToolInvocation that executes a UIAction.
 * 
 * This is the common invocation type for all UI-based actions like
 * click, type, swipe, system buttons, etc.
 * 
 * After execution, it captures the post-action screen state so the
 * agent can observe what changed.
 */
class UIActionInvocation(
    override val toolName: String,
    override val params: JSONObject,
    private val description: String,
    private val uiAction: UIAction
) : ToolInvocation {
    
    companion object {
        private const val TAG = "UIActionInvocation"
        private const val UI_SETTLE_DELAY_MS = 300L
    }
    
    override fun getDescription(): String {
        val agentThought = params.optString("agent_thought", "").trim()
        return if (agentThought.isNotEmpty()) {
            "$description (reason: $agentThought)"
        } else {
            description
        }
    }
    
    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) {
            return ToolExecutionResult.Cancelled("Cancelled before execution")
        }
        
        val result = context.platform.performAction(uiAction, context.currentSnapshot)
        
        return when (result) {
            is ActionResult.Success -> {
                val observation = capturePostActionObservation(context)
                ToolExecutionResult.Success(
                    output = result.message,
                    observation = observation
                )
            }
            is ActionResult.Failure -> ToolExecutionResult.Failure(result.reason, result.exception)
            is ActionResult.ElementNotFound -> {
                val available = context.currentSnapshot?.elements?.map { it.index } ?: emptyList()
                val availableSuffix = if (available.isNotEmpty()) {
                    val preview = available.take(20).joinToString(", ")
                    val more = if (available.size > 20) " ... and ${available.size - 20} more" else ""
                    " Available indices: $preview$more"
                } else {
                    ""
                }
                ToolExecutionResult.Failure(
                    "Element not found: index ${result.elementIndex}.$availableSuffix"
                )
            }
            is ActionResult.Cancelled -> ToolExecutionResult.Cancelled(result.reason)
        }
    }
    
    /**
     * Capture the screen state after action execution.
     */
    private suspend fun capturePostActionObservation(context: ToolExecutionContext): ToolObservation? {
        return try {
            // Brief delay for UI to settle
            delay(UI_SETTLE_DELAY_MS)
            
            val snapshot = context.platform.captureScreen()
            val tree = Perceptor.toPromptJson(snapshot)
            
            ToolObservation.ScreenState(
                accessibilityTree = tree,
                elementCount = snapshot.elements.size,
                snapshot = snapshot
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture post-action observation: ${e.message}")
            null
        }
    }
}

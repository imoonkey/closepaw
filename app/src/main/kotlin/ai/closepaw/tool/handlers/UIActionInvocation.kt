package ai.closepaw.tool.handlers

import android.util.Log
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.UIAction
import ai.closepaw.tool.action.buildObservation
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.ToolObservation
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * UIActionInvocation — executes a UIAction for SystemButtonTool and WaitTool.
 *
 * For mobile_action (click/type/swipe/long_press), MobileActionInvocation is used instead.
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

        val result = context.platform.performAction(uiAction)

        return when (result) {
            is ActionResult.Success -> {
                val observation = capturePostActionObservation(context)
                ToolExecutionResult.Success(output = result.message, observation = observation)
            }
            is ActionResult.Failure -> ToolExecutionResult.Failure(result.reason)
            is ActionResult.Cancelled -> ToolExecutionResult.Cancelled(result.reason)
        }
    }

    private suspend fun capturePostActionObservation(
        context: ToolExecutionContext
    ): ToolObservation? {
        return try {
            delay(UI_SETTLE_DELAY_MS)
            val snapshot = context.platform.captureScreen()
            buildObservation(snapshot, context.platform, context.appClassifier)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture post-action observation: ${e.message}")
            null
        }
    }
}

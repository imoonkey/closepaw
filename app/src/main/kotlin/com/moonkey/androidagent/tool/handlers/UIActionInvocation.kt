package com.moonkey.androidagent.tool.handlers

import android.util.Log
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.perception.Perceptor
import com.moonkey.androidagent.perception.toSummary
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolObservation
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

        val preSnapshot = context.currentSnapshot
        val result = context.platform.performAction(uiAction)

        return when (result) {
            is ActionResult.Success -> {
                val observation = capturePostActionObservation(context)

                val scrollBoundaryWarning =
                    if (uiAction is UIAction.Swipe) {
                        detectScrollBoundary(preSnapshot, observation)
                    } else {
                        null
                    }

                val outputMessage =
                    if (scrollBoundaryWarning != null) {
                        "${result.message} Warnings: $scrollBoundaryWarning"
                    } else {
                        result.message
                    }

                ToolExecutionResult.Success(output = outputMessage, observation = observation)
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
            val tree = Perceptor.toPromptJson(snapshot)
            ToolObservation.ScreenState(
                accessibilityTree = tree,
                elementCount = snapshot.elements.orEmpty().size,
                summary = snapshot.toSummary(context.platform.getCurrentPackageName()),
                snapshot = snapshot
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture post-action observation: ${e.message}")
            null
        }
    }

    private fun detectScrollBoundary(
        preSnapshot: ScreenSnapshot?,
        postObservation: ToolObservation?
    ): String? {
        if (preSnapshot == null) return null
        val postSnapshot =
            (postObservation as? ToolObservation.ScreenState)?.snapshot ?: return null

        val preTexts = preSnapshot.elements.orEmpty()
            .filter { it.text.isNotBlank() || it.description.isNotBlank() }
            .map { "${it.text}|${it.description}|${it.bounds}" }
            .sorted()

        val postTexts = postSnapshot.elements.orEmpty()
            .filter { it.text.isNotBlank() || it.description.isNotBlank() }
            .map { "${it.text}|${it.description}|${it.bounds}" }
            .sorted()

        return if (preTexts == postTexts && preTexts.isNotEmpty()) {
            "Screen content unchanged after swipe - may have reached scroll boundary"
        } else {
            null
        }
    }
}

package com.moonkey.androidagent.tool.handlers

import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.ToolObservation
import org.json.JSONObject

/**
 * UIActionInvocation - A ToolInvocation that executes a UIAction.
 *
 * This is the common invocation type for all UI-based actions like click, type, swipe, system
 * buttons, etc.
 *
 * After execution, it captures the post-action screen state so the agent can observe what changed.
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
        val result = context.platform.performAction(uiAction, preSnapshot)

        return when (result) {
            is ActionResult.Success -> {
                val observation = capturePostActionObservation(context)

                // For swipe actions, detect scroll boundary
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
            is ActionResult.Failure -> ToolExecutionResult.Failure(result.reason, result.exception)
            is ActionResult.ElementNotFound -> {
                val available = context.currentSnapshot?.elements?.map { it.index } ?: emptyList()
                val availableSuffix =
                        if (available.isNotEmpty()) {
                            val preview = available.take(20).joinToString(", ")
                            val more =
                                    if (available.size > 20) " ... and ${available.size - 20} more"
                                    else ""
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

    /** Capture the screen state after action execution. */
    private suspend fun capturePostActionObservation(
            context: ToolExecutionContext
    ): ToolObservation? {
        return TargetingInvocationUtils.capturePostActionObservation(context, TAG, UI_SETTLE_DELAY_MS)
    }

    /** Delegates to shared utility for scroll boundary detection. */
    private fun detectScrollBoundary(
            preSnapshot: com.moonkey.androidagent.model.ScreenSnapshot?,
            postObservation: ToolObservation?
    ): String? {
        return TargetingInvocationUtils.detectScrollBoundary(preSnapshot, postObservation)
    }
}

package com.moonkey.androidagent.tool.impl

import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import com.moonkey.androidagent.tool.action.ActionOutcome
import org.json.JSONObject

/**
 * Thin glue: routes to executor, maps ActionOutcome to ToolExecutionResult.
 *
 * ~40 lines. Replaces UIActionInvocation + all *TargetInvocation classes
 * for mobile_action.
 */
class MobileActionInvocation(
    override val params: JSONObject,
    private val description: String,
    private val executeAction: suspend (AndroidPlatform, ScreenSnapshot?, () -> Boolean) -> ActionOutcome
) : ToolInvocation {
    override val toolName = "mobile_action"

    override fun getDescription(): String {
        val thought = params.optString("agent_thought", "").trim()
        return if (thought.isNotEmpty()) "$description ($thought)" else description
    }

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) return ToolExecutionResult.Cancelled()
        val outcome = executeAction(context.platform, context.currentSnapshot, context::isCancelled)
        return mapOutcome(outcome)
    }

    private fun mapOutcome(outcome: ActionOutcome): ToolExecutionResult = when (outcome) {
        is ActionOutcome.Success -> {
            val output = buildString {
                append(outcome.message)
                if (!outcome.verified) append(" [unverified]")
                if (outcome.attemptTrail.size > 1) {
                    append("\nAttempts: ${outcome.attemptTrail.joinToString(" -> ")}")
                }
            }
            ToolExecutionResult.Success(output = output, observation = outcome.observation)
        }
        is ActionOutcome.Failed -> {
            val output = buildString {
                append(outcome.reason)
                if (outcome.attemptTrail.size > 1) {
                    append("\nAttempts: ${outcome.attemptTrail.joinToString("; ")}")
                }
            }
            ToolExecutionResult.Failure(output)
        }
        is ActionOutcome.Cancelled -> ToolExecutionResult.Cancelled(outcome.reason)
    }
}

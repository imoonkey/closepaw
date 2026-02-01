package com.moonkey.androidagent.tool.handlers

import android.util.Log
import com.moonkey.androidagent.model.ScreenSnapshot
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
 * Invocation for click action using multi-selector fallback.
 */
class ClickTargetInvocation(
    override val params: JSONObject,
    private val description: String
) : ToolInvocation {

    companion object {
        private const val TAG = "ClickTargetInvocation"
        private const val UI_SETTLE_DELAY_MS = 300L
    }

    override val toolName: String = "mobile_action"

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

        val snapshot = context.currentSnapshot
            ?: return ToolExecutionResult.Failure("Snapshot required for click targeting")

        val attempts = mutableListOf<String>()

        suspend fun attempt(label: String, action: UIAction): ToolExecutionResult? {
            val result = context.platform.performAction(action, snapshot)
            return when (result) {
                is ActionResult.Success -> ToolExecutionResult.Success(
                    output = result.message,
                    observation = capturePostActionObservation(context)
                )
                is ActionResult.Failure -> {
                    attempts.add("$label: ${result.reason}")
                    null
                }
                is ActionResult.ElementNotFound -> {
                    attempts.add("$label: ${buildElementNotFoundMessage(result.elementIndex, snapshot)}")
                    null
                }
                is ActionResult.Cancelled -> ToolExecutionResult.Cancelled(result.reason)
            }
        }

        val hasBounds = params.has("x1") && params.has("y1") && params.has("x2") && params.has("y2")
        if (hasBounds) {
            val x1 = params.optInt("x1", -1)
            val y1 = params.optInt("y1", -1)
            val x2 = params.optInt("x2", -1)
            val y2 = params.optInt("y2", -1)
            val cx = (x1 + x2) / 2
            val cy = (y1 + y2) / 2
            val result = attempt("bounds center ($cx,$cy)", UIAction.ClickAt(cx, cy))
            if (result != null) return result
        }

        val hasPoint = params.has("x") && params.has("y")
        if (hasPoint) {
            val x = params.optInt("x", -1)
            val y = params.optInt("y", -1)
            val result = attempt("coordinates ($x,$y)", UIAction.ClickAt(x, y))
            if (result != null) return result
        }

        val resourceId = params.optString("resource_id", "").trim()
        if (resourceId.isNotEmpty()) {
            val matches = snapshot.elements.filter { it.resourceId == resourceId }
            val index = params.optInt("resource_id_index", 0)
            val element = matches.getOrNull(index)
            if (element == null) {
                attempts.add(
                    "resource_id='$resourceId' index $index out of range (found ${matches.size})"
                )
            } else {
                val result = attempt(
                    "resource_id='$resourceId' index $index",
                    UIAction.Click(element.index)
                )
                if (result != null) return result
            }
        }

        val text = params.optString("text", "").trim()
        if (text.isNotEmpty()) {
            val matches = snapshot.elements.filter {
                it.text.equals(text, ignoreCase = true) ||
                    it.description.equals(text, ignoreCase = true)
            }
            val index = params.optInt("text_index", 0)
            val element = matches.getOrNull(index)
            if (element == null) {
                attempts.add(
                    "text=\"$text\" index $index out of range (found ${matches.size})"
                )
            } else {
                val result = attempt(
                    "text=\"$text\" index $index",
                    UIAction.Click(element.index)
                )
                if (result != null) return result
            }
        }

        if (params.has("element_index")) {
            val idx = params.optInt("element_index", -1)
            if (idx >= 0) {
                val result = attempt("element_index $idx", UIAction.Click(idx))
                if (result != null) return result
            } else {
                attempts.add("element_index must be >= 0")
            }
        }

        val details = if (attempts.isNotEmpty()) {
            " Attempts: ${attempts.joinToString("; ")}"
        } else {
            ""
        }
        return ToolExecutionResult.Failure("Failed to click element.$details")
    }

    private fun buildElementNotFoundMessage(index: Int, snapshot: ScreenSnapshot): String {
        val available = snapshot.elements.map { it.index }
        val preview = available.take(20).joinToString(", ")
        val more = if (available.size > 20) " ... and ${available.size - 20} more" else ""
        return if (available.isNotEmpty()) {
            "Element not found: index $index. Available indices: $preview$more"
        } else {
            "Element not found: index $index. No elements available."
        }
    }

    private suspend fun capturePostActionObservation(context: ToolExecutionContext): ToolObservation? {
        return try {
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

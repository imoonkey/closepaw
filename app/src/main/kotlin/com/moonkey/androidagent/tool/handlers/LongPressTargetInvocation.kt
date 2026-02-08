package com.moonkey.androidagent.tool.handlers

import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import org.json.JSONObject

/**
 * Invocation for long_press action using multi-selector fallback.
 */
class LongPressTargetInvocation(
    override val params: JSONObject,
    private val description: String
) : ToolInvocation {

    companion object {
        private const val TAG = "LongPressTargetInvocation"
        private const val DEFAULT_DURATION_MS = 1000L
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

        val durationMs = params.optLong("duration_ms", DEFAULT_DURATION_MS)
        val snapshot = context.currentSnapshot

        val attempts = mutableListOf<String>()

        suspend fun attempt(label: String, action: UIAction, snapshotForAction: ScreenSnapshot?): ToolExecutionResult? {
            val result = context.platform.performAction(action, snapshotForAction)
            return when (result) {
                is ActionResult.Success -> ToolExecutionResult.Success(
                    output = result.message,
                    observation = TargetingInvocationUtils.capturePostActionObservation(context, TAG)
                )
                is ActionResult.Failure -> {
                    attempts.add("$label: ${result.reason}")
                    null
                }
                is ActionResult.ElementNotFound -> {
                    val snap = snapshotForAction
                    val reason = if (snap != null) {
                        TargetingInvocationUtils.buildElementNotFoundMessage(result.elementIndex, snap)
                    } else {
                        "Element not found: index ${result.elementIndex} (no snapshot available)"
                    }
                    attempts.add("$label: $reason")
                    null
                }
                is ActionResult.Cancelled -> ToolExecutionResult.Cancelled(result.reason)
            }
        }

        val selectorAttempts = MultiSelectorTargeting.attemptsFromParams(
            params = params,
            textKey = "text",
            textIndexKey = "text_index",
            textLabel = "text"
        )

        for (selectorAttempt in selectorAttempts) {
            val selector = selectorAttempt.selector
            val label = selectorAttempt.label

            val result = when (selector) {
                is MultiSelectorTargeting.Selector.Point -> attempt(
                    label = label,
                    action = UIAction.LongClickAt(selector.x, selector.y, durationMs),
                    snapshotForAction = null
                )
                is MultiSelectorTargeting.Selector.Text -> {
                    val snap = snapshot
                    if (snap == null) {
                        attempts.add("$label: Snapshot required for text lookup")
                        null
                    } else {
                        val elementIndex = MultiSelectorTargeting.findElementIndexByTextOrDescription(
                            snapshot = snap,
                            text = selector.text,
                            index = selector.index
                        )
                        if (elementIndex == null) {
                            val count = MultiSelectorTargeting.matchCountByTextOrDescription(snap, selector.text)
                            attempts.add(
                                "text=\"${selector.text}\" index ${selector.index} out of range (found $count)"
                            )
                            null
                        } else {
                            attempt(
                                label = label,
                                action = UIAction.LongClick(elementIndex, durationMs),
                                snapshotForAction = snap
                            )
                        }
                    }
                }
                is MultiSelectorTargeting.Selector.ElementIndex -> {
                    val snap = snapshot
                    if (snap == null) {
                        attempts.add("$label: Snapshot required for element_index long press")
                        null
                    } else {
                        attempt(
                            label = label,
                            action = UIAction.LongClick(selector.elementIndex, durationMs),
                            snapshotForAction = snap
                        )
                    }
                }
            }

            if (result != null) return result
        }

        val details = if (attempts.isNotEmpty()) {
            " Attempts: ${attempts.joinToString("; ")}"
        } else {
            ""
        }
        return ToolExecutionResult.Failure("Failed to long press element.$details")
    }
}

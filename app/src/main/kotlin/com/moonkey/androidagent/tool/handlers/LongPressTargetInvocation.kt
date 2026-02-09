package com.moonkey.androidagent.tool.handlers

import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import org.json.JSONObject

/**
 * Invocation for long_press action with selector fallback.
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
        val attemptLogs = mutableListOf<String>()

        suspend fun attempt(
            label: String,
            action: UIAction,
            snapshotForAction: ScreenSnapshot?,
            snapshotForUiChange: ScreenSnapshot?
        ): ToolExecutionResult? {
            return when (
                val outcome =
                    TargetingInvocationUtils.executeAttempt(
                        context = context,
                        action = action,
                        snapshotForAction = snapshotForAction,
                        snapshotForUiChange = snapshotForUiChange,
                        requireUiChange = true,
                        logTag = TAG
                    )
            ) {
                is TargetingInvocationUtils.AttemptOutcome.Success -> {
                    ToolExecutionResult.Success(
                        output = outcome.message,
                        observation = outcome.observation
                    )
                }
                is TargetingInvocationUtils.AttemptOutcome.Retry -> {
                    attemptLogs.add("$label: ${outcome.reason}")
                    null
                }
                is TargetingInvocationUtils.AttemptOutcome.Cancelled -> {
                    ToolExecutionResult.Cancelled(outcome.reason)
                }
            }
        }

        val selectorAttempts =
            MultiSelectorTargeting.attemptsFromParams(
                params = params,
                textKey = "text",
                textIndexKey = "text_index",
                textLabel = "text"
            )

        for (selectorAttempt in selectorAttempts) {
            val selector = selectorAttempt.selector
            val label = selectorAttempt.label

            val result =
                when (selector) {
                    is MultiSelectorTargeting.Selector.Point -> {
                        attempt(
                            label = label,
                            action = UIAction.LongClickAt(selector.x, selector.y, durationMs),
                            snapshotForAction = null,
                            snapshotForUiChange = snapshot
                        )
                    }
                    is MultiSelectorTargeting.Selector.Text -> {
                        if (snapshot == null) {
                            attemptLogs.add("$label: Snapshot required for text lookup")
                            null
                        } else {
                            val element =
                                MultiSelectorTargeting.resolveElementByTextOrDescription(
                                    snapshot = snapshot,
                                    text = selector.text,
                                    index = selector.index
                                )
                            if (element == null) {
                                val count =
                                    MultiSelectorTargeting.matchCountByTextOrDescription(
                                        snapshot,
                                        selector.text
                                    )
                                attemptLogs.add(
                                    "text=\"${selector.text}\" index ${selector.index} out of range (found $count)"
                                )
                                null
                            } else {
                                attempt(
                                    label = label,
                                    action = UIAction.LongClick(element.index, durationMs),
                                    snapshotForAction = snapshot,
                                    snapshotForUiChange = snapshot
                                )
                            }
                        }
                    }
                    is MultiSelectorTargeting.Selector.ElementIndex -> {
                        if (snapshot == null) {
                            attemptLogs.add("$label: Snapshot required for element_index long press")
                            null
                        } else {
                            val element =
                                MultiSelectorTargeting.resolveElement(
                                    snapshot,
                                    selector.elementIndex
                                )
                            if (element == null) {
                                attemptLogs.add(
                                    TargetingInvocationUtils.buildElementNotFoundMessage(
                                        selector.elementIndex,
                                        snapshot
                                    )
                                )
                                null
                            } else {
                                attempt(
                                    label = label,
                                    action = UIAction.LongClick(element.index, durationMs),
                                    snapshotForAction = snapshot,
                                    snapshotForUiChange = snapshot
                                )
                            }
                        }
                    }
                }

            if (result != null) {
                return result
            }
        }

        val details = if (attemptLogs.isNotEmpty()) {
            " Attempts: ${attemptLogs.joinToString("; ")}"
        } else {
            ""
        }
        return ToolExecutionResult.Failure("Failed to long press element.$details")
    }
}

package com.moonkey.androidagent.tool.handlers

import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ToolInvocation
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Invocation for type action using multi-selector fallback to focus a field.
 *
 * Payload text lives in params["input_text"], while params["text"] is the text selector.
 */
class TypeTargetInvocation(
    override val params: JSONObject,
    private val description: String
) : ToolInvocation {

    companion object {
        private const val TAG = "TypeTargetInvocation"
        private const val FOCUS_DELAY_MS = 120L
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

        val textToType = params.getString("input_text")
        val clear = params.optBoolean("clear", false)
        val snapshot = context.currentSnapshot
        val attemptLogs = mutableListOf<String>()

        suspend fun attemptType(
            label: String,
            focusAction: UIAction?,
            typeAction: UIAction.Type,
            snapshotForType: ScreenSnapshot?
        ): ToolExecutionResult? {
            if (focusAction != null) {
                when (
                    val focusOutcome =
                        TargetingInvocationUtils.executeAttempt(
                            context = context,
                            action = focusAction,
                            snapshotForAction = snapshotForType,
                            captureObservationOnSuccess = false,
                            logTag = TAG
                        )
                ) {
                    is TargetingInvocationUtils.AttemptOutcome.Success -> Unit
                    is TargetingInvocationUtils.AttemptOutcome.Retry -> {
                        attemptLogs.add("$label: focus failed: ${focusOutcome.reason}")
                        return null
                    }
                    is TargetingInvocationUtils.AttemptOutcome.Cancelled -> {
                        return ToolExecutionResult.Cancelled(focusOutcome.reason)
                    }
                }

                delay(FOCUS_DELAY_MS)
            }

            return when (
                val typeOutcome =
                    TargetingInvocationUtils.executeAttempt(
                        context = context,
                        action = typeAction,
                        snapshotForAction = snapshotForType,
                        logTag = TAG
                    )
            ) {
                is TargetingInvocationUtils.AttemptOutcome.Success -> {
                    ToolExecutionResult.Success(
                        output = typeOutcome.message,
                        observation = typeOutcome.observation
                    )
                }
                is TargetingInvocationUtils.AttemptOutcome.Retry -> {
                    attemptLogs.add("$label: ${typeOutcome.reason}")
                    null
                }
                is TargetingInvocationUtils.AttemptOutcome.Cancelled -> {
                    ToolExecutionResult.Cancelled(typeOutcome.reason)
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

        if (selectorAttempts.isEmpty()) {
            val result =
                attemptType(
                    label = "focused field",
                    focusAction = null,
                    typeAction = UIAction.Type(textToType, elementIndex = null, clear = clear),
                    snapshotForType = snapshot
                )
            if (result != null) {
                return result
            }
        } else {
            for (selectorAttempt in selectorAttempts) {
                val selector = selectorAttempt.selector
                val label = selectorAttempt.label

                val result =
                    when (selector) {
                        is MultiSelectorTargeting.Selector.Point -> {
                            attemptType(
                                label = label,
                                focusAction = UIAction.TapAt(selector.x, selector.y),
                                typeAction =
                                    UIAction.Type(textToType, elementIndex = null, clear = clear),
                                snapshotForType = snapshot
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
                                    attemptType(
                                        label = label,
                                        focusAction = null,
                                        typeAction =
                                            UIAction.Type(
                                                textToType,
                                                elementIndex = element.index,
                                                clear = clear
                                            ),
                                        snapshotForType = snapshot
                                    )
                                }
                            }
                        }
                        is MultiSelectorTargeting.Selector.ElementIndex -> {
                            if (snapshot == null) {
                                attemptLogs.add("$label: Snapshot required for element_index focus")
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
                                    attemptType(
                                        label = label,
                                        focusAction = null,
                                        typeAction =
                                            UIAction.Type(
                                                textToType,
                                                elementIndex = element.index,
                                                clear = clear
                                            ),
                                        snapshotForType = snapshot
                                    )
                                }
                            }
                        }
                    }

                if (result != null) {
                    return result
                }
            }
        }

        val details = if (attemptLogs.isNotEmpty()) {
            " Attempts: ${attemptLogs.joinToString("; ")}"
        } else {
            ""
        }
        return ToolExecutionResult.Failure("Failed to type text.$details")
    }
}

package com.moonkey.androidagent.tool.handlers

import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
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

        val attempts = mutableListOf<String>()

        suspend fun attemptType(
            label: String,
            focusAction: UIAction?,
            typeAction: UIAction,
            snapshotForType: ScreenSnapshot?
        ): ToolExecutionResult? {
            if (focusAction != null) {
                val focusResult = context.platform.performAction(focusAction, snapshotForType)
                when (focusResult) {
                    is ActionResult.Success -> Unit
                    is ActionResult.Failure -> {
                        attempts.add("$label: focus failed: ${focusResult.reason}")
                        return null
                    }
                    is ActionResult.ElementNotFound -> {
                        val snap = snapshotForType
                        val reason = if (snap != null) {
                            TargetingInvocationUtils.buildElementNotFoundMessage(focusResult.elementIndex, snap)
                        } else {
                            "Element not found: index ${focusResult.elementIndex} (no snapshot available)"
                        }
                        attempts.add("$label: focus failed: $reason")
                        return null
                    }
                    is ActionResult.Cancelled -> return ToolExecutionResult.Cancelled(focusResult.reason)
                }

                delay(FOCUS_DELAY_MS)
            }

            val result = context.platform.performAction(typeAction, snapshotForType)
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
                    val snap = snapshotForType
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
        val effectiveAttempts = selectorAttempts

        if (effectiveAttempts.isEmpty()) {
            val result = attemptType(
                label = "focused field",
                focusAction = null,
                typeAction = UIAction.Type(textToType, elementIndex = null, clear = clear),
                snapshotForType = snapshot
            )
            if (result != null) return result
        } else {
            for (selectorAttempt in effectiveAttempts) {
                val selector = selectorAttempt.selector
                val label = selectorAttempt.label

                val result = when (selector) {
                    is MultiSelectorTargeting.Selector.Bounds -> {
                        val (cx, cy) = selector.center()
                        attemptType(
                            label = label,
                            focusAction = UIAction.ClickAt(cx, cy),
                            typeAction = UIAction.Type(textToType, elementIndex = null, clear = clear),
                            snapshotForType = snapshot
                        )
                    }
                    is MultiSelectorTargeting.Selector.Point -> attemptType(
                        label = label,
                        focusAction = UIAction.ClickAt(selector.x, selector.y),
                        typeAction = UIAction.Type(textToType, elementIndex = null, clear = clear),
                        snapshotForType = snapshot
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
                                attemptType(
                                    label = label,
                                    focusAction = null,
                                    typeAction = UIAction.Type(textToType, elementIndex = elementIndex, clear = clear),
                                    snapshotForType = snap
                                )
                            }
                        }
                    }
                    is MultiSelectorTargeting.Selector.ElementIndex -> {
                        val snap = snapshot
                        if (snap == null) {
                            attempts.add("$label: Snapshot required for element_index focus")
                            null
                        } else {
                            attemptType(
                                label = label,
                                focusAction = null,
                                typeAction = UIAction.Type(textToType, elementIndex = selector.elementIndex, clear = clear),
                                snapshotForType = snap
                            )
                        }
                    }
                }

                if (result != null) return result
            }
        }

        val details = if (attempts.isNotEmpty()) {
            " Attempts: ${attempts.joinToString("; ")}"
        } else {
            ""
        }
        return ToolExecutionResult.Failure("Failed to type text.$details")
    }
}

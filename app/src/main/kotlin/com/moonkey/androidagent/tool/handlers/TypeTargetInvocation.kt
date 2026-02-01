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
 * Invocation for type action using multi-selector fallback to focus a field.
 *
 * Unlike click/long_press, the payload text lives in params["text"], so this
 * uses params["target_text"] (+ target_text_index) for text-based targeting.
 */
class TypeTargetInvocation(
    override val params: JSONObject,
    private val description: String
) : ToolInvocation {

    companion object {
        private const val TAG = "TypeTargetInvocation"
        private const val UI_SETTLE_DELAY_MS = 300L
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

        val textToType = params.getString("text")
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
                            buildElementNotFoundMessage(focusResult.elementIndex, snap)
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
                    observation = capturePostActionObservation(context)
                )
                is ActionResult.Failure -> {
                    attempts.add("$label: ${result.reason}")
                    null
                }
                is ActionResult.ElementNotFound -> {
                    val snap = snapshotForType
                    val reason = if (snap != null) {
                        buildElementNotFoundMessage(result.elementIndex, snap)
                    } else {
                        "Element not found: index ${result.elementIndex} (no snapshot available)"
                    }
                    attempts.add("$label: $reason")
                    null
                }
                is ActionResult.Cancelled -> ToolExecutionResult.Cancelled(result.reason)
            }
        }

        val targetTextIndexKey = if (params.has("target_text_index")) "target_text_index" else "text_index"
        val selectorAttempts = MultiSelectorTargeting.attemptsFromParams(
            params = params,
            textKey = "target_text",
            textIndexKey = targetTextIndexKey,
            textLabel = "target_text"
        ).let { rawAttempts ->
            val targetText = params.optString("target_text", "").trim()
            val resourceId = params.optString("resource_id", "").trim()
            if (resourceId.isEmpty() || targetText.isEmpty() || snapshot == null) return@let rawAttempts

            val resourceIdAttempt = rawAttempts.firstOrNull {
                it.selector is MultiSelectorTargeting.Selector.ResourceId
            } ?: return@let rawAttempts

            val elementIndex = MultiSelectorTargeting.findElementIndexByResourceId(
                snapshot = snapshot,
                resourceId = resourceId,
                index = params.optInt("resource_id_index", 0)
            ) ?: return@let rawAttempts

            val element = snapshot.elements.firstOrNull { it.index == elementIndex } ?: return@let rawAttempts
            val elementLabel = element.text.ifBlank { element.description }.trim()
            if (elementLabel.isNotEmpty() && !elementLabel.equals(targetText, ignoreCase = true)) {
                attempts.add(
                    "resource_id='$resourceId' ignored: target_text=\"$targetText\" does not match resolved element text/description \"$elementLabel\""
                )
                rawAttempts.filterNot { it === resourceIdAttempt }
            } else {
                rawAttempts
            }
        }

        if (selectorAttempts.isEmpty()) {
            val result = attemptType(
                label = "focused field",
                focusAction = null,
                typeAction = UIAction.Type(textToType, elementIndex = null, clear = clear),
                snapshotForType = snapshot
            )
            if (result != null) return result
        } else {
            for (selectorAttempt in selectorAttempts) {
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
                    is MultiSelectorTargeting.Selector.ResourceId -> {
                        val snap = snapshot
                        if (snap == null) {
                            attempts.add("$label: Snapshot required for resource_id lookup")
                            null
                        } else {
                            val elementIndex = MultiSelectorTargeting.findElementIndexByResourceId(
                                snapshot = snap,
                                resourceId = selector.resourceId,
                                index = selector.index
                            )
                            if (elementIndex == null) {
                                val count = MultiSelectorTargeting.matchCountByResourceId(snap, selector.resourceId)
                                attempts.add(
                                    "resource_id='${selector.resourceId}' index ${selector.index} out of range (found $count)"
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
                    is MultiSelectorTargeting.Selector.Text -> {
                        val snap = snapshot
                        if (snap == null) {
                            attempts.add("$label: Snapshot required for target_text lookup")
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
                                    "target_text=\"${selector.text}\" index ${selector.index} out of range (found $count)"
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
